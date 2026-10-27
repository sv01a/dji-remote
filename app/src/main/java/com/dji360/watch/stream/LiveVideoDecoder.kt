package com.dji360.watch.stream

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.nio.ByteBuffer

/**
 * Hardware-accelerated video decoder using Android's [MediaCodec], outputting
 * live frames directly to a provided [Surface] (from SurfaceView).
 * Supports automatic detection of AVC/H.264 vs HEVC/H.265.
 */
class LiveVideoDecoder(
    private val surface: Surface,
    private val log: (String) -> Unit
) {
    private var codec: MediaCodec? = null
    @Volatile private var isRunning = false
    private var isConfigured = false
    var detectedMime: String = "init"
        private set
    var frameCount = 0L
        private set
    val renderedFrames: Long get() = frameCount
    val codecMime: String get() = detectedMime
    private var cachedHeader: ByteArray? = null

    fun feedNalUnit(nal: ByteArray) {
        if (!isRunning) return

        if (!isConfigured) {
            val mime = detectMime(nal) ?: "video/hevc"
            detectedMime = mime
            initCodec(mime)
        }

        val c = codec ?: return
        try {
            val inIndex = c.dequeueInputBuffer(10_000)
            if (inIndex >= 0) {
                val inBuf: ByteBuffer? = c.getInputBuffer(inIndex)
                if (inBuf != null) {
                    inBuf.clear()
                    inBuf.put(nal)
                    val pts = System.nanoTime() / 1000
                    c.queueInputBuffer(inIndex, 0, nal.size, pts, 0)
                }
            }

            drainOutput(c)
        } catch (e: Exception) {
            log("Decoder: feed exception: ${e.message}")
        }
    }

    private fun drainOutput(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var outIndex = c.dequeueOutputBuffer(info, 0)
        while (outIndex >= 0) {
            frameCount++
            if (frameCount % 60 == 1L) {
                log("Decoder: Rendered frame #$frameCount to surface")
            }
            // render = true renders frame immediately to the bound Surface
            c.releaseOutputBuffer(outIndex, true)
            outIndex = c.dequeueOutputBuffer(info, 0)
        }
    }

    private fun initCodec(mime: String) {
        try {
            log("Decoder: Initializing $mime hardware decoder...")
            val c = MediaCodec.createDecoderByType(mime)
            val format = MediaFormat.createVideoFormat(mime, 1280, 720).apply {
                // Low latency decoding hint
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            try {
                c.configure(format, surface, null, 0)
            } catch (e: Exception) {
                // Some legacy decoders reject KEY_LOW_LATENCY; retry without it
                log("Decoder: Low latency configure failed, retrying without hint: ${e.message}")
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 0)
                c.configure(format, surface, null, 0)
            }
            c.start()
            codec = c
            isConfigured = true
            log("Decoder: $mime decoder configured and started successfully")
        } catch (e: Exception) {
            log("Decoder: Failed to initialize $mime: ${e.message}")
        }
    }

    private fun detectMime(nal: ByteArray): String? {
        var i = 0
        while (i + 4 <= nal.size) {
            if (nal[i] == 0.toByte() && nal[i + 1] == 0.toByte() && nal[i + 2] == 1.toByte()) {
                val b = nal[i + 3].toInt() and 0xFF
                val hevcType = (b ushr 1) and 0x3F
                val avcType = b and 0x1F
                // HEVC: VPS (32), SPS (33), PPS (34)
                if (hevcType in 32..34) return "video/hevc"
                // H.264: SPS (7), PPS (8)
                if (avcType in 7..8) return "video/avc"
                i += 3
            } else {
                i++
            }
        }
        return null
    }

    fun start() {
        isRunning = true
        frameCount = 0L
    }

    fun stop() {
        isRunning = false
        isConfigured = false
        try {
            codec?.stop()
            codec?.release()
        } catch (e: Exception) {}
        codec = null
        log("Decoder: Stopped and released")
    }
}
