package com.dji360.watch.dji

/**
 * CRC-32, ported from the official DJI demo
 * (reference/Osmo-GPS-Controller-Demo/utils/crc/custom_crc32.{c,h}).
 *
 * Standard reflected CRC-32 (poly 0xEDB88320) but with the DJI-specific
 * init value 0x00003AA3 and NO final xor-out (custom_crc32.h crc32_init()).
 * Verified against the official test frame in connect_cmd_frame.txt.
 */
object Crc32 {
    private val TABLE = IntArray(256).also { table ->
        for (i in 0 until 256) {
            var c = i
            repeat(8) {
                c = if (c and 1 != 0) ((c ushr 1) xor 0xEDB88320.toInt()) else c ushr 1
            }
            table[i] = c
        }
    }

    /** CRC-32 over [length] bytes of [data] starting at [offset] (init 0x3AA3, no xor-out). */
    fun calculate(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Long {
        var crc = 0x3aa3L
        val end = offset + length
        for (i in offset until end) {
            val idx = ((crc xor (data[i].toLong() and 0xFF)) and 0xFF).toInt()
            crc = ((TABLE[idx].toLong() and 0xFFFFFFFFL) xor (crc ushr 8)) and 0xFFFFFFFFL
        }
        return crc
    }
}
