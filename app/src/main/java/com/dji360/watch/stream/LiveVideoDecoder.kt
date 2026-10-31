package com.dji360.watch.stream

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.nio.ByteBuffer

/**
 * Robust, high-performance hardware video decoder using Android's [MediaCodec].
 * Synchronous input queuing with bounded retry to prevent dropped reference frames.
 * Dedicated output thread rendering at 15 FPS (decimating every 2nd frame to lighten GPU/compositor load).
 */
class LiveVideoDecoder(
    private val surface: Surface,
    private val log: (String) -> Unit
) {
    private var codec: MediaCodec? = null
    @Volatile private var isRunning = false
    private var isConfigured = false
    private var outputThread: Thread? = null

    var detectedMime: String = "video/avc"
        private set
    var firstNalHeaderHex: String = "??"
        private set
    val codecMime: String get() = if (detectedMime.contains("hevc")) "hevc" else if (detectedMime.contains("avc")) "avc" else detectedMime

    var frameCount = 0L
        private set
    val renderedFrames: Long get() = frameCount

    var hasKeyframe = false
        private set

    var lastError: String? = null
        private set

    // Display decimation: decode all frames for reference integrity, but render every 2nd frame (15 FPS)
    var decimateOutput: Boolean = false

    fun feedNalUnit(nal: ByteArray) {
        if (!isRunning) return

        if (!hasKeyframe) {
            val keyInfo = findKeyframeOrConfig(nal)
            if (keyInfo != null) {
                hasKeyframe = true
                val (mime, hex) = keyInfo
                firstNalHeaderHex = hex
                detectedMime = mime
                log("Decoder: Found KEYFRAME (0x$hex, $mime)! Initializing codec...")
                initCodec(mime)
            } else {
                // Drop leftover P-frames before first keyframe to prevent green artifacts
                return
            }
        }

        val c = codec ?: return
        try {
            var inIndex = c.dequeueInputBuffer(5_000)
            var retry = 0
            while (inIndex < 0 && retry < 5 && isRunning) {
                inIndex = c.dequeueInputBuffer(5_000)
                retry++
            }
            if (inIndex >= 0) {
                val inBuf = c.getInputBuffer(inIndex)
                if (inBuf != null) {
                    inBuf.clear()
                    inBuf.put(nal)
                    val pts = System.nanoTime() / 1000
                    c.queueInputBuffer(inIndex, 0, nal.size, pts, 0)
                    if (lastError == "IN_FULL") lastError = null
                }
            } else {
                lastError = "IN_FULL"
            }
        } catch (e: Exception) {
            val tag = when (e) {
                is MediaCodec.CodecException -> "C:${e.diagnosticInfo.takeLast(4)}"
                is IllegalStateException -> "ISE"
                else -> e.javaClass.simpleName.take(4)
            }
            lastError = "F:$tag"
            log("Decoder: feed exception ($tag): ${e.message}")
        }
    }

    private fun findKeyframeOrConfig(nal: ByteArray): Pair<String, String>? {
        var i = 0
        var foundHex = "??"
        var foundMime: String? = null

        while (i + 3 <= nal.size) {
            if (nal[i] == 0.toByte() && nal[i + 1] == 0.toByte()) {
                val headerOffset = if (nal[i + 2] == 1.toByte()) {
                    i + 3
                } else if (i + 4 <= nal.size && nal[i + 2] == 0.toByte() && nal[i + 3] == 1.toByte()) {
                    i + 4
                } else {
                    -1
                }

                if (headerOffset != -1 && headerOffset < nal.size) {
                    val b = nal[headerOffset].toInt() and 0xFF
                    if (b != 0xFF) {
                        val avcType = b and 0x1F
                        val hevcType = (b ushr 1) and 0x3F

                        // Check for AVC parameter sets or IDR
                        if (avcType == 7 || avcType == 8 || avcType == 5) {
                            return Pair("video/avc", "%02X".format(b))
                        }
                        // Check for HEVC parameter sets or IRAP
                        if (hevcType in 32..34 || hevcType in 19..21) {
                            return Pair("video/hevc", "%02X".format(b))
                        }

                        if (foundMime == null) {
                            foundHex = "%02X".format(b)
                            foundMime = if (avcType in 1..8) "video/avc" else "video/hevc"
                        }
                    }
                    i = headerOffset
                    continue
                }
            }
            i++
        }
        return null
    }

    private fun startOutputLoop(c: MediaCodec) {
        outputThread?.interrupt()
        outputThread = Thread({
            val info = MediaCodec.BufferInfo()
            var outFrameIdx = 0L
            while (isRunning && codec === c) {
                try {
                    val outIndex = c.dequeueOutputBuffer(info, 15_000)
                    if (outIndex >= 0) {
                        outFrameIdx++
                        // Render every 2nd frame if decimation is enabled, else render all
                        val shouldRender = !decimateOutput || (outFrameIdx % 2L == 0L)
                        c.releaseOutputBuffer(outIndex, shouldRender)
                        frameCount++
                        if (frameCount % 60 == 1L) {
                            log("Decoder: Rendered frame #$frameCount to surface (render=$shouldRender)")
                        }
                    } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        log("Decoder: Output format: ${c.outputFormat}")
                    }
                } catch (e: Exception) {
                    val tag = when (e) {
                        is MediaCodec.CodecException -> "C:${e.diagnosticInfo.takeLast(4)}"
                        is IllegalStateException -> "ISE"
                        is IllegalArgumentException -> "IAE"
                        else -> e.javaClass.simpleName.take(4)
                    }
                    lastError = "O:$tag"
                    log("Decoder: out exception ($tag): ${e.message}")
                    if (!isRunning) break
                }
            }
        }, "dji.codec.out").also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun initCodec(mime: String) {
        try {
            log("Decoder: Initializing $mime hardware decoder...")
            val c = MediaCodec.createDecoderByType(mime)
            val format = MediaFormat.createVideoFormat(mime, 1280, 720).apply {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 512 * 1024)
            }
            c.configure(format, surface, null, 0)
            c.start()
            codec = c
            isConfigured = true
            startOutputLoop(c)
            log("Decoder: $mime decoder configured and output thread started")
        } catch (e: Exception) {
            lastError = e.message
            log("Decoder: Failed to initialize $mime: ${e.message}")
        }
    }

    fun start() {
        isRunning = true
        frameCount = 0L
        hasKeyframe = false
    }

    fun stop() {
        isRunning = false
        isConfigured = false
        hasKeyframe = false
        val out = outputThread
        outputThread = null
        out?.interrupt()
        try {
            codec?.stop()
            codec?.release()
        } catch (e: Exception) {}
        codec = null
        log("Decoder: Stopped and released")
    }
}
