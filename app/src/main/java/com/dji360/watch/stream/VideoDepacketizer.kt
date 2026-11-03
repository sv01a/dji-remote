package com.dji360.watch.stream

import java.io.ByteArrayOutputStream

/**
 * Robust, high-performance reassembler for DJI live-view video streams.
 * Accurately tracks DJI frame markers and frame sequence counters, reassembles multi-packet frames,
 * strips DJI transport headers safely, and emits clean Annex-B NAL units directly to MediaCodec.
 */
class VideoDepacketizer(
    private val onFrameComplete: (ByteArray) -> Unit
) {
    private val buffer = ByteArrayOutputStream(256 * 1024)
    private var currentFrameNo: Int = -1

    var emittedFrames: Long = 0L
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
        }

        if (buffer.size() < 512 * 1024) {
            buffer.write(packet, bodyOffset, bodyLen)
        }
    }

    private fun emitCurrentFrame() {
        if (buffer.size() == 0) return
        val raw = buffer.toByteArray()
        buffer.reset()

        val offset = firstRealNalOffset(raw)
        val clean = if (offset > 0) raw.copyOfRange(offset, raw.size) else raw
        if (clean.isEmpty()) return

        // Ensure standard 4-byte Annex-B start code (00 00 00 01) for Android MediaCodec
        val pureNal = if (clean.size >= 4 &&
            clean[0] == 0.toByte() && clean[1] == 0.toByte() &&
            clean[2] == 0.toByte() && clean[3] == 1.toByte()) {
            clean
        } else if (clean.size >= 3 &&
            clean[0] == 0.toByte() && clean[1] == 0.toByte() &&
            clean[2] == 1.toByte()) {
            val nal = ByteArray(1 + clean.size)
            nal[0] = 0
            System.arraycopy(clean, 0, nal, 1, clean.size)
            nal
        } else {
            val nal = ByteArray(4 + clean.size)
            nal[0] = 0; nal[1] = 0; nal[2] = 0; nal[3] = 1
            System.arraycopy(clean, 0, nal, 4, clean.size)
            nal
        }

        emittedFrames++
        onFrameComplete(pureNal)
    }

    fun reset() {
        buffer.reset()
        currentFrameNo = -1
    }

    companion object {
        /**
         * Scans for the first real video NAL unit start code, starting safely AFTER the 16-byte
         * DJI private 0xFF transport header to avoid false matches inside metadata fields.
         */
        fun firstRealNalOffset(data: ByteArray): Int {
            if (data.size < 16) return 0
            if (data[0] == 0.toByte() && data[1] == 0.toByte() &&
                data[2] == 1.toByte() && data[3] == 0xFF.toByte()) {
                var i = 16
                val limit = minOf(36, data.size - 3)
                while (i < limit) {
                    if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                        if (data[i + 2] == 1.toByte() && data[i + 3] != 0xFF.toByte()) {
                            return i
                        } else if (i + 4 < data.size && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte() && data[i + 4] != 0xFF.toByte()) {
                            return i
                        }
                    }
                    i++
                }
                return 16
            }
            return 0
        }
    }
}
