package com.dji360.watch.dji

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class DumlTests {

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.replace(" ", "")
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            out[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    @Test
    fun `crc8 matches official vector`() {
        val hdr = hexToBytes("55 0D 04")
        val c8 = DumlFrame.crc8(hdr)
        assertEquals(0x33, c8)
    }

    @Test
    fun `crc16 matches official vector for get wifi ssid`() {
        val body = hexToBytes("55 0D 04 33 02 07 00 A0 40 07 07")
        val c16 = DumlFrame.crc16(body)
        assertEquals(0x7274, c16)
    }

    @Test
    fun `build query wifi ssid matches official byte vector`() {
        // Official frame from Osmosis capture: 550d0433020700a04007077472
        val expected = hexToBytes("550D0433020700A04007077472")
        val built = DumlFrame.queryWifiSsid(seq = 0xA000)
        assertArrayEquals(expected, built)

        val parsed = DumlFrame.parse(built)
        assertEquals(DumlFrame.SENDER_APP, parsed.sender)
        assertEquals(DumlFrame.RX_WIFI, parsed.receiver)
        assertEquals(0xA000, parsed.seq)
        assertEquals(DumlFrame.FLAG_REQUEST, parsed.flags)
        assertEquals(DumlFrame.CMDSET_WIFI, parsed.cmdSet)
        assertEquals(DumlFrame.CMD_WIFI_GET_SSID, parsed.cmdId)
        assertEquals(0, parsed.payload.size)
    }

    @Test
    fun `build query wifi password matches official byte vector`() {
        // Official frame: 550d0433020700a040070eb5ef
        val expected = hexToBytes("550D0433020700A040070EB5EF")
        val built = DumlFrame.queryWifiPassword(seq = 0xA000)
        assertArrayEquals(expected, built)

        val parsed = DumlFrame.parse(built)
        assertEquals(DumlFrame.CMD_WIFI_GET_PASSWORD, parsed.cmdId)
    }

    @Test
    fun `build query wifi mac matches official byte vector`() {
        // Official frame: 550d0433020700a040070ca7cc
        val expected = hexToBytes("550D0433020700A040070CA7CC")
        val built = DumlFrame.queryWifiMac(seq = 0xA000)
        assertArrayEquals(expected, built)

        val parsed = DumlFrame.parse(built)
        assertEquals(DumlFrame.CMD_WIFI_GET_MAC, parsed.cmdId)
    }

    @Test
    fun `unpack status string handles valid and error payloads`() {
        // Status 0x00, length 9, "Osmo360-1"
        val payload = byteArrayOf(0x00, 0x09) + "Osmo360-1".toByteArray(Charsets.UTF_8)
        val s = DumlFrame.unpackStatusString(payload)
        assertEquals("Osmo360-1", s)

        // Error status (e.g. 0x01)
        val errPayload = byteArrayOf(0x01, 0x05) + "error".toByteArray(Charsets.UTF_8)
        assertEquals(null, DumlFrame.unpackStatusString(errPayload))
    }
}
