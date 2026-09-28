package dev.cocyce.mtknative.gpt

import dev.cocyce.mtknative.brom.WireFormat.leU32
import dev.cocyce.mtknative.brom.WireFormat.leU64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the GPT decoder against synthetic disk images with a known layout.
 *
 * The parser is pure logic, so these run on a desktop JVM with no device.
 */
class GptParserTest {

    @Test
    fun parses_512_byte_sector_table() {
        val image = buildImage(sectorSize = 0x200, entryStartLba = 2L)
        val table = GptParser.parse(image)

        assertNotNull("512-byte GPT should parse", table)
        table!!
        assertEquals(0x200, table.sectorSize)
        assertEquals(0x10000, table.header.revision)
        assertEquals(1L, table.header.currentLba)
        assertEquals(34L, table.header.firstUsableLba)
        assertEquals(2014L, table.header.lastUsableLba)
        assertEquals(4, table.header.numPartEntries)
        assertEquals(128, table.header.partEntrySize)

        // The fourth entry has an all-zero GUID and must terminate the list.
        assertEquals(3, table.partitions.size)
        assertEquals(listOf("boot", "vbmeta", "userdata"), table.partitions.map { it.name })
    }

    @Test
    fun decodes_partition_geometry() {
        val table = GptParser.parse(buildImage(sectorSize = 0x200, entryStartLba = 2L))!!
        val boot = table.byName("boot")!!

        assertEquals(34L, boot.firstLba)
        assertEquals(1057L, boot.lastLba)
        assertEquals(1024L, boot.sectorCount)
        assertEquals("EFI_BASIC_DATA", boot.typeName)
        assertEquals(34L * 0x200, boot.byteOffset(0x200))
        assertEquals(1024L * 0x200, boot.byteLength(0x200))
    }

    @Test
    fun decodes_ab_slot_flags() {
        val table = GptParser.parse(buildImage(sectorSize = 0x200, entryStartLba = 2L))!!
        val userdata = table.byName("userdata")!!

        // flags bit 2 is AB_PARTITION_ATTR_SLOT_ACTIVE in the reference.
        assertTrue("userdata should be the active slot", userdata.isActiveSlot)
        assertFalse("boot should not be flagged active", table.byName("boot")!!.isActiveSlot)
    }

    @Test
    fun parses_4096_byte_sector_table() {
        val table = GptParser.parse(buildImage(sectorSize = 0x1000, entryStartLba = 2L))

        assertNotNull("4096-byte GPT should parse", table)
        assertEquals(0x1000, table!!.sectorSize)
        assertEquals(3, table.partitions.size)
        assertEquals("boot", table.partitions[0].name)
    }

    @Test
    fun returns_null_when_no_signature_present() {
        assertNull(GptParser.parse(ByteArray(0x4000)))
    }

    @Test
    fun returns_null_when_buffer_is_too_short() {
        val image = buildImage(sectorSize = 0x200, entryStartLba = 2L)
        assertNull(GptParser.parse(image.copyOf(0x210)))
    }

    @Test
    fun resolves_comma_separated_partition_lists() {
        val table = GptParser.parse(buildImage(sectorSize = 0x200, entryStartLba = 2L))!!
        val resolved = table.resolveNames(listOf("boot", "missing", "vbmeta"))

        assertEquals(2, resolved.size)
        assertEquals("boot", resolved[0].name)
        assertEquals("vbmeta", resolved[1].name)
    }

    @Test
    fun formats_total_disk_size() {
        val table = GptParser.parse(buildImage(sectorSize = 0x200, entryStartLba = 2L))!!
        // reference: totalsectors = last_usable_lba + 34
        assertEquals(2048L, table.totalSectors)
        assertEquals(2048L * 0x200, table.totalBytes)
    }

    @Test
    fun human_readable_sizes_are_scaled() {
        assertEquals("512 B", GptTable.formatSize(512))
        assertEquals("1.00 KiB", GptTable.formatSize(1024))
        assertEquals("1.50 MiB", GptTable.formatSize(1536 * 1024))
        assertEquals("2.00 GiB", GptTable.formatSize(2L * 1024 * 1024 * 1024))
    }

    // ------------------------------------------------------------------
    // Image builder
    // ------------------------------------------------------------------

    private fun buildImage(sectorSize: Int, entryStartLba: Long): ByteArray {
        val entryCount = 4
        val entrySize = 128
        val totalSectors = entryStartLba + 40
        // ByteArray() takes an Int, and totalSectors is Long because it derives
        // from entryStartLba. The entry offsets below already narrow with toInt().
        val image = ByteArray((totalSectors * sectorSize).coerceAtLeast(0x10000L).toInt())

        // --- LBA1: GPT header ---
        val header = sectorSize
        "EFI PART".toByteArray(Charsets.US_ASCII).copyInto(image, header)
        leU32(0x10000).copyInto(image, header + 8)          // revision
        leU32(92).copyInto(image, header + 12)              // header size
        leU32(0).copyInto(image, header + 16)               // crc32
        leU32(0).copyInto(image, header + 20)               // reserved
        leU64(1L).copyInto(image, header + 24)              // current LBA
        leU64(2047L).copyInto(image, header + 32)           // backup LBA
        leU64(34L).copyInto(image, header + 40)             // first usable
        leU64(2014L).copyInto(image, header + 48)           // last usable
        guidBytes(0x11111111).copyInto(image, header + 56)  // disk GUID
        leU64(entryStartLba).copyInto(image, header + 72)   // entry start LBA
        leU32(entryCount).copyInto(image, header + 80)
        leU32(entrySize).copyInto(image, header + 84)

        // --- Entry array ---
        val entries = (entryStartLba * sectorSize).toInt()
        writeEntry(image, entries + 0 * entrySize, 0xEBD0A0A2.toInt(), 0xAAAA0001.toInt(),
            34L, 1057L, 0L, "boot")
        writeEntry(image, entries + 1 * entrySize, 0xEBD0A0A2.toInt(), 0xAAAA0002.toInt(),
            1058L, 1089L, 0L, "vbmeta")
        writeEntry(image, entries + 2 * entrySize, 0x0FC63DAF, 0xAAAA0003.toInt(),
            1090L, 2014L, 0x4L, "userdata")
        // Entry 3 stays all zeroes: the terminator.

        return image
    }

    private fun writeEntry(
        image: ByteArray,
        offset: Int,
        typeDword: Int,
        uniqueDword: Int,
        firstLba: Long,
        lastLba: Long,
        flags: Long,
        name: String
    ) {
        guidBytes(typeDword).copyInto(image, offset)
        guidBytes(uniqueDword).copyInto(image, offset + 16)
        leU64(firstLba).copyInto(image, offset + 32)
        leU64(lastLba).copyInto(image, offset + 40)
        leU64(flags).copyInto(image, offset + 48)
        val encoded = name.toByteArray(Charsets.UTF_16LE)
        encoded.copyInto(image, offset + 56, 0, minOf(encoded.size, 72))
    }

    /** A 16-byte GUID whose first little-endian dword is [dword]. */
    private fun guidBytes(dword: Int): ByteArray =
        leU32(dword) + ByteArray(12) { (it + 1).toByte() }
}
