package com.dji360.watch.dji

/**
 * DATA-payload builders for DJI R SDK commands.
 * Field layout is ported 1:1 from the official demo
 * (protocol/dji_protocol_data_structures.h, packed little-endian structs).
 */
object DjiCommands {
    // ---- CmdSet/CmdID constants (docs/protocol_data_segment.md of the demo) ----
    const val CMDSET_COMMON = 0x00
    const val CMD_VERSION_QUERY = 0x00
    const val CMD_KEY_REPORT = 0x11
    const val CMD_CONNECTION_REQUEST = 0x19

    const val CMDSET_CAMERA = 0x1D
    const val CMD_CAMERA_STATUS_PUSH = 0x02
    const val CMD_RECORD_CONTROL = 0x03
    const val CMD_MODE_SWITCH = 0x04
    const val CMD_STATUS_SUBSCRIPTION = 0x05
    const val CMD_NEW_STATUS_PUSH = 0x06

    // Osmo 360 device_id (docs/protocol_data_segment.md: Osmo 360 = 0xFF66)
    const val DEVICE_ID_OSMO_360 = 0xFF66

    // ---- LE write helpers ----
    fun u16(v: Int): ByteArray = byteArrayOf((v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte())
    fun u32(v: Long): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(), ((v ushr 24) and 0xFF).toByte()
    )

    // ---- 0x00/0x19 connection request command (33 bytes) ----
    /**
     * Layout (docs/protocol_data_segment.md): device_id@0..3, mac_addr_len@4,
     * mac_addr@5..20, fw_version@21..24, conidx@25, verify_mode@26,
     * verify_data@27..28, reserved@29..32.
     * [macBytes] — up to 6 bytes of OUR OWN adapter address, MSB first as printed.
     */
    fun connectionRequest(deviceId: Long, macBytes: ByteArray, verifyMode: Int, verifyData: Int): ByteArray {
        val out = ByteArray(33)
        val mac = macBytes.copyOf(6)  // zero-padded to 16-byte field
        u32(deviceId).copyInto(out, 0)     // device_id
        out[4] = mac.size.toByte()         // mac_addr_len = 6
        mac.copyInto(out, 5)               // mac_addr[0..5] (field 5..20, rest stays 0)
        u32(0).copyInto(out, 21)           // fw_version = 0
        out[25] = 0                        // conidx
        out[26] = verifyMode.toByte()      // verify_mode
        u16(verifyData).copyInto(out, 27)  // verify_data LE
        // reserved[29..32] remain 0
        return out
    }

    /** 0x00/0x19 ack we send back to the camera (9 bytes). */
    fun connectionResponse(deviceId: Long, retCode: Int, cameraNumber: Int): ByteArray {
        val out = ByteArray(9)
        u32(deviceId).copyInto(out, 0)
        out[4] = retCode.toByte()
        u32(cameraNumber.toLong()).copyInto(out, 5)
        return out
    }

    // ---- 0x1D/0x03 record control (9 bytes) ----
    fun recordControl(deviceId: Long, start: Boolean): ByteArray {
        val out = ByteArray(9)
        u32(deviceId).copyInto(out, 0)
        out[4] = (if (start) 0x00 else 0x01).toByte()
        // reserved[5..8] = 0
        return out
    }

    // ---- 0x1D/0x04 mode switch (9 bytes) ----
    fun modeSwitch(deviceId: Long, mode: Int): ByteArray {
        val out = ByteArray(9)
        u32(deviceId).copyInto(out, 0)
        out[4] = mode.toByte()
        return out
    }

    // ---- 0x1D/0x05 status subscription (6 bytes) ----
    fun statusSubscription(pushMode: Int = 3, pushFreq: Int = 20): ByteArray {
        val out = ByteArray(6)
        out[0] = pushMode.toByte()    // 3 = periodic + on-change
        out[1] = pushFreq.toByte()    // only 20 (2 Hz) allowed
        return out
    }

    // ---- 0x00/0x11 key report (4 bytes) ----
    fun keyReport(keyCode: Int, mode: Int = 1, keyValue: Int = 0): ByteArray {
        val out = ByteArray(4)
        out[0] = keyCode.toByte()
        out[1] = mode.toByte()
        u16(keyValue).copyInto(out, 2)
        return out
    }

    // ---- 0x00/0x17 GPS data push (48 bytes, docs/protocol_data_segment.md) ----
    fun gpsData(
        yearMonthDay: Int, hourMinuteSecond: Int,
        longitudeE7: Long, latitudeE7: Long, heightMm: Long,
        speedNorthCms: Float, speedEastCms: Float, speedDownCms: Float,
        verticalAccuracyMm: Long, horizontalAccuracyMm: Long,
        speedAccuracyCms: Long, satellites: Long
    ): ByteArray {
        val out = ByteArray(48)
        fun i32(off: Int, v: Long) = u32(v and 0xFFFFFFFFL).copyInto(out, off)
        fun f32(off: Int, v: Float) = u32(v.toRawBits().toLong() and 0xFFFFFFFFL).copyInto(out, off)
        i32(0, yearMonthDay.toLong())
        i32(4, hourMinuteSecond.toLong())
        i32(8, longitudeE7)
        i32(12, latitudeE7)
        i32(16, heightMm)
        f32(20, speedNorthCms)
        f32(24, speedEastCms)
        f32(28, speedDownCms)
        i32(32, verticalAccuracyMm)
        i32(36, horizontalAccuracyMm)
        i32(40, speedAccuracyCms)
        i32(44, satellites)
        return out
    }

    const val CMD_GPS_PUSH = 0x17

    const val KEY_RECORD = 0x01
    const val KEY_QS = 0x02
    const val KEY_SNAPSHOT = 0x03
}
