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
        val ack = DatalinkClient.buildAckPacket(0x1234, 0x0005, 0x0042, 0x0068)
        assertEquals(34, ack.size)
        assertEquals(DatalinkClient.PKT_ACK, ack[6])
        // videoCursor at payload offset 0 (byte 8 of total packet)
        assertEquals(0x42.toByte(), ack[8])
        assertEquals(0x00.toByte(), ack[9])
    }

    @Test
    fun `stripDjiMarker removes private 0xFF marker and preserves SPS NAL`() {
        val djiMarker = byteArrayOf(0, 0, 1, 0xFF.toByte(), 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)
        val spsNal = byteArrayOf(0, 0, 1, 0x67, 0x42, 0x00, 0x1E)
        val combined = djiMarker + spsNal

        val stripped = VideoDepacketizer.stripDjiMarker(combined)
        assertArrayEquals(spsNal, stripped)
    }

    @Test
    fun `depacketizer reassembles multi-packet frame`() {
        var frameResult: ByteArray? = null
        val depacketizer = VideoDepacketizer { completed ->
            frameResult = completed
        }

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

        val expectedNal = nalPart1 + nalPart2
        assertArrayEquals(expectedNal, frameResult)
    }
}
