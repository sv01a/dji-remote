package com.dji360.watch.stream

import java.io.ByteArrayOutputStream

/**
 * Robust, high-performance reassembler for DJI live-view video streams.
 * Accurately tracks DJI frame markers and frame sequence counters, reassembles multi-packet frames,
 * strips DJI transport headers safely, and emits clean Annex-B NAL units directly to MediaCodec.
 *
 * Frame boundaries are recognised two ways:
 *  1. DJI frame marker `00 00 01 FF` + declared little-endian payload size at bytes 4..7 of the
 *     frame header → the frame is emitted the moment the declared byte count is complete.
 *  2. marker / frameNo / pos boundaries — which also becomes the permanent mode as soon as the
 *     declared size proves to describe something else ([recordDeclaredMismatch]).
 */
class VideoDepacketizer(
    private val onFrameComplete: (ByteArray) -> Unit,
    private val log: (String) -> Unit = {}
) {
    private val buffer = ByteArrayOutputStream(256 * 1024)
    private var currentFrameNo: Int = -1
    private var expectedSize: Int = 0

    // `00 00 01 FF` + u32 LE payload size is a hypothesis about the DJI frame header layout — it is
    // not present in the official demo sources, so it is verified against the live stream in both
    // directions: too small (the packet following a declared-size emit continues the same frame) and
    // too big (a frame reaches its marker boundary before the declared byte count is complete).
    // After two disagreements the hypothesis is dropped for the rest of the session, so a stream
    // that uses those bytes as a timestamp never spams the log once per frame.
    private var trustDeclaredSize = true
    private var awaitingBoundaryConfirmation = false
    private var declaredMismatches = 0

    var emittedFrames: Long = 0L
        private set
    var truncatedFrames: Long = 0L
        private set
    var overflowFrames: Long = 0L
        private set
    val usingDeclaredFrameSize: Boolean get() = trustDeclaredSize

    @Volatile private var loggedFirstPacket = false

    fun feed(packet: ByteArray) {
        if (packet.size <= 20 || packet[6] != 0x02.toByte()) return

        if (!loggedFirstPacket) {
            loggedFirstPacket = true
            log("Depacketizer: first video packet ${packet.size}B: ${hexOf(packet.take(56))}")
        }

        val bodyOffset = 20
        val bodyLen = packet.size - bodyOffset
        if (bodyLen <= 0) return

        val frameNo = packet[16].toInt() and 0xFF
        val pos = (packet[18].toInt() and 0xFF) * 2 + ((packet[17].toInt() and 0xFF) ushr 7)

        val hasMarker = bodyLen >= 4 &&
                packet[bodyOffset] == 0.toByte() &&
                packet[bodyOffset + 1] == 0.toByte() &&
                packet[bodyOffset + 2] == 1.toByte() &&
                packet[bodyOffset + 3] == 0xFF.toByte()

        val isNewFrame = (pos == 0) || hasMarker || (currentFrameNo != -1 && currentFrameNo != frameNo)

        if (awaitingBoundaryConfirmation) {
            awaitingBoundaryConfirmation = false
            if (!isNewFrame) {
                recordDeclaredMismatch("next packet continued the same frame")
            }
        }

        if (isNewFrame) {
            // Whatever we accumulated belongs to the previous frame — flush it now instead of
            // waiting for the next frame's first packet, otherwise a missed marker stalls the
            // pipeline forever (buffer hits the cap and silently stops accepting data).
            if (buffer.size() > 0) {
                if (expectedSize > 0 && buffer.size() < expectedSize) {
                    truncatedFrames++
                    // The frame finished *before* its declared size, so bytes 4..7 are bigger than
                    // the frame (timestamp/PTS-like). Without counting this as a mismatch the
                    // fallback would never trigger and every single frame would log.
                    recordDeclaredMismatch("frame $currentFrameNo ended at ${buffer.size()}/$expectedSize B")
                }
                emitCurrentFrame()
            }
            currentFrameNo = frameNo
            expectedSize = if (trustDeclaredSize && hasMarker && bodyLen >= 8)
                declaredFrameSize(packet, bodyOffset) else 0
        }

        if (buffer.size() + bodyLen > MAX_FRAME_BYTES) {
            overflowFrames++
            log("Depacketizer: frame > ${MAX_FRAME_BYTES}B, forced emit (frames=$emittedFrames)")
            emitCurrentFrame()
            expectedSize = 0
        }

        buffer.write(packet, bodyOffset, bodyLen)

        // Declared size reached → emit immediately (kills one frame of latency and
        // guarantees forward progress even when a boundary marker is missed).
        if (expectedSize > 0 && buffer.size() >= expectedSize) {
            emitCurrentFrame()
            awaitingBoundaryConfirmation = true
        }
    }

    /** DJI frame header: `00 00 01 FF` + u32 LE payload count; total = count + 16. */
    private fun declaredFrameSize(packet: ByteArray, bodyOffset: Int): Int {
        var count = 0L
        for (i in 0..3) {
            count = count or ((packet[bodyOffset + 4 + i].toLong() and 0xFF) shl (i * 8))
        }
        return if (count in 1..MAX_FRAME_BYTES) (count + 16).toInt() else 0
    }

    /**
     * The declared-size hypothesis is dropped after two frames disagree with it — from then on
     * only marker / pos / frameNo boundaries are used, and `expectedSize` stays 0 so the stream
     * cannot spam the log once per frame.
     */
    private fun recordDeclaredMismatch(reason: String) {
        declaredMismatches++
        if (declaredMismatches >= 2) {
            if (trustDeclaredSize) {
                trustDeclaredSize = false
                log("Depacketizer: declared size does not describe this stream ($reason) — switching to marker/frameNo mode")
            }
        } else {
            log("Depacketizer: declared size mismatch #$declaredMismatches: $reason")
        }
    }

    private fun emitCurrentFrame() {
        if (buffer.size() == 0) return
        val raw = buffer.toByteArray()
        buffer.reset()
        expectedSize = 0

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

        if (emittedFrames == 0L) {
            log("Depacketizer: first NAL (${pureNal.size}B, stripped $offset): ${hexOf(pureNal.take(24))}")
        }
        emittedFrames++
        onFrameComplete(pureNal)
    }

    fun reset() {
        buffer.reset()
        currentFrameNo = -1
        expectedSize = 0
        loggedFirstPacket = false
        trustDeclaredSize = true
        awaitingBoundaryConfirmation = false
        declaredMismatches = 0
    }

    companion object {
        private const val MAX_FRAME_BYTES = 1_500_000

        private fun hexOf(bytes: List<Byte>) = bytes.joinToString(" ") { "%02X".format(it) }

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
