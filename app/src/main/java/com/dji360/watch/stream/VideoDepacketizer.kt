package com.dji360.watch.stream

import java.io.ByteArrayOutputStream

/**
 * High-performance, robust reassembler for DJI live-view video streams.
 * Accurately accumulates fragments for each frame according to DJI's declared byte length,
 * then strips the 16-byte private transport header and emits complete Annex-B Access Units
 * at the genuine camera frame rate (30 fps).
 */
class VideoDepacketizer(
    private val onFrameComplete: (ByteArray) -> Unit
) {
    private val buffer = ByteArrayOutputStream(256 * 1024)
    private var expectedSize: Int = 0

    var emittedFrames: Long = 0L
        private set

    fun feed(packet: ByteArray) {
        if (packet.size <= 20 || packet[6] != 0x02.toByte()) return

        val bodyOffset = 20
        val bodyLen = packet.size - bodyOffset
        if (bodyLen <= 0) return

        // DJI frame header marker: 00 00 01 FF
        val isFrameStart = bodyLen >= 8 &&
                packet[bodyOffset] == 0.toByte() &&
                packet[bodyOffset + 1] == 0.toByte() &&
                packet[bodyOffset + 2] == 1.toByte() &&
                packet[bodyOffset + 3] == 0xFF.toByte()

        if (isFrameStart) {
            // If we have an existing buffer that was partially accumulated, emit or reset
            if (buffer.size() > 16) {
                if (expectedSize > 0 && buffer.size() >= (expectedSize * 85) / 100) {
                    emitCurrentFrame()
                } else {
                    buffer.reset()
                }
            }

            // Extract declared payload size from little-endian u32 at bytes 4..7
            var count = 0L
            for (i in 0..3) {
                count = count or ((packet[bodyOffset + 4 + i].toLong() and 0xFF) shl (i * 8))
            }

            // Expected total size is declared payload count + 16-byte DJI header
            expectedSize = if (count in 1..2_000_000) count.toInt() + 16 else 0
            buffer.reset()
            buffer.write(packet, bodyOffset, bodyLen)
            checkComplete()
            return
        }

        // Continuation packet of the current frame
        if (expectedSize > 0) {
            buffer.write(packet, bodyOffset, bodyLen)
            checkComplete()
        }
    }

    private fun checkComplete() {
        if (expectedSize > 0 && buffer.size() >= expectedSize) {
            emitCurrentFrame()
        }
    }

    private fun emitCurrentFrame() {
        val raw = buffer.toByteArray()
        buffer.reset()
        expectedSize = 0

        if (raw.size > 16) {
            // Strip the 16-byte DJI header (00 00 01 FF + 12 metadata bytes)
            // Ensure standard 4-byte Annex-B start code (00 00 00 01) for Android MediaCodec
            val pureNal = if (raw.size >= 20 &&
                raw[16] == 0.toByte() && raw[17] == 0.toByte() &&
                (raw[18] == 1.toByte() || (raw[18] == 0.toByte() && raw[19] == 1.toByte()))) {
                raw.copyOfRange(16, raw.size)
            } else {
                val nal = ByteArray(4 + (raw.size - 16))
                nal[0] = 0
                nal[1] = 0
                nal[2] = 0
                nal[3] = 1
                System.arraycopy(raw, 16, nal, 4, raw.size - 16)
                nal
            }
            emittedFrames++
            onFrameComplete(pureNal)
        }
    }

    fun reset() {
        buffer.reset()
        expectedSize = 0
    }
}
