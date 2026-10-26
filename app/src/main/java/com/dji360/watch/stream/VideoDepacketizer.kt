package com.dji360.watch.stream

import java.io.ByteArrayOutputStream

/**
 * Reassembles DJI live-view video packets into complete H.264 (AVC) or H.265 (HEVC) Access Units.
 *
 * Wire layout of video datagram:
 *   Bytes 0..7:   Transport header (pktType = 0x02 at byte 6, seq at bytes 4..5 LE)
 *   Bytes 8..19:  Routing / metadata header
 *   Bytes 20..end: Payload data
 *
 * Each video frame begins with a DJI marker (0x00 0x00 0x01 0xFF) followed by a 16-byte header
 * containing the declared payload length (little-endian u32 at bytes 4..7).
 * We strip the DJI private marker and emit pure Annex B NAL units (starting with 0x00 0x00 0x00 0x01
 * or 0x00 0x00 0x01) directly to the video decoder.
 */
class VideoDepacketizer(
    private val onFrameComplete: (ByteArray) -> Unit
) {
    private var buffer = ByteArrayOutputStream(64 * 1024)
    private var currentFrameNo: Int = -1
    private var lastFragmentPos: Int = -1
    var droppedFragments = 0
        private set

    var droppedFrames: Int = 0
        private set

    fun feed(packet: ByteArray) {
        // Video packets have pktType == 0x02 at byte 6 and size > 20
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
            onFrameComplete(stripped)
        }
    }

    fun reset() {
        buffer.reset()
        currentFrameNo = -1
        lastFragmentPos = -1
        
    }

    companion object {
        /**
         * Strip DJI's private per-frame marker (0x00 0x00 0x01 0xFF + header) and return pure NAL units.
         */
        fun stripDjiMarker(data: ByteArray): ByteArray {
            var i = 0
            while (i + 4 <= data.size) {
                if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                    val nalByte = data[i + 3].toInt() and 0xFF
                    // DJI private marker is 0xFF (nal_type 63 in HEVC or 31 in AVC)
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
