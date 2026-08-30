package com.dji360.watch.dji

/**
 * DJI R SDK protocol frame: build + parse.
 * Ported from reference/Osmo-GPS-Controller-Demo/protocol/dji_protocol_parser.c
 * and verified against the official test vector connect_cmd_frame.txt.
 *
 * Frame layout (little-endian everywhere):
 *   SOF(1)=0xAA | Ver/Length(2) | CmdType(1) | ENC(1) | RES(3) | SEQ(2)
 *   | CRC16(2) | DATA: CmdSet(1) CmdID(1) payload(n) | CRC32(4)
 * CRC-16 covers SOF..SEQ; CRC-32 covers SOF..DATA (both incl.).
 */
object DjiFrame {
    const val SOF = 0xAA
    const val HEADER_LEN = 14           // SOF..CmdID inclusive (see dji_protocol_parser.c)
    const val TAIL_LEN = 4              // CRC32
    const val MIN_FRAME_LEN = 16        // header without CmdSet/CmdID + CRC32

    // cmd_type values from logic/enums_logic.h of the official demo
    const val CMD_NO_RESPONSE = 0x00
    const val CMD_RESPONSE_OR_NOT = 0x01
    const val CMD_WAIT_RESULT = 0x02
    const val ACK_NO_RESPONSE = 0x20
    const val ACK_RESPONSE_OR_NOT = 0x21
    const val ACK_WAIT_RESULT = 0x22

    /** Parsed incoming frame. */
    data class Parsed(
        val cmdType: Int,
        val seq: Int,
        val cmdSet: Int,
        val cmdId: Int,
        val payload: ByteArray,
        val raw: ByteArray
    ) {
        val isAck: Boolean get() = (cmdType and 0x20) != 0
    }

    class FrameException(message: String) : Exception(message)

    /**
     * Build a full frame. [payload] is the DATA-payload AFTER CmdSet/CmdID
     * (i.e. exactly what the C structs in dji_protocol_data_structures.h hold).
     */
    fun build(cmdSet: Int, cmdId: Int, cmdType: Int, seq: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val total = HEADER_LEN + payload.size + TAIL_LEN
        val f = ByteArray(total)
        var o = 0
        f[o++] = SOF.toByte()
        val verLen = (total and 0x3FF) // version = 0 in high 6 bits
        f[o++] = (verLen and 0xFF).toByte()
        f[o++] = ((verLen ushr 8) and 0xFF).toByte()
        f[o++] = cmdType.toByte()
        f[o++] = 0x00                    // ENC: no encryption
        f[o++] = 0x00; f[o++] = 0x00; f[o++] = 0x00  // RES
        f[o++] = (seq and 0xFF).toByte()             // SEQ little-endian
        f[o++] = ((seq ushr 8) and 0xFF).toByte()
        val c16 = Crc16.calculate(f, 0, o)           // SOF..SEQ
        f[o++] = (c16 and 0xFF).toByte()
        f[o++] = ((c16 ushr 8) and 0xFF).toByte()
        f[o++] = cmdSet.toByte()
        f[o++] = cmdId.toByte()
        payload.copyInto(f, o)
        o += payload.size
        val c32 = Crc32.calculate(f, 0, o)           // SOF..DATA
        f[o++] = (c32 and 0xFF).toByte()
        f[o++] = ((c32 ushr 8) and 0xFF).toByte()
        f[o++] = ((c32 ushr 16) and 0xFF).toByte()
        f[o++] = ((c32 ushr 24) and 0xFF).toByte()
        return f
    }

    /** Parse a notification frame; throws [FrameException] on any structural/CRC error. */
    fun parse(data: ByteArray): Parsed {
        if (data.size < MIN_FRAME_LEN) throw FrameException("frame too short: ${data.size}")
        if (data[0].toInt() and 0xFF != SOF) throw FrameException("bad SOF")
        val verLen = (data[2].toInt() and 0xFF shl 8) or (data[1].toInt() and 0xFF)
        val expected = verLen and 0x3FF
        if (expected != data.size) throw FrameException("length mismatch: hdr=$expected actual=${data.size}")
        val c16rx = (data[11].toInt() and 0xFF shl 8) or (data[10].toInt() and 0xFF)
        val c16 = Crc16.calculate(data, 0, 10)
        if (c16rx != c16) throw FrameException("CRC16 mismatch rx=%04X calc=%04X".format(c16rx, c16))
        val c32rx = (data[data.size - 1].toInt() and 0xFF shl 24) or
                (data[data.size - 2].toInt() and 0xFF shl 16) or
                (data[data.size - 3].toInt() and 0xFF shl 8) or
                (data[data.size - 4].toInt() and 0xFF)
        val c32 = Crc32.calculate(data, 0, data.size - 4)
        if ((c32rx.toLong() and 0xFFFFFFFFL) != c32) throw FrameException("CRC32 mismatch rx=%08X calc=%08X".format(c32rx, c32))
        val cmdType = data[3].toInt() and 0xFF
        val seq = (data[9].toInt() and 0xFF shl 8) or (data[8].toInt() and 0xFF)
        val cmdSet = data[12].toInt() and 0xFF
        val cmdId = data[13].toInt() and 0xFF
        val payload = data.copyOfRange(14, data.size - 4)
        return Parsed(cmdType, seq, cmdSet, cmdId, payload, data)
    }

    fun hex(b: ByteArray): String = b.joinToString(" ") { "%02X".format(it) }
}
