package com.dji360.watch.dji

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests against OFFICIAL DJI vectors:
 *  - 10 connect-request frames from reference/Osmo-GPS-Controller-Demo/test/connect_cmd_frame.txt
 *  - CRC16/CRC32 cross-checked in-file (offsets verified by python emulation)
 */
class FrameTests {

    private val vectors = listOf(
        "AA330002000000000100982F00197856341206383456789ABC00000000000000000000000000000000FE1F00000000796C667C",
        "AA330002000000000100982F00197856341206383456789ABC00000000000000000000000000000000191A0000000033C8C7BF",
        "AA330002000000000100982F00197856341206383456789ABC000000000000000000000000000000008F1200000000AC768844",
        "AA330002000000000100982F00197856341206383456789ABC00000000000000000000000000000000050B00000000E1B45396",
        "AA330002000000000100982F00197856341206383456789ABC000000000000000000000000000000004F0E000000009B1270F0",
        "AA330002000000000100982F00197856341206383456789ABC00000000000000000000000000000000B71F00000000DD373154",
        "AA330002000000000100982F00197856341206383456789ABC00000000000000000000000000000000501000000000368B20DD",
        "AA330002000000000100982F00197856341206383456789ABC00000000000000000000000000000000B82100000000EF8376BB",
        "AA330002000000000100982F00197856341206383456789ABC00000000000000000000000000000000100C00000000D9FB6877",
        "AA330002000000000100982F00197856341206383456789ABC00000000000000000000000000000000E10200000000048F9616"
    )

    @Test
    fun `parse official connect cmd vectors`() {
        vectors.forEach { v ->
            val f = DjiFrame.parse(hex(v))
            assertEquals(0x00, f.cmdSet)
            assertEquals(0x19, f.cmdId)
            assertEquals(0x02, f.cmdType)
            assertEquals(1, f.seq)
            assertEquals(51, f.raw.size)
            assertArrayEquals(byteArrayOf(0x78.toByte(), 0x56.toByte(), 0x34.toByte(), 0x12.toByte()), f.payload.copyOfRange(0, 4))
            assertEquals(6, f.payload[4].toInt())
        }
    }

    @Test
    fun `rebuild official frame byte-exact`() {
        // Same parameters connect_cmd_frame_builder.c used for vector #1:
        // device_id 0x12345678, mac 38:34:56:78:9A:BC, fw 0, conidx 0, verify_mode 0,
        // random verify_data 0x1FFE, seq 1 (run #1 of the official generator)
        val payload = DjiCommands.connectionRequest(
            deviceId = 0x12345678L,
            macBytes = byteArrayOf(0x38.toByte(), 0x34.toByte(), 0x56.toByte(), 0x78.toByte(), 0x9A.toByte(), 0xBC.toByte()),
            verifyMode = 0,
            verifyData = 0x1FFE
        )
        val rebuilt = DjiFrame.build(0x00, 0x19, DjiFrame.CMD_WAIT_RESULT, seq = 1, payload = payload)
        assertArrayEquals(hex(vectors[0]), rebuilt)
        assertEquals(1, DjiFrame.parse(rebuilt).seq)
    }

    @Test
    fun `crc16 of header matches vector`() {
        val raw = hex(vectors[0])
        assertEquals(0x2F98, Crc16.calculate(raw, 0, 10))
    }

    @Test
    fun `crc32 of frame matches vector`() {
        val raw = hex(vectors[0])
        assertEquals(0x7C666C79L, Crc32.calculate(raw, 0, raw.size - 4))
    }

    @Test
    fun `command payload sizes match C packed structs`() {
        // connection_request_command_frame = 4+1+16+4+1+1+2+4 = 33
        assertEquals(33, DjiCommands.connectionRequest(0xFF66, ByteArray(6), 1, 42).size)
        // connection_request_response_frame = 4+1+4 = 9
        assertEquals(9, DjiCommands.connectionResponse(0xFF66, 0, 0).size)
        // record_control_command_frame = 4+1+4 = 9
        assertEquals(9, DjiCommands.recordControl(0xFF66, true).size)
        // camera_mode_switch_command_frame = 4+1+4 = 9
        assertEquals(9, DjiCommands.modeSwitch(0xFF66, 0x38).size)
        // camera_status_subscription_command_frame = 1+1+4 = 6
        assertEquals(6, DjiCommands.statusSubscription().size)
        // key_report_command_frame = 1+1+2 = 4
        assertEquals(4, DjiCommands.keyReport(DjiCommands.KEY_SNAPSHOT).size)
    }

    @Test
    fun `mode icon favors the authoritative 0x1D06 name`() {
        // A "Video" name must NOT yield the 0x00 SlowMotion turtle even if the
        // numeric code is 0 (Osmo 360 may report a base code in 0x1D02).
        assertEquals("🎥", DjiLink.modeIconFor("Video", 0x00))
        assertEquals("🌐", DjiLink.modeIconFor("360 Video", 0x38))
        assertEquals("🌐", DjiLink.modeIconFor("Panoramic Video", 0x38))
        assertEquals("🌐📷", DjiLink.modeIconFor("Panoramic Photo", 0x3F))
        assertEquals("🤳", DjiLink.modeIconFor("Selfie Mode", 0x3C))
        assertEquals("🚀", DjiLink.modeIconFor("Boost Video", 0x41))
        assertEquals("🪐", DjiLink.modeIconFor("Vortex", 0x43))
        assertEquals("🌌", DjiLink.modeIconFor("360\u00B0 SuperNight", 0x44))
        assertEquals("🌙", DjiLink.modeIconFor("Single Lens SuperNight", 0x4A))
        assertEquals("🐢", DjiLink.modeIconFor(null, 0x00))   // fallback to code
        assertEquals("🎥", DjiLink.modeIconFor(null, 0x01))   // fallback to code
        // numeric fallback for the osmo 360 codes when no name yet
        assertEquals("🌐", DjiLink.modeIconFor(null, 0x38))
    }

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { i ->
            ((Character.digit(s[i * 2], 16) shl 4) + Character.digit(s[i * 2 + 1], 16)).toByte()
        }
}
