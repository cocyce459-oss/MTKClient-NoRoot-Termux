package dev.cocyce.mtknative.brom

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Endianness is the easiest thing to get subtly wrong in a protocol port, so
 * these pin every conversion against literal byte expectations.
 */
class WireFormatTest {

    @Test
    fun beU32_is_big_endian() {
        assertArrayEquals(hex("F00DD00D"), WireFormat.beU32(BromOpcodes.MAGIC))
        assertArrayEquals(hex("00004002"), WireFormat.beU32(BromOpcodes.OP_READ32))
        assertArrayEquals(hex("08000000"), WireFormat.beU32(0x8000000))
        assertArrayEquals(hex("FFFFFFFF"), WireFormat.beU32(-1))
    }

    @Test
    fun leU32_bytes_are_little_endian() {
        // The reference sends 0xDEADBEEF as EF BE AD DE.
        assertArrayEquals(hex("EFBEADDE"), WireFormat.leU32(0xDEADBEEF.toInt()))
        assertArrayEquals(hex("01000000"), WireFormat.leU32(1))
    }

    @Test
    fun leU32_round_trips() {
        val values = intArrayOf(0, 1, 0x1234, 0x8000000, 0x7FFFFFFF, -1, 0xDEADBEEF.toInt())
        for (value in values) {
            assertEquals(value, WireFormat.leU32(WireFormat.leU32(value)))
        }
    }

    @Test
    fun beU16_is_big_endian() {
        assertArrayEquals(hex("0002"), WireFormat.beU16(2))
        assertArrayEquals(hex("FFFF"), WireFormat.beU16(0xFFFF))
    }

    @Test
    fun leU16_reads_little_endian() {
        assertEquals(0x3412, WireFormat.leU16(hex("1234")))
    }

    @Test
    fun leU64_round_trips_large_sector_values() {
        val values = longArrayOf(0L, 34L, 2014L, 0x100000000L, Long.MAX_VALUE, -1L)
        for (value in values) {
            assertEquals(value, WireFormat.leU64(WireFormat.leU64(value)))
        }
    }

    @Test
    fun leU64_is_little_endian() {
        assertArrayEquals(hex("2200000000000000"), WireFormat.leU64(0x22L))
    }

    @Test
    fun padTo_adds_only_when_unaligned() {
        assertArrayEquals(hex("AABBCC00"), WireFormat.padTo(hex("AABBCC"), 4))
        assertArrayEquals(hex("AABBCCDD"), WireFormat.padTo(hex("AABBCCDD"), 4))
        assertArrayEquals(ByteArray(0), WireFormat.padTo(ByteArray(0), 4))
        assertArrayEquals(hex("AA000000"), WireFormat.padTo(hex("AA"), 4))
    }

    @Test
    fun concat_preserves_order() {
        val joined = WireFormat.concat(hex("F00DD00D"), hex("00004002"), hex("00001000"))
        assertArrayEquals(hex("F00DD00D0000400200001000"), joined)
    }

    @Test
    fun toHex_uppercases_without_separators() {
        assertEquals("F00DD00D", WireFormat.toHex(hex("f00dd00d")))
        assertEquals("F0:0D", WireFormat.toHex(hex("f00d"), ":"))
    }

    @Test
    fun hex32_renders_unsigned() {
        assertEquals("0xF00DD00D", WireFormat.hex32(BromOpcodes.MAGIC))
        assertEquals("0x00004002", WireFormat.hex32(BromOpcodes.OP_READ32))
    }

    private fun hex(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
