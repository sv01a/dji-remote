package com.dji360.watch.stream

import java.io.ByteArrayOutputStream

/**
 * Robust, high-performance reassembler for DJI live-view video streams.
 * Accurately tracks DJI frame markers and frame sequence counters, reassembles multi-packet frames,
 * drops corrupted frames to prevent decoder crashes, strips DJI private transport headers,
 * and emits clean Annex-B NAL units directly to MediaCodec.
 */
class VideoDepacketizer(
    private val onFrameComplete: (ByteArray) -> Unit
) {
    private val buffer = ByteArrayOutputStream(256 * 1024)
    private var currentFrameNo: Int = -1
    private var lastFragmentPos: Int = -1
    private var isCorrupt = false

    var emittedFrames: Long = 0L
        private set
    var droppedFrames: Long = 0L
        private set

    fun feed(packet: ByteArray) {
        if (packet.size <= 20 || packet[6] != 0x02.toByte()) return

        val bodyOffset = 20
        val bodyLen = packet.size - bodyOffset
        if (bodyLen <= 0) return

        val frameNo = packet[16].toInt() and 0xFF
        val pos = (packet[18].toInt() and 0xFF) * 2 + ((packet[17].toInt() and 0xFF) ushr 7)

        val startsWithMarker = bodyLen >= 4 &&
                packet[bodyOffset] == 0.toByte() &&
                packet[bodyOffset + 1] == 0.toByte() &&
                packet[bodyOffset + 2] == 1.toByte() &&
                packet[bodyOffset + 3] == 0xFF.toByte()

        val isNewFrame = (pos == 0) || startsWithMarker || (currentFrameNo != -1 && currentFrameNo != frameNo)

        if (isNewFrame) {
            emitCurrentFrame()
            currentFrameNo = frameNo
            lastFragmentPos = -1
            isCorrupt = false
        }

        if (lastFragmentPos != -1 && pos != lastFragmentPos + 1 && pos != lastFragmentPos) {
            isCorrupt = true // Packet dropped in network, mark frame as corrupt
        }
        lastFragmentPos = pos

        if (buffer.size() < 512 * 1024) {
            buffer.write(packet, bodyOffset, bodyLen)
        }
    }

    private fun emitCurrentFrame() {
        if (buffer.size() == 0) return
        val raw = buffer.toByteArray()
        buffer.reset()

        if (isCorrupt) {
            droppedFrames++
            return // Skip broken partial frame to keep hardware decoder safe!
        }

        val stripped = stripDjiMarker(raw)
        if (stripped.isEmpty()) return

        // Ensure standard 4-byte Annex-B start code (00 00 00 01) for Android MediaCodec
        val pureNal = if (stripped.size >= 4 &&
            stripped[0] == 0.toByte() && stripped[1] == 0.toByte() &&
            stripped[2] == 0.toByte() && stripped[3] == 1.toByte()) {
            stripped
        } else if (stripped.size >= 3 &&
            stripped[0] == 0.toByte() && stripped[1] == 0.toByte() &&
            stripped[2] == 1.toByte()) {
            val nal = ByteArray(1 + stripped.size)
            nal[0] = 0
            System.arraycopy(stripped, 0, nal, 1, stripped.size)
            nal
        } else {
            val nal = ByteArray(4 + stripped.size)
            nal[0] = 0; nal[1] = 0; nal[2] = 0; nal[3] = 1
            System.arraycopy(stripped, 0, nal, 4, stripped.size)
            nal
        }

        emittedFrames++
        onFrameComplete(pureNal)
    }

    fun reset() {
        buffer.reset()
        currentFrameNo = -1
        lastFragmentPos = -1
        isCorrupt = false
    }

    companion object {
        fun stripDjiMarker(data: ByteArray): ByteArray {
            if (data.size >= 16 &&
                data[0] == 0.toByte() && data[1] == 0.toByte() &&
                data[2] == 1.toByte() && data[3] == 0xFF.toByte()) {
                return data.copyOfRange(16, data.size)
            }
            return data
        }
    }
}
