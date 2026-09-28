package dev.cocyce.mtknative.brom

import dev.cocyce.mtknative.brom.WireFormat.leU32
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Replays wire vectors recorded from the *reference implementation itself*.
 *
 * `tools/golden_vectors.py` monkeypatches `usbwrite`/`usbread` on the real
 * `Stage2` class in bkerler/mtkclient and records the exact bytes it emits, its
 * per-call frame boundaries and every read length it requests. Those recordings
 * live in `src/test/resources/golden_vectors.json`.
 *
 * These tests therefore do not assert "my port agrees with my understanding of
 * the protocol" — they assert "my port agrees, byte for byte and transfer for
 * transfer, with the code that is known to work on real hardware". Frame
 * boundaries matter: BROM is sensitive to transfer granularity, so a port that
 * produced the same bytes in different chunks would still be wrong, and would
 * still fail here.
 */
class BromProtocolVectorTest {

    /** Records frames written and read lengths requested, replying from [responder]. */
    private class RecordingWire(private val responder: (Int) -> ByteArray) : Wire {
        val frames = mutableListOf<String>()
        val readSizes = mutableListOf<Int>()

        /** Concatenated hex of everything transmitted, comparable to the golden `tx`. */
        val tx: String get() = frames.joinToString("")

        override fun write(data: ByteArray) {
            frames.add(WireFormat.toHex(data))
        }

        override fun read(length: Int): ByteArray {
            readSizes.add(length)
            val response = responder(length)
            assertEquals("responder must satisfy the requested length", length, response.size)
            return response
        }
    }

    // ------------------------------------------------------------------
    // Register access
    // ------------------------------------------------------------------

    @Test
    fun read32_single_matches_reference() =
        verify("read32_single") { it.read32(0x1000, 1) }

    @Test
    fun read32_burst_increments_address_matches_reference() =
        verify("read32_three") { it.read32(0x2000, 3) }

    @Test
    fun write32_sends_little_endian_data_matches_reference() =
        verify("write32_int") { it.write32(0x100, 0xDEADBEEF.toInt()) }

    @Test
    fun write32_burst_matches_reference() =
        verify("write32_list") {
            it.write32(0x200, intArrayOf(0x11111111, 0x22222222, 0x33333333))
        }

    @Test
    fun jump_matches_reference() =
        verify("jump") { it.jump(0x22000000) }

    @Test
    fun clear_cache_matches_reference() =
        verify("cmd_C8_clearcache") { it.clearCache() }

    // ------------------------------------------------------------------
    // Bulk memory access
    // ------------------------------------------------------------------

    @Test
    fun memread_single_chunk_matches_reference() =
        verify("memread_16") { it.memRead(0x0, 0x10) }

    @Test
    fun memread_chunks_at_0x100_matches_reference() =
        verify("memread_0x250") { it.memRead(0x8000000, 0x250) }

    @Test
    fun memwrite_acks_once_after_last_frame_matches_reference() =
        verify("memwrite_bytes") { it.memWrite(0x200000, hex("1122334455667788")) }

    @Test
    fun memwrite_pads_to_dword_boundary_matches_reference() =
        verify("memwrite_odd_pad") { it.memWrite(0x0, hex("AABBCC")) }

    // ------------------------------------------------------------------
    // Control
    // ------------------------------------------------------------------

    @Test
    fun reboot_reads_no_response_matches_reference() =
        verify("reboot") { it.reboot() }

    // ------------------------------------------------------------------
    // Flash
    // ------------------------------------------------------------------

    @Test
    fun readflash_computes_start_sector_and_count_matches_reference() =
        verify("readflash_user_0x800") { it.readFlash(0, 0x400L, 0x800L) }

    @Test
    fun readflash_boot1_matches_reference() =
        verify("readflash_type1_0x4000") { it.readFlash(1, 0L, 0x4000L) }

    @Test
    fun readflash_rounds_partial_sector_up_matches_reference() =
        verify("readflash_odd_len") { it.readFlash(0, 0x100L, 0x350L) }

    @Test
    fun rpmb_uses_16_bit_operands_matches_reference() =
        verify("rpmb_0x200") { it.readRpmb(0L, 0x200L, false) { } }

    // ------------------------------------------------------------------
    // eMMC initialisation
    // ------------------------------------------------------------------

    @Test
    fun init_emmc_short_circuits_when_status_is_one_matches_reference() =
        verify("init_emmc_already", responder = { length ->
            if (length == 4) leU32(1) else ByteArray(length)
        }) { it.initEmmc() }

    @Test
    fun init_emmc_cold_boot_path_matches_reference() {
        var reads = 0
        verify("init_emmc_coldboot", responder = { length ->
            when {
                length != 4 -> ByteArray(length)
                ++reads == 1 -> leU32(0)
                else -> byteArrayOf(0xD1.toByte(), 0xD1.toByte(), 0xD1.toByte(), 0xD1.toByte())
            }
        }) { it.initEmmc() }
    }

    // ------------------------------------------------------------------
    // Coverage guard
    // ------------------------------------------------------------------

    /**
     * Fails if a recorded vector has no test above, so the golden file cannot
     * silently drift away from what is actually being verified.
     */
    @Test
    fun every_golden_vector_is_covered() {
        val recorded = golden.keys().asSequence().toSet()
        val covered = setOf(
            "read32_single", "read32_three", "write32_int", "write32_list",
            "jump", "cmd_C8_clearcache", "memread_16", "memread_0x250",
            "memwrite_bytes", "memwrite_odd_pad", "reboot",
            "readflash_user_0x800", "readflash_type1_0x4000", "readflash_odd_len",
            "init_emmc_already", "init_emmc_coldboot", "rpmb_0x200"
        )
        assertEquals("golden vectors must all be exercised", recorded, covered)
    }

    // ------------------------------------------------------------------
    // Harness
    // ------------------------------------------------------------------

    /**
     * Runs [op] against a recording transport and compares all three observable
     * properties with the reference recording.
     *
     * @param skipEmmcLatch leave the eMMC latch clear, for the init probes
     */
    private fun verify(
        name: String,
        responder: (Int) -> ByteArray = ACK_RESPONDER,
        skipEmmcLatch: Boolean = false,
        op: (BromProtocol) -> Unit
    ) {
        val expected = golden.getJSONObject(name)
        assertFalse(
            "$name was recorded as an error: ${expected.optString("error")}",
            expected.has("error")
        )

        val wire = RecordingWire(responder)
        val brom = BromProtocol(wire, NoopSleeper)
        if (!skipEmmcLatch) brom.assumeEmmcInited()

        op(brom)

        assertEquals("$name: transmitted bytes", expected.getString("tx"), wire.tx.lowercase())
        assertEquals(
            "$name: frame boundaries",
            expected.getJSONArray("tx_frames").toStringList(),
            wire.frames
        )
        assertEquals(
            "$name: requested read lengths",
            expected.getJSONArray("rx_requests").toIntList(),
            wire.readSizes
        )
    }

    private val golden: JSONObject by lazy {
        val stream = requireNotNull(javaClass.getResourceAsStream("/golden_vectors.json")) {
            "golden_vectors.json is missing from src/test/resources"
        }
        JSONObject(stream.bufferedReader().use { it.readText() })
    }

    private fun JSONArray.toStringList(): List<String> = (0 until length()).map { getString(it) }

    private fun JSONArray.toIntList(): List<Int> = (0 until length()).map { getInt(it) }

    private fun hex(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        /** BROM replies `D0D0D0D0` to writes and jumps; data reads are don't-care. */
        val ACK_RESPONDER: (Int) -> ByteArray = { length ->
            if (length == 4) {
                byteArrayOf(0xD0.toByte(), 0xD0.toByte(), 0xD0.toByte(), 0xD0.toByte())
            } else {
                ByteArray(length)
            }
        }
    }
}
