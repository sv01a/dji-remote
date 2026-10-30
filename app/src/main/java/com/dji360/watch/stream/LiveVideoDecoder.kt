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

    var detectedMime: String = "video/hevc"
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
            detectedMime = "video/hevc"
            initCodec("video/hevc")
        }

        val c = codec ?: return
        try {
            val inIndex = c.dequeueInputBuffer(5_000)
            if (inIndex >= 0) {
                val inBuf: ByteBuffer? = c.getInputBuffer(inIndex)
                if (inBuf != null) {
                    inBuf.clear()
                    inBuf.put(nal)
                    val pts = System.nanoTime() / 1000
                    c.queueInputBuffer(inIndex, 0, nal.size, pts, 0)
                }
            }
        } catch (e: Exception) {
            log("Decoder: feed exception: ${e.message}")
        }
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
                    if (!isRunning) break
                }
            }
        }, "dji.codec.out").also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun initCodec(mime: String = "video/hevc") {
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
