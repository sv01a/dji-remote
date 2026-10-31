package com.dji360.watch.stream

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.nio.ByteBuffer

/**
 * High-performance hardware video decoder using Android's [MediaCodec] with an
 * asynchronous dedicated output thread (matching OpenCine architecture) to guarantee
 * zero-blocking UDP ingestion and artifact-free rendering.
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

    var lastError: String? = null
        private set

    fun feedNalUnit(nal: ByteArray) {
        if (!isRunning) return

        if (!isConfigured) {
            val (mime, hex) = detectMime(nal)
            firstNalHeaderHex = hex
            val chosenMime = mime ?: "video/avc"
            detectedMime = chosenMime
            initCodec(chosenMime)
        }

        val c = codec ?: return
        try {
            var inIndex = c.dequeueInputBuffer(10_000)
            if (inIndex < 0) {
                inIndex = c.dequeueInputBuffer(10_000)
            }
            if (inIndex >= 0) {
                val inBuf: ByteBuffer? = c.getInputBuffer(inIndex)
                if (inBuf != null) {
                    inBuf.clear()
                    inBuf.put(nal)
                    val pts = System.nanoTime() / 1000
                    c.queueInputBuffer(inIndex, 0, nal.size, pts, 0)
                }
            } else {
                lastError = "IN_FULL"
            }
        } catch (e: Exception) {
            lastError = "F:" + (e.message?.take(6) ?: "ERR")
            log("Decoder: feed exception: ${e.message}")
        }
    }

    private fun detectMime(nal: ByteArray): Pair<String?, String> {
        var i = 0
        while (i + 4 <= nal.size) {
            if (nal[i] == 0.toByte() && nal[i + 1] == 0.toByte() &&
                (nal[i + 2] == 1.toByte() || (i + 5 <= nal.size && nal[i + 2] == 0.toByte() && nal[i + 3] == 1.toByte()))) {
                val headerOffset = if (nal[i + 2] == 1.toByte()) i + 3 else i + 4
                val b = nal[headerOffset].toInt() and 0xFF
                val hex = "%02X".format(b)
                if (b != 0xFF) {
                    val hevcType = (b ushr 1) and 0x3F
                    val avcType = b and 0x1F
                    if (hevcType in 32..35) return Pair("video/hevc", hex)
                    if (avcType in 7..8) return Pair("video/avc", hex)
                    if (hevcType in 19..21) return Pair("video/hevc", hex)
                    if (avcType in 1..5) return Pair("video/avc", hex)
                    return Pair(if (avcType in 1..8) "video/avc" else "video/hevc", hex)
                }
                i += 3
            } else {
                i++
            }
        }
        return Pair("video/avc", "??")
    }

    private fun startOutputLoop(c: MediaCodec) {
        outputThread?.interrupt()
        outputThread = Thread({
            val info = MediaCodec.BufferInfo()
            while (isRunning && codec === c) {
                try {
                    val outIndex = c.dequeueOutputBuffer(info, 10_000)
                    if (outIndex >= 0) {
                        frameCount++
                        if (frameCount % 60 == 1L) {
                            log("Decoder: Rendered frame #$frameCount to surface")
                        }
                        c.releaseOutputBuffer(outIndex, true)
                    } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        log("Decoder: Output format changed: ${c.outputFormat}")
                    }
                } catch (e: Exception) {
                    lastError = "O:" + (e.message?.take(6) ?: "ERR")
                    log("Decoder: out exception: ${e.message}")
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
                setInteger(MediaFormat.KEY_PRIORITY, 0)
            }
            try {
                c.configure(format, surface, null, 0)
            } catch (e: Exception) {
                log("Decoder: Configure exception, retrying: ${e.message}")
                c.configure(format, surface, null, 0)
            }
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
    }

    fun stop() {
        isRunning = false
        isConfigured = false
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
