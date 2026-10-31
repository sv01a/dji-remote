package com.dji360.watch.stream

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Hardware video decoder with async queue architecture.
 * NAL units are enqueued non-blocking from the UDP thread.
 * A dedicated feeder thread drains the queue into MediaCodec.
 * A dedicated output thread renders decoded frames to Surface.
 */
class LiveVideoDecoder(
    private val surface: Surface,
    private val log: (String) -> Unit
) {
    private var codec: MediaCodec? = null
    @Volatile private var isRunning = false
    private var isConfigured = false
    private var outputThread: Thread? = null
    private var feederThread: Thread? = null
    private val nalQueue = LinkedBlockingQueue<ByteArray>(60)

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

    fun feedNalUnit(nal: ByteArray) {
        if (!isRunning) return
        if (!hasKeyframe) {
            if (isKeyframeOrConfig(nal)) {
                hasKeyframe = true
                val (mime, hex) = detectMime(nal)
                firstNalHeaderHex = hex
                detectedMime = mime ?: "video/avc"
                log("Decoder: KEYFRAME (0x$hex $detectedMime)")
                initCodec(detectedMime)
            } else {
                return
            }
        }
        if (!nalQueue.offer(nal)) {
            nalQueue.poll()
            nalQueue.offer(nal)
        }
    }

    private fun firstNalByte(nal: ByteArray): Int? {
        var i = 0
        while (i + 4 <= nal.size) {
            if (nal[i] == 0.toByte() && nal[i+1] == 0.toByte()) {
                if (nal[i+2] == 1.toByte()) {
                    val b = nal[i+3].toInt() and 0xFF
                    if (b != 0xFF) return b
                    i += 3
                } else if (nal[i+2] == 0.toByte() && i+4 < nal.size && nal[i+3] == 1.toByte()) {
                    val b = nal[i+4].toInt() and 0xFF
                    if (b != 0xFF) return b
                    i += 4
                } else i++
            } else i++
        }
        return null
    }

    private fun isKeyframeOrConfig(nal: ByteArray): Boolean {
        val b = firstNalByte(nal) ?: return false
        val avcType = b and 0x1F
        val hevcType = (b ushr 1) and 0x3F
        return avcType == 7 || avcType == 8 || avcType == 5 ||
               hevcType in 32..34 || hevcType in 19..21
    }

    private fun detectMime(nal: ByteArray): Pair<String?, String> {
        val b = firstNalByte(nal) ?: return Pair("video/avc", "??")
        val hex = "%02X".format(b)
        val hevcType = (b ushr 1) and 0x3F
        val avcType = b and 0x1F
        if (hevcType in 32..35) return Pair("video/hevc", hex)
        if (avcType in 7..8) return Pair("video/avc", hex)
        if (hevcType in 19..21) return Pair("video/hevc", hex)
        if (avcType in 1..5) return Pair("video/avc", hex)
        return Pair("video/avc", hex)
    }

    private fun startFeederLoop(c: MediaCodec) {
        feederThread?.interrupt()
        feederThread = Thread({
            while (isRunning && codec === c) {
                try {
                    val nal = nalQueue.poll(50, TimeUnit.MILLISECONDS) ?: continue
                    val inIndex = c.dequeueInputBuffer(50_000)
                    if (inIndex >= 0) {
                        val inBuf = c.getInputBuffer(inIndex)
                        if (inBuf != null) {
                            inBuf.clear()
                            inBuf.put(nal)
                            c.queueInputBuffer(inIndex, 0, nal.size, System.nanoTime() / 1000, 0)
                            if (lastError == "IN_FULL") lastError = null
                        }
                    } else {
                        lastError = "IN_FULL"
                    }
                } catch (e: InterruptedException) { break }
                catch (e: Exception) {
                    lastError = "F:" + (e.javaClass.simpleName.take(3))
                    log("Decoder: feed err: ${e.message}")
                }
            }
        }, "dji.codec.feed").also { it.isDaemon = true; it.start() }
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
                        c.releaseOutputBuffer(outIndex, true)
                    }
                } catch (e: Exception) {
                    lastError = "O:" + (e.javaClass.simpleName.take(3))
                    log("Decoder: out err: ${e.message}")
                    if (!isRunning) break
                }
            }
        }, "dji.codec.out").also { it.isDaemon = true; it.start() }
    }

    private fun initCodec(mime: String) {
        try {
            val c = MediaCodec.createDecoderByType(mime)
            val fmt = MediaFormat.createVideoFormat(mime, 1280, 720).apply {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 512 * 1024)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
            }
            c.configure(fmt, surface, null, 0)
            c.start()
            codec = c
            isConfigured = true
            nalQueue.clear()
            startFeederLoop(c)
            startOutputLoop(c)
            log("Decoder: $mime started (feeder+output)")
        } catch (e: Exception) {
            lastError = e.message
            log("Decoder: init fail: ${e.message}")
        }
    }

    fun start() {
        isRunning = true
        frameCount = 0L
        hasKeyframe = false
        nalQueue.clear()
    }

    fun stop() {
        isRunning = false
        isConfigured = false
        hasKeyframe = false
        nalQueue.clear()
        feederThread?.interrupt(); feederThread = null
        outputThread?.interrupt(); outputThread = null
        try { codec?.stop(); codec?.release() } catch (_: Exception) {}
        codec = null
    }
}
