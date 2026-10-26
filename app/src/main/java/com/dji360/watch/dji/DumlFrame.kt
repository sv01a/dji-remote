package com.dji360.watch.dji

import java.nio.charset.StandardCharsets

/**
 * DJI DUML (DJI Universal Messaging Layer) frame codec.
 * Used for communicating with camera subsystems (WiFi, VideoTransmission, Camera settings)
 * over BLE and UDP Datalink.
 *
 * Wire format (13 + payload.size bytes):
 *   0x55 | len_lo | (ver<<2) | len_hi | crc8 | sender | receiver | seq (2, LE) | flags | cmdSet | cmdId | payload | crc16 (2, LE)
 */
data class DumlFrame(
    val sender: Int,
    val receiver: Int,
    val seq: Int,
    val flags: Int,
    val cmdSet: Int,
    val cmdId: Int,
    val payload: ByteArray,
    val raw: ByteArray
) {
    val isResponse: Boolean get() = (flags and 0xC0) == 0xC0 || (flags and 0x80) != 0

    companion object {
        const val SOF: Byte = 0x55

        // Addressing (packed as (id shl 5) or type)
        const val SENDER_APP: Int = 0x02
        const val RX_CAMERA: Int = 0x01
        const val RX_WIFI: Int = 0x07
        const val RX_VIDEO_TRANS: Int = 0x08
        const val RX_SESSION: Int = 0xF0

        // Flags
        const val FLAG_REQUEST: Int = 0x40
        const val FLAG_RESPONSE: Int = 0xC0
        const val FLAG_NOTIFY: Int = 0x00

        // Common CmdSets
        const val CMDSET_COMMON: Int = 0x00
        const val CMDSET_CAMERA: Int = 0x02
        const val CMDSET_WIFI: Int = 0x07
        const val CMDSET_STREAM: Int = 0x08
        const val CMDSET_LIVE_VIEW: Int = 0x09

        // WiFi CmdIDs
        const val CMD_WIFI_GET_SSID: Int = 0x07
        const val CMD_WIFI_GET_PASSWORD: Int = 0x0E
        const val CMD_WIFI_GET_MAC: Int = 0x0C
        const val CMD_WIFI_CONNECT: Int = 0x47

        // Live View / Stream CmdIDs
        const val CMD_LIVE_VIEW_ENABLE: Int = 0xA8

        /** Reflected CRC-8, init 0x77, poly 0x8C over header bytes (0..2). */
        fun crc8(data: ByteArray, offset: Int = 0, length: Int = data.size): Int {
            var c = 0x77
            for (i in offset until (offset + length)) {
                c = c xor (data[i].toInt() and 0xFF)
                for (b in 0 until 8) {
                    c = if ((c and 1) != 0) (c ushr 1) xor 0x8C else (c ushr 1)
                }
            }
            return c and 0xFF
        }

        /** Reflected CRC-16, init 0x3692, poly 0x8408 over frame minus trailing CRC16. */
        fun crc16(data: ByteArray, offset: Int = 0, length: Int = data.size): Int {
            var c = 0x3692
            for (i in offset until (offset + length)) {
                c = c xor (data[i].toInt() and 0xFF)
                for (b in 0 until 8) {
                    c = if ((c and 1) != 0) (c ushr 1) xor 0x8408 else (c ushr 1)
                }
            }
            return c and 0xFFFF
        }

        /** Build and encode a complete DUML frame. */
        fun build(
            sender: Int,
            receiver: Int,
            seq: Int,
            flags: Int,
            cmdSet: Int,
            cmdId: Int,
            payload: ByteArray = ByteArray(0)
        ): ByteArray {
            val total = 13 + payload.size
            val out = ByteArray(total)
            out[0] = SOF
            out[1] = (total and 0xFF).toByte()
            out[2] = ((1 shl 2) or ((total ushr 8) and 0x03)).toByte() // ver=1
            out[3] = crc8(out, 0, 3).toByte()
            out[4] = (sender and 0xFF).toByte()
            out[5] = (receiver and 0xFF).toByte()
            out[6] = (seq and 0xFF).toByte()
            out[7] = ((seq ushr 8) and 0xFF).toByte()
            out[8] = (flags and 0xFF).toByte()
            out[9] = (cmdSet and 0xFF).toByte()
            out[10] = (cmdId and 0xFF).toByte()
            if (payload.isNotEmpty()) {
                payload.copyInto(out, 11)
            }
            val c = crc16(out, 0, 11 + payload.size)
            out[total - 2] = (c and 0xFF).toByte()
            out[total - 1] = ((c ushr 8) and 0xFF).toByte()
            return out
        }

        /** Parse a received DUML frame. Throws [FrameException] if invalid. */
        fun parse(data: ByteArray): DumlFrame {
            if (data.size < 13) throw FrameException("Frame too short (${data.size} < 13)")
            if (data[0] != SOF) throw FrameException("Invalid SOF (0x%02X != 0x55)".format(data[0]))

            val lenLo = data[1].toInt() and 0xFF
            val lenHi = data[2].toInt() and 0x03
            val declaredLen = (lenHi shl 8) or lenLo
            if (data.size < declaredLen) {
                throw FrameException("Truncated frame: declared $declaredLen, got ${data.size}")
            }

            val expectedCrc8 = crc8(data, 0, 3)
            val actualCrc8 = data[3].toInt() and 0xFF
            if (expectedCrc8 != actualCrc8) {
                throw FrameException("CRC8 mismatch: expected 0x%02X, got 0x%02X".format(expectedCrc8, actualCrc8))
            }

            val expectedCrc16 = crc16(data, 0, declaredLen - 2)
            val actualCrc16 = (data[declaredLen - 2].toInt() and 0xFF) or
                    ((data[declaredLen - 1].toInt() and 0xFF) shl 8)
            if (expectedCrc16 != actualCrc16) {
                throw FrameException("CRC16 mismatch: expected 0x%04X, got 0x%04X".format(expectedCrc16, actualCrc16))
            }

            val sender = data[4].toInt() and 0xFF
            val receiver = data[5].toInt() and 0xFF
            val seq = (data[6].toInt() and 0xFF) or ((data[7].toInt() and 0xFF) shl 8)
            val flags = data[8].toInt() and 0xFF
            val cmdSet = data[9].toInt() and 0xFF
            val cmdId = data[10].toInt() and 0xFF
            val payload = if (declaredLen > 13) {
                data.copyOfRange(11, declaredLen - 2)
            } else ByteArray(0)

            return DumlFrame(sender, receiver, seq, flags, cmdSet, cmdId, payload, data.copyOf(declaredLen))
        }

        /** Query WiFi SSID frame builder. */
        fun queryWifiSsid(seq: Int): ByteArray =
            build(SENDER_APP, RX_WIFI, seq, FLAG_REQUEST, CMDSET_WIFI, CMD_WIFI_GET_SSID)

        /** Query WiFi Password frame builder. */
        fun queryWifiPassword(seq: Int): ByteArray =
            build(SENDER_APP, RX_WIFI, seq, FLAG_REQUEST, CMDSET_WIFI, CMD_WIFI_GET_PASSWORD)

        /** Query WiFi MAC frame builder. */
        fun queryWifiMac(seq: Int): ByteArray =
            build(SENDER_APP, RX_WIFI, seq, FLAG_REQUEST, CMDSET_WIFI, CMD_WIFI_GET_MAC)

        /**
         * Unpack WiFi string reply: format is `[status: 1 byte (0=ok)][len: 1 byte][utf8 string]`.
         * Returns null if status != 0 or truncated.
         */
        fun unpackStatusString(payload: ByteArray): String? {
            if (payload.size < 2) return null
            val status = payload[0].toInt() and 0xFF
            if (status != 0) return null
            val len = payload[1].toInt() and 0xFF
            val end = (2 + len).coerceAtMost(payload.size)
            return String(payload, 2, end - 2, StandardCharsets.UTF_8)
        }

        fun hex(bytes: ByteArray): String =
            bytes.joinToString("") { "%02X".format(it) }
    }

    class FrameException(msg: String) : Exception(msg)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as DumlFrame
        return sender == other.sender && receiver == other.receiver &&
                seq == other.seq && flags == other.flags &&
                cmdSet == other.cmdSet && cmdId == other.cmdId &&
                payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = sender
        result = 31 * result + receiver
        result = 31 * result + seq
        result = 31 * result + flags
        result = 31 * result + cmdSet
        result = 31 * result + cmdId
        result = 31 * result + payload.contentHashCode()
        return result
    }
}
