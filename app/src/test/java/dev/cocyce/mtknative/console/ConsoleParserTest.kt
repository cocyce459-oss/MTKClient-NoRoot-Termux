package dev.cocyce.mtknative.console

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The console must accept the same argument spellings upstream `mtk.py` does,
 * including `getint()`'s decimal-then-hex fallback.
 */
class ConsoleParserTest {

    @Test
    fun tokenize_splits_on_whitespace() {
        assertEquals(listOf("r", "boot,vbmeta", "boot.img,vbmeta.img"),
            ConsoleParser.tokenize("r boot,vbmeta boot.img,vbmeta.img"))
    }

    @Test
    fun tokenize_collapses_repeated_whitespace() {
        assertEquals(listOf("memread", "0x0", "0x10"),
            ConsoleParser.tokenize("   memread    0x0 \t 0x10   "))
    }

    @Test
    fun tokenize_honours_quotes() {
        assertEquals(listOf("r", "boot", "my file.img"),
            ConsoleParser.tokenize("r boot \"my file.img\""))
        assertEquals(listOf("a", "b c"), ConsoleParser.tokenize("a 'b c'"))
    }

    @Test
    fun tokenize_keeps_empty_quoted_token() {
        assertEquals(listOf("x", "", "y"), ConsoleParser.tokenize("""x "" y"""))
    }

    @Test
    fun tokenize_returns_empty_for_blank_line() {
        assertTrue(ConsoleParser.tokenize("").isEmpty())
        assertTrue(ConsoleParser.tokenize("   ").isEmpty())
    }

    @Test
    fun parseNumber_accepts_decimal() {
        assertEquals(0L, ConsoleParser.parseNumber("0"))
        assertEquals(4096L, ConsoleParser.parseNumber("4096"))
        assertEquals(-1L, ConsoleParser.parseNumber("-1"))
    }

    @Test
    fun parseNumber_accepts_hex_with_and_without_prefix() {
        assertEquals(0x8000000L, ConsoleParser.parseNumber("0x8000000"))
        assertEquals(0x8000000L, ConsoleParser.parseNumber("0X8000000"))
        // stage2.py's getint() falls back to base 16 for bare hex.
        assertEquals(0x100L, ConsoleParser.parseNumber("0x100"))
        assertEquals(0xFFL, ConsoleParser.parseNumber("ff"))
    }

    @Test
    fun parseNumber_trims_surrounding_space() {
        assertEquals(16L, ConsoleParser.parseNumber("  0x10  "))
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseNumber_rejects_garbage() {
        ConsoleParser.parseNumber("not-a-number")
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseNumber_rejects_empty() {
        ConsoleParser.parseNumber("")
    }

    @Test
    fun isHexByteString_requires_even_length() {
        assertTrue(ConsoleParser.isHexByteString("1122334455667788"))
        assertTrue(ConsoleParser.isHexByteString("0x1122334455667788"))
        assertFalse("odd length is a dword, not a byte string", ConsoleParser.isHexByteString("123"))
        assertFalse(ConsoleParser.isHexByteString("zz"))
        assertFalse(ConsoleParser.isHexByteString(""))
    }

    @Test
    fun hexToBytes_decodes() {
        assertArrayEquals(byteArrayOf(0x11, 0x22, 0x33, 0x44),
            ConsoleParser.hexToBytes("11223344"))
        assertArrayEquals(byteArrayOf(0x11, 0x22, 0x33, 0x44),
            ConsoleParser.hexToBytes("0x11223344"))
    }

    @Test
    fun flagValue_supports_space_and_equals_forms() {
        val args = ConsoleParser.tokenize("rpmb --start 0x100 --length=0x200 --reverse")
        assertEquals("0x100", ConsoleParser.flagValue(args, "--start"))
        assertEquals("0x200", ConsoleParser.flagValue(args, "--length"))
        assertNull(ConsoleParser.flagValue(args, "--filename"))
    }

    @Test
    fun flagValue_ignores_dangling_flag() {
        val args = ConsoleParser.tokenize("memread 0x0 --filename")
        assertNull(ConsoleParser.flagValue(args, "--filename"))
    }

    @Test
    fun positional_drops_flags_and_their_values_only_by_prefix() {
        val args = ConsoleParser.tokenize("memread 0x0 0x10 --filename out.bin")
        // positional() filters flags, not flag *values*; callers pair them explicitly.
        assertEquals(listOf("memread", "0x0", "0x10", "out.bin"),
            ConsoleParser.positional(args))
    }

    // ------------------------------------------------------------------
    // memwrite payload precedence
    //
    // This ordering is easy to get wrong and silently corrupts a write: the
    // reference treats a spec containing "0x" as a little-endian dword via
    // pack("<I"), and any other even-length hex string as literal bytes.
    // ------------------------------------------------------------------

    @Test
    fun memwrite_hex_prefixed_spec_is_a_little_endian_dword() {
        assertArrayEquals(byteArrayOf(0x78, 0x56, 0x34, 0x12),
            ConsoleParser.decodeMemWritePayload("0x12345678"))
        assertArrayEquals(byteArrayOf(0x78, 0x56, 0x34, 0x12),
            ConsoleParser.decodeMemWritePayload("0X12345678"))
    }

    @Test
    fun memwrite_bare_hex_string_keeps_byte_order() {
        assertArrayEquals(
            byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88.toByte()),
            ConsoleParser.decodeMemWritePayload("1122334455667788")
        )
    }

    @Test
    fun memwrite_decimal_spec_is_a_little_endian_dword() {
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0, 0, 0),
            ConsoleParser.decodeMemWritePayload("255"))
    }

    @Test
    fun memwrite_comma_list_encodes_each_dword() {
        assertArrayEquals(
            byteArrayOf(1, 0, 0, 0, 2, 0, 0, 0),
            ConsoleParser.decodeMemWritePayload("0x1,0x2")
        )
    }

    @Test
    fun memwrite_odd_length_decimal_spec_is_a_dword() {
        // "123" is odd-length, so it is not a byte string. getint() tries decimal
        // first and succeeds, so this is 123, NOT 0x123.
        assertArrayEquals(byteArrayOf(0x7B, 0, 0, 0),
            ConsoleParser.decodeMemWritePayload("123"))
    }

    @Test
    fun memwrite_odd_length_hex_spec_falls_back_to_base_16() {
        // "abc" fails decimal, so getint() falls back to base 16 -> 0xABC = 2748,
        // which is little-endian BC 0A 00 00.
        assertArrayEquals(byteArrayOf(0xBC.toByte(), 0x0A, 0, 0),
            ConsoleParser.decodeMemWritePayload("abc"))
    }

    @Test
    fun looksLikePath_detects_file_inputs() {
        assertTrue(ConsoleParser.looksLikePath("/sdcard/data.bin"))
        assertTrue(ConsoleParser.looksLikePath("boot.img"))
        assertTrue(ConsoleParser.looksLikePath("payloads/stage1.bin"))
        assertFalse(ConsoleParser.looksLikePath("0x12345678"))
        assertFalse(ConsoleParser.looksLikePath("11223344"))
    }
}
