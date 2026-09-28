package dev.cocyce.mtknative.brom

/**
 * Minimal byte-order helpers.
 *
 * The BROM protocol mixes endianness: frame headers are big-endian, data and
 * acknowledgements are little-endian. Keeping the conversions in one place makes
 * the wire format auditable against the reference vectors.
 */
object WireFormat {

    fun beU16(value: Int): ByteArray = byteArrayOf(
        ((value ushr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte()
    )

    fun beU32(value: Int): ByteArray = byteArrayOf(
        ((value ushr 24) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte()
    )

    fun beU32(value: Long): ByteArray = beU32(value.toInt())

    fun leU32(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 24) and 0xFF).toByte()
    )

    fun leU64(value: Long): ByteArray = ByteArray(8) { ((value ushr (8 * it)) and 0xFF).toByte() }

    /** Reads an unsigned little-endian 16-bit value. */
    fun leU16(data: ByteArray, offset: Int = 0): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    /** Reads a little-endian 32-bit value. Returned as a signed Kotlin Int. */
    fun leU32(data: ByteArray, offset: Int = 0): Int =
        (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)

    /** Reads a little-endian 64-bit value. */
    fun leU64(data: ByteArray, offset: Int = 0): Long {
        var result = 0L
        for (i in 7 downTo 0) {
            result = (result shl 8) or (data[offset + i].toLong() and 0xFF)
        }
        return result
    }

    fun concat(vararg parts: ByteArray): ByteArray {
        val total = parts.sumOf { it.size }
        val out = ByteArray(total)
        var pos = 0
        for (part in parts) {
            part.copyInto(out, pos)
            pos += part.size
        }
        return out
    }

    /** Pads [data] with trailing zeroes up to a multiple of [alignment]. */
    fun padTo(data: ByteArray, alignment: Int): ByteArray {
        val remainder = data.size % alignment
        if (remainder == 0) return data
        return data + ByteArray(alignment - remainder)
    }

    /** Formats a signed Int as an unsigned 32-bit hex literal, e.g. `0xF00DD00D`. */
    fun hex32(value: Int): String = "0x%08X".format(value)

    /** Formats a Long as an unsigned 64-bit hex literal. */
    fun hex64(value: Long): String = "0x%016X".format(value)

    fun toHex(data: ByteArray, separator: String = ""): String =
        data.joinToString(separator) { "%02X".format(it) }
}
