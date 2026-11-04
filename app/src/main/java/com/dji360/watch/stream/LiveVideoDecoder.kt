package com.dji360.watch.stream

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.SurfaceHolder

/**
 * Robust, high-performance hardware video decoder using Android's [MediaCodec].
 * Synchronous input queuing with bounded retry to prevent dropped reference frames.
 * Dedicated output thread rendering at 15 FPS (decimating every 2nd frame to lighten GPU/compositor load).
 *
 * All codec teardown/re-init happens on the feeding thread ([feedNalUnit]) under [codecLock], so a
 * codec error never releases a codec that another thread is still queueing into. A broken codec is
 * rebuilt automatically when the next keyframe access unit arrives.
 */
class LiveVideoDecoder(
    private val holder: SurfaceHolder,
    private val log: (String) -> Unit
) {
    @Volatile private var codec: MediaCodec? = null
    @Volatile private var isRunning = false
    @Volatile private var codecBroken = false
    private val codecLock = Any()
    private var outputThread: Thread? = null

    @Volatile var detectedMime: String = "video/avc"
        private set
    @Volatile var firstNalHeaderHex: String = "??"
        private set
    val codecMime: String get() = if (detectedMime.contains("hevc")) "hevc" else if (detectedMime.contains("avc")) "avc" else detectedMime

    @Volatile var frameCount = 0L
        private set
    val renderedFrames: Long get() = frameCount

    @Volatile var hasKeyframe = false
        private set

    @Volatile var lastError: String? = null
        private set

    @Volatile private var nalsFed = 0L

    // Display decimation: decode all frames for reference integrity, but render every 2nd frame (15 FPS)
    var decimateOutput: Boolean = true

    fun isSurfaceValid(): Boolean = holder.surface?.isValid == true

    fun feedNalUnit(nal: ByteArray) {
        if (!isRunning) return

        if (!hasKeyframe) {
            val keyInfo = findKeyframeOrConfig(nal)
            if (keyInfo != null) {
                val (mime, hex) = keyInfo
                firstNalHeaderHex = hex
                detectedMime = mime
                log("Decoder: Found KEYFRAME (0x$hex, $mime)! Initializing codec...")
                initCodec(mime)
                if (!hasKeyframe) return   // init failed — wait for the next key AU
            } else {
                // Drop leftover P-frames before first keyframe to prevent green artifacts
                return
            }
        }

        synchronized(codecLock) {
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
                        nalsFed++
                        if (lastError == "IN_FULL") lastError = null
                    }
                } else {
                    lastError = "IN_FULL"
                }
            } catch (e: Exception) {
                val exMsg = e.message ?: e.javaClass.simpleName
                lastError = "F:" + exMsg.take(6)
                log("Decoder: feed exception: $exMsg — codec will be rebuilt on next keyframe")
                releaseCodecLocked()
            }
        }
    }

    private fun findKeyframeOrConfig(nal: ByteArray): Pair<String, String>? {
        var i = 0
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
            var outErrors = 0
            while (isRunning && !codecBroken && codec === c) {
                try {
                    val outIndex = c.dequeueOutputBuffer(info, 15_000)
                    if (outIndex >= 0) {
                        outFrameIdx++
                        // Render every 2nd frame if decimation is enabled, else render all
                        val shouldRender = (outFrameIdx <= 2L) || !decimateOutput || (outFrameIdx % 2L == 0L)
                        c.releaseOutputBuffer(outIndex, shouldRender)
                        frameCount++
                        if (frameCount % 60 == 1L) {
                            log("Decoder: Rendered frame #$frameCount to surface (render=$shouldRender)")
                        }
                    } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        log("Decoder: Output format: ${c.outputFormat}")
                    }
                } catch (e: Exception) {
                    // Teardown race, not a decoder fault: stop()/re-init either interrupted this
                    // thread or already replaced the codec underneath it (releaseCodecLocked sets
                    // codec = null before stop()/release(), so a parked dequeue throws
                    // IllegalStateException). Reporting it would show a bogus O:... on the HUD and
                    // mark a perfectly healthy codec as broken.
                    if (!isRunning || codec !== c || e is InterruptedException) break
                    val exMsg = e.message ?: e.javaClass.simpleName
                    val recoverable = e is MediaCodec.CodecException && e.isRecoverable
                    val tag = when (e) {
                        is MediaCodec.CodecException -> "C:${e.diagnosticInfo.takeLast(4)}"
                        else -> exMsg.take(8)
                    }
                    outErrors++
                    lastError = "O:$tag"
                    log("Decoder: out exception ($tag, #$outErrors): $exMsg")
                    if (!recoverable || outErrors >= 8) {
                        // A dead codec never recovers by itself: tear it down so the next
                        // keyframe access unit builds a fresh one.
                        codecBroken = true
                        hasKeyframe = false
                        log("Decoder: codec unrecoverable (fed=$nalsFed out=$frameCount) — re-init on next keyframe")
                        break
                    }
                }
            }
        }, "dji.codec.out").also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun initCodec(mime: String) {
        synchronized(codecLock) {
            if (codec != null) {
                if (!codecBroken) return
                log("Decoder: rebuilding unrecoverable codec...")
                releaseCodecLocked()
            }
            try {
                val s = holder.surface
                if (s == null || !s.isValid) {
                    lastError = "NO_SURF"
                    log("Decoder: Surface is not valid! Cannot configure $mime")
                    return
                }
                log("Decoder: Initializing $mime hardware decoder...")
                val c = MediaCodec.createDecoderByType(mime)
                val format = MediaFormat.createVideoFormat(mime, 1920, 1080).apply {
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 1024)
                }
                c.configure(format, s, null, 0)
                c.start()
                codecBroken = false
                codec = c
                hasKeyframe = true
                startOutputLoop(c)
                log("Decoder: $mime decoder configured and output thread started")
            } catch (e: Exception) {
                val exMsg = e.message ?: e.javaClass.simpleName
                lastError = "INIT:" + exMsg.take(6)
                hasKeyframe = false
                log("Decoder: Failed to initialize $mime: $exMsg")
            }
        }
    }

    /** Caller must already hold [codecLock]. */
    private fun releaseCodecLocked() {
        // Tear the output thread down first: it may be parked inside dequeueOutputBuffer(15 ms),
        // and it exits quietly once `codec` is null, so it must not be interrupted by stop()/
        // release() pulling the codec out from under a native dequeue.
        val out = outputThread
        if (out != null && out !== Thread.currentThread()) {
            outputThread = null
            out.interrupt()
            try {
                out.join(60)
            } catch (_: InterruptedException) {
            }
        }

        val c = codec ?: return
        codec = null
        hasKeyframe = false
        codecBroken = false

        try {
            c.stop()
        } catch (e: Exception) {}
        try {
            c.release()
        } catch (e: Exception) {}
    }

    fun start() {
        isRunning = true
        frameCount = 0L
        nalsFed = 0L
        hasKeyframe = false
        codecBroken = false
    }

    fun stop() {
        isRunning = false
        hasKeyframe = false
        codecBroken = false
        // releaseCodecLocked interrupts the output thread and waits briefly for it to leave
        // dequeueOutputBuffer before the codec is released.
        synchronized(codecLock) { releaseCodecLocked() }
        log("Decoder: Stopped and released")
    }
}
