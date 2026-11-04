package com.dji360.watch.stream

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamTests {

    @Test
    fun `transportHeader calculates xor and lengths correctly`() {
        // pktType 0x00, payloadLen 40, sessionId 0x1234, seq 0x0001
        val hdr = DatalinkClient.transportHeader(0x00, 40, 0x1234, 0x0001)
        assertEquals(8, hdr.size)
        // total = 8 + 40 = 48 (0x0030) -> w0 = 0x8030 -> lo=0x30, hi=0x80
        assertEquals(0x30.toByte(), hdr[0])
        assertEquals(0x80.toByte(), hdr[1])
        assertEquals(0x34.toByte(), hdr[2])
        assertEquals(0x12.toByte(), hdr[3])
        assertEquals(0x01.toByte(), hdr[4])
        assertEquals(0x00.toByte(), hdr[5])
        assertEquals(0x00.toByte(), hdr[6])

        // Verify trailing XOR byte: xor of first 7 bytes
        var xor = 0
        for (i in 0 until 7) xor = xor xor (hdr[i].toInt() and 0xFF)
        assertEquals(xor.toByte(), hdr[7])
    }

    @Test
    fun `buildHandshakePacket size is 48 bytes with baseSeq`() {
        val hs = DatalinkClient.buildHandshakePacket(0x1234, 0x0000, 0x0068)
        assertEquals(48, hs.size)
        assertEquals(DatalinkClient.PKT_HANDSHAKE, hs[6])
        // baseSeq at payload offset 0 (byte 8 of total packet)
        assertEquals(0x68.toByte(), hs[8])
        assertEquals(0x00.toByte(), hs[9])
    }

    @Test
    fun `buildAckPacket size is 34 bytes with videoCursor`() {
        // Signature: (sessionId, videoCursor, ackedSeq, extraSeq)
        val ack = DatalinkClient.buildAckPacket(0x1234, 0x0042, 0x0068, 0x0005)
        assertEquals(34, ack.size)
        assertEquals(DatalinkClient.PKT_ACK, ack[6])
        // Each cursor group is 8 bytes: lo,hi,lo,hi,0,0,0,0  (group layout is an on-device hypothesis)
        // videoCursor at payload offset 0 (byte 8 of total packet)
        assertEquals(0x42.toByte(), ack[8])
        assertEquals(0x00.toByte(), ack[9])
        assertEquals(0x42.toByte(), ack[10])
        // ackedSeq group
        assertEquals(0x68.toByte(), ack[16])
        // extraSeq group
        assertEquals(0x05.toByte(), ack[24])
    }

    @Test
    fun `firstRealNalOffset skips private 0xFF marker and finds real NAL`() {
        val djiHeader = byteArrayOf(0, 0, 1, 0xFF.toByte(), 0, 0, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8)
        val spsNal = byteArrayOf(0, 0, 0, 1, 0x40, 0x01, 0x0C)
        val combined = djiHeader + spsNal

        val offset = VideoDepacketizer.firstRealNalOffset(combined)
        assertEquals(16, offset)
    }

    @Test
    fun `depacketizer reassembles multi-packet frame`() {
        var frameResult: ByteArray? = null
        val depacketizer = VideoDepacketizer(onFrameComplete = { completed ->
            frameResult = completed
        })

        // Build 2 packets for frame 1
        // Packet 1: starts with DJI marker + part of NAL
        val djiMarker = byteArrayOf(0, 0, 1, 0xFF.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        val nalPart1 = byteArrayOf(0, 0, 1, 0x67, 10, 20)
        val p1Body = djiMarker + nalPart1

        val p1 = ByteArray(20 + p1Body.size)
        p1[6] = 0x02 // PKT_VIDEO
        p1[16] = 1   // frameNo = 1
        p1[17] = 0   // pos 0
        p1[18] = 0
        p1Body.copyInto(p1, 20)

        // Packet 2: remainder of NAL
        val nalPart2 = byteArrayOf(30, 40, 50)
        val p2 = ByteArray(20 + nalPart2.size)
        p2[6] = 0x02 // PKT_VIDEO
        p2[16] = 1   // frameNo = 1
        p2[17] = 0x80.toByte() // 0x80 >> 7 = 1
        p2[18] = 0            // pos 1
        nalPart2.copyInto(p2, 20)

        // Packet 3: start of frame 2 (triggers completion of frame 1)
        val p3 = ByteArray(20 + djiMarker.size)
        p3[6] = 0x02
        p3[16] = 2   // frameNo = 2
        djiMarker.copyInto(p3, 20)

        depacketizer.feed(p1)
        depacketizer.feed(p2)
        depacketizer.feed(p3)

        // Emit always normalises to a 4-byte Annex-B start code for MediaCodec.
        val expectedNal = byteArrayOf(0, 0, 0, 1) + byteArrayOf(0x67, 10, 20) + nalPart2
        assertArrayEquals(expectedNal, frameResult)
    }

    @Test
    fun `depacketizer emits a frame as soon as the declared size is complete`() {
        val frames = mutableListOf<ByteArray>()
        val depacketizer = VideoDepacketizer(onFrameComplete = { frames.add(it) })

        // DJI frame header: `00 00 01 FF` + u32 LE payload count -> total frame = count + 16.
        val declared = 16
        val header = ByteArray(16).also {
            it[0] = 0; it[1] = 0; it[2] = 1; it[3] = 0xFF.toByte()
            it[4] = (declared and 0xFF).toByte()
        }
        val nal = ByteArray(declared).also { it[0] = 0; it[1] = 0; it[2] = 1; it[3] = 0x65 }

        val p1 = ByteArray(20 + header.size + 8)
        p1[6] = 0x02          // PKT_VIDEO
        p1[16] = 7            // frameNo
        header.copyInto(p1, 20)
        nal.copyOfRange(0, 8).copyInto(p1, 20 + header.size)

        val p2 = ByteArray(20 + 8)
        p2[6] = 0x02
        p2[16] = 7
        p2[17] = 0x80.toByte() // continuation (pos != 0)
        nal.copyOfRange(8, 16).copyInto(p2, 20)

        depacketizer.feed(p1)
        assertEquals("frame is incomplete until the declared byte count is reached", 0, frames.size)

        depacketizer.feed(p2)
        assertEquals("frame must be flushed on the declared size, not on the next frame", 1, frames.size)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1) + nal.copyOfRange(3, 16), frames[0])

        // Stream keeps advancing: the next declared frame completes inside its own packets.
        val p3 = ByteArray(20 + 16 + 4).also {
            it[6] = 0x02
            it[16] = 8
            it[20] = 0; it[21] = 0; it[22] = 1; it[23] = 0xFF.toByte(); it[24] = 4
        }
        depacketizer.feed(p3)
        assertEquals(2, frames.size)
    }

    @Test
    fun `depacketizer stops trusting an oversized declared size after two frames`() {
        val frames = mutableListOf<ByteArray>()
        val logs = mutableListOf<String>()
        val depacketizer = VideoDepacketizer(onFrameComplete = { frames.add(it) }, log = { logs.add(it) })

        // Declared count wildly larger than the real frame (e.g. bytes 4..7 are really a timestamp).
        // The old behaviour: the declared size is never reached, so the fallback never triggered and
        // every single frame logged "short frame" at 30 Hz.
        fun frame(frameNo: Int): List<ByteArray> {
            val header = ByteArray(16).also {
                it[0] = 0; it[1] = 0; it[2] = 1; it[3] = 0xFF.toByte()
                it[4] = 0x50; it[5] = 0xC3.toByte()   // 50000 little-endian
            }
            val nalPart1 = ByteArray(8).also { it[0] = 0; it[1] = 0; it[2] = 1; it[3] = 0x65 }
            val first = ByteArray(20 + 16 + 8).also { it[6] = 0x02; it[16] = frameNo.toByte() }
            header.copyInto(first, 20)
            nalPart1.copyInto(first, 36)
            val rest = ByteArray(20 + 8).also {
                it[6] = 0x02
                it[16] = frameNo.toByte()
                it[17] = 0x80.toByte()                 // continuation (pos != 0)
            }
            ByteArray(8) { (frameNo * 8 + it).toByte() }.copyInto(rest, 20)
            return listOf(first, rest)
        }

        for (f in 0 until 4) frame(f + 1).forEach { depacketizer.feed(it) }

        assertEquals("stream must keep making progress", 3, frames.size)
        assertEquals("the mismatch must be counted once per frame, only while the hypothesis is alive", 2, depacketizer.truncatedFrames)
        assertEquals("no per-frame log spam", 2, logs.count { it.contains("declared size") })
        assertEquals("the declared-size hypothesis must be dropped", false, depacketizer.usingDeclaredFrameSize)
    }
}

