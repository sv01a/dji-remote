package com.dji360.watch.stream

import java.io.ByteArrayOutputStream

/**
 * Reassembles DJI live-view video packets into complete Access Units, matching OpenCine HevcDepacketizer.
 */
class VideoDepacketizer(
    private val onFrameComplete: (ByteArray) -> Unit
) {
    private val maxEncodedBytes = 4 * 1024 * 1024
    private var sizedMode = false
    private var expectedSize: Int? = null
    private var sizedBuffer = ByteArrayOutputStream(128 * 1024)
    private var lastSizedSequence: Int? = null
    private var currentFrame: Int? = null
    private var buffer = ByteArrayOutputStream(128 * 1024)
    private var lastPosition: Int? = null
    private var corrupt = false

    var droppedIncomplete = 0
        private set

    fun feed(packet: ByteArray) {
        if (packet.size <= 20 || packet[6] != 0x02.toByte()) return

        val bodyOffset = 20
        val bodyLen = packet.size - bodyOffset
        val marker = bodyLen >= 4 &&
                packet[bodyOffset] == 0.toByte() &&
                packet[bodyOffset + 1] == 0.toByte() &&
                packet[bodyOffset + 2] == 1.toByte() &&
                packet[bodyOffset + 3] == 0xFF.toByte()

        val declared = declaredSize(packet, bodyOffset, bodyLen)
        val nonzeroDeclaration = marker && bodyLen >= 16 && (4..7).any { packet[bodyOffset + it] != 0.toByte() }

        if (sizedMode || nonzeroDeclaration) {
            if (!sizedMode) {
                sizedMode = true
                buffer.reset()
            }
            feedSized(packet, bodyOffset, bodyLen, marker, declared)
            return
        }

        val frameNo = packet[16].toInt() and 0xFF
        val pos = (packet[18].toInt() and 0xFF) * 2 + ((packet[17].toInt() and 0xFF) ushr 7)

        if (currentFrame != null && currentFrame != frameNo) {
            if (buffer.size() > 0) {
                if (corrupt) {
                    droppedIncomplete++
                } else {
                    emitFrame(stripDjiMarker(buffer.toByteArray()))
                }
            }
            buffer.reset()
            corrupt = false
            lastPosition = null
        }
        currentFrame = frameNo

        if (lastPosition != null) {
            val last = lastPosition!!
            if (pos == last) return
            if (pos < last) {
                if (buffer.size() > 0) {
                    if (corrupt) droppedIncomplete++
                    else emitFrame(stripDjiMarker(buffer.toByteArray()))
                }
                buffer.reset()
                corrupt = false
            } else if (pos != last + 1) {
                corrupt = true
            }
        }
        lastPosition = pos

        if (bodyLen <= maxEncodedBytes + 16 - buffer.size()) {
            buffer.write(packet, bodyOffset, bodyLen)
        } else {
            corrupt = true
        }
    }

    private fun declaredSize(packet: ByteArray, offset: Int, len: Int): Int? {
        if (len < 16) return null
        if (packet[offset] != 0.toByte() || packet[offset + 1] != 0.toByte() ||
            packet[offset + 2] != 1.toByte() || packet[offset + 3] != 0xFF.toByte()) {
            return null
        }
        var count = 0L
        for (i in 0..3) {
            count = count or ((packet[offset + 4 + i].toLong() and 0xFF) shl (i * 8))
        }
        if (count <= 0 || count > maxEncodedBytes) return null
        return count.toInt() + 16
    }

    private fun feedSized(
        packet: ByteArray,
        offset: Int,
        len: Int,
        marker: Boolean,
        declared: Int?
    ) {
        val sequence = (packet[4].toInt() and 0xFF) or ((packet[5].toInt() and 0xFF) shl 8)
        if (lastSizedSequence != null && sequence == lastSizedSequence) return

        if (marker) {
            if (expectedSize != null) droppedIncomplete++
            sizedBuffer.reset()
            expectedSize = declared
            lastSizedSequence = null
        }

        val expected = expectedSize ?: return

        if (lastSizedSequence != null) {
            val expectedNextSeq = (lastSizedSequence!! + 8) and 0xFFFF
            if (sequence != expectedNextSeq) {
                droppedIncomplete++
                expectedSize = null
                sizedBuffer.reset()
                lastSizedSequence = sequence
                return
            }
        }
        lastSizedSequence = sequence

        if (len > expected - sizedBuffer.size()) {
            droppedIncomplete++
            expectedSize = null
            sizedBuffer.reset()
            return
        }

        sizedBuffer.write(packet, offset, len)

        if (sizedBuffer.size() == expected) {
            val raw = sizedBuffer.toByteArray()
            sizedBuffer.reset()
            expectedSize = null
            if (raw.size > 16) {
                val au = raw.copyOfRange(16, raw.size)
                emitFrame(au)
            }
        }
    }

    private fun emitFrame(au: ByteArray) {
        if (au.isNotEmpty()) {
            onFrameComplete(au)
        }
    }

    fun reset() {
        sizedMode = false
        expectedSize = null
        sizedBuffer.reset()
        lastSizedSequence = null
        currentFrame = null
        buffer.reset()
        lastPosition = null
        corrupt = false
        droppedIncomplete = 0
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
