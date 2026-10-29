package com.dji360.watch.stream

import java.io.ByteArrayOutputStream

/**
 * Robust reassembler for DJI live-view video stream.
 * Emits complete Annex-B NAL units directly to MediaCodec.
 */
class VideoDepacketizer(
    private val onFrameComplete: (ByteArray) -> Unit
) {
    private val buffer = ByteArrayOutputStream(128 * 1024)
    private var currentFrameNo: Int = -1
    private var lastFragmentPos: Int = -1

    var emittedFrames: Long = 0L
        private set
    var droppedFragments: Long = 0L
        private set

    fun feed(packet: ByteArray) {
        if (packet.size <= 20 || packet[6] != 0x02.toByte()) return

        val bodyOffset = 20
        val bodyLen = packet.size - bodyOffset
        val frameNo = packet[16].toInt() and 0xFF
        val pos = (packet[18].toInt() and 0xFF) * 2 + ((packet[17].toInt() and 0xFF) ushr 7)

        val startsWithMarker = bodyLen >= 4 &&
                packet[bodyOffset] == 0.toByte() &&
                packet[bodyOffset + 1] == 0.toByte() &&
                packet[bodyOffset + 2] == 1.toByte() &&
                packet[bodyOffset + 3] == 0xFF.toByte()

        if (startsWithMarker || (currentFrameNo != -1 && currentFrameNo != frameNo)) {
            finishCurrentFrame()
            currentFrameNo = frameNo
            lastFragmentPos = -1
        }

        if (lastFragmentPos != -1 && pos != lastFragmentPos + 1 && pos != lastFragmentPos) {
            droppedFragments++
        }
        lastFragmentPos = pos

        buffer.write(packet, bodyOffset, bodyLen)
    }

    private fun finishCurrentFrame() {
        if (buffer.size() == 0) return
        val raw = buffer.toByteArray()
        buffer.reset()

        val stripped = stripDjiMarker(raw)
        if (stripped.isNotEmpty()) {
            emittedFrames++
            onFrameComplete(stripped)
        }
    }

    fun reset() {
        buffer.reset()
        currentFrameNo = -1
        lastFragmentPos = -1
    }

    companion object {
        fun stripDjiMarker(data: ByteArray): ByteArray {
            var i = 0
            while (i + 4 <= data.size) {
                if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                    val nalByte = data[i + 3].toInt() and 0xFF
                    if (nalByte != 0xFF) {
                        return if (i == 0) data else data.copyOfRange(i, data.size)
                    }
                    i += 3
                } else {
                    i++
                }
            }
            return data
        }
    }
}
