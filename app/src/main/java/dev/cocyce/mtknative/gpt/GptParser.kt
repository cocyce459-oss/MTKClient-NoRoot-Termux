package dev.cocyce.mtknative.gpt

import dev.cocyce.mtknative.brom.WireFormat

/** One entry from the GUID Partition Table. */
data class GptPartition(
    val name: String,
    val typeName: String,
    val typeGuid: String,
    val uniqueGuid: String,
    val firstLba: Long,
    val lastLba: Long,
    val flags: Long
) {
    val sectorCount: Long get() = lastLba - firstLba + 1

    fun byteOffset(sectorSize: Int): Long = firstLba * sectorSize

    fun byteLength(sectorSize: Int): Long = sectorCount * sectorSize

    /** A/B slot state, as decoded by the reference's `AB_PARTITION_ATTR_*` flags. */
    val isActiveSlot: Boolean get() = (flags shr 2) and 0x1L == 1L
    val isBootSuccessful: Boolean get() = (flags shr 6) and 0x1L == 1L
    val isUnbootable: Boolean get() = (flags shr 7) and 0x1L == 1L
}

/** The GPT header found at LBA1. */
data class GptHeader(
    val revision: Int,
    val headerSize: Int,
    val crc32: Int,
    val currentLba: Long,
    val backupLba: Long,
    val firstUsableLba: Long,
    val lastUsableLba: Long,
    val diskGuid: String,
    val partEntryStartLba: Long,
    val numPartEntries: Int,
    val partEntrySize: Int
)

/** A parsed partition table plus the sector size it was decoded with. */
class GptTable(
    val header: GptHeader,
    val partitions: List<GptPartition>,
    val sectorSize: Int
) {
    /** Reference computes `last_usable_lba + 34` as the total sector count. */
    val totalSectors: Long get() = header.lastUsableLba + 34

    val totalBytes: Long get() = totalSectors * sectorSize

    fun byName(name: String): GptPartition? =
        partitions.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** Resolves a comma-separated partition list, e.g. `boot,vbmeta`. */
    fun resolveNames(names: List<String>): List<GptPartition> =
        names.mapNotNull { raw -> byName(raw.trim()) }

    fun formatTable(): String {
        val builder = StringBuilder()
        builder.append(
            "%-4s %-32s %-12s %-12s %-10s %-18s%n".format(
                "#", "Name", "Start LBA", "End LBA", "Size", "Type"
            )
        )
        builder.append("-".repeat(94)).append('\n')
        partitions.forEachIndexed { index, part ->
            builder.append(
                "%-4d %-32s %-12d %-12d %-10s %-18s%n".format(
                    index,
                    part.name.ifBlank { "(unnamed)" },
                    part.firstLba,
                    part.lastLba,
                    formatSize(part.byteLength(sectorSize)),
                    part.typeName
                )
            )
        }
        builder.append("-".repeat(94)).append('\n')
        builder.append(
            "%d partitions, sector size %d, disk %s (%d sectors)%n".format(
                partitions.size, sectorSize, formatSize(totalBytes), totalSectors
            )
        )
        return builder.toString()
    }

    companion object {
        fun formatSize(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            val units = listOf("KiB", "MiB", "GiB", "TiB")
            var value = bytes.toDouble()
            var unitIndex = -1
            while (value >= 1024.0 && unitIndex < units.size - 1) {
                value /= 1024.0
                unitIndex++
            }
            return "%.2f %s".format(value, units[unitIndex])
        }
    }
}

/**
 * GUID Partition Table decoder.
 *
 * Ported from `mtkclient/Library/Partitions/gpt.py`. Like the reference, both
 * 512-byte and 4096-byte sector geometries are attempted, and an entry whose
 * unique GUID is all zeroes terminates the list.
 *
 * This is pure logic with no Android or I/O dependencies, so it can be unit
 * tested against a synthetic disk image on the JVM.
 */
object GptParser {

    private const val SIGNATURE = "EFI PART"

    /** Bytes of defined header fields: 8+4+4+4+4+8+8+8+8+16+8+4+4. */
    private const val HEADER_FIELDS = 0x58

    private const val ENTRY_SIZE_DEFAULT = 128

    /** Sector geometries to probe, in the same order as the reference. */
    val SECTOR_SIZES = intArrayOf(0x200, 0x1000)

    /**
     * Parses a GPT from a buffer holding at least the first sectors of the disk.
     *
     * @return the decoded table, or null if no `EFI PART` signature was found at
     *         LBA1 for either sector geometry.
     */
    fun parse(data: ByteArray): GptTable? {
        for (sectorSize in SECTOR_SIZES) {
            val headerOffset = sectorSize
            if (data.size < headerOffset + HEADER_FIELDS) continue
            if (String(data, headerOffset, 8, Charsets.US_ASCII) != SIGNATURE) continue

            val header = parseHeader(data, headerOffset) ?: continue
            val partitions = parseEntries(data, header, sectorSize)
            return GptTable(header, partitions, sectorSize)
        }
        return null
    }

    private fun parseHeader(data: ByteArray, offset: Int): GptHeader? {
        val revision = WireFormat.leU32(data, offset + 8)
        if (revision != 0x10000) return null
        return GptHeader(
            revision = revision,
            headerSize = WireFormat.leU32(data, offset + 12),
            crc32 = WireFormat.leU32(data, offset + 16),
            currentLba = WireFormat.leU64(data, offset + 24),
            backupLba = WireFormat.leU64(data, offset + 32),
            firstUsableLba = WireFormat.leU64(data, offset + 40),
            lastUsableLba = WireFormat.leU64(data, offset + 48),
            diskGuid = formatGuid(data, offset + 56),
            partEntryStartLba = WireFormat.leU64(data, offset + 72),
            numPartEntries = WireFormat.leU32(data, offset + 80),
            partEntrySize = WireFormat.leU32(data, offset + 84)
        )
    }

    private fun parseEntries(data: ByteArray, header: GptHeader, sectorSize: Int): List<GptPartition> {
        val entrySize = if (header.partEntrySize > 0) header.partEntrySize else ENTRY_SIZE_DEFAULT
        var start = header.partEntryStartLba * sectorSize
        if (start <= 0) start = sectorSize.toLong() * 2

        val result = ArrayList<GptPartition>()
        for (index in 0 until header.numPartEntries) {
            val offset = (start + index.toLong() * entrySize).toInt()
            // Stop when the entry lies past what the caller read; the reference
            // would fault here, we simply report the partitions we do have.
            if (offset < 0 || offset + entrySize > data.size) break
            if (isAllZero(data, offset + 16, 16)) break

            val typeGuid = formatGuid(data, offset)
            val uniqueGuid = formatGuid(data, offset + 16)
            val firstLba = WireFormat.leU64(data, offset + 32)
            val lastLba = WireFormat.leU64(data, offset + 40)
            val flags = WireFormat.leU64(data, offset + 48)
            val name = decodeUtf16Le(data, offset + 56, minOf(72, entrySize - 56))

            val typeKey = WireFormat.leU32(data, offset)
            val typeName = EFI_TYPES[typeKey.toLong() and 0xFFFFFFFFL]
                ?: WireFormat.hex32(typeKey)

            // The reference skips entries typed EFI_UNUSED.
            if (typeKey == 0x00000000) continue

            result.add(
                GptPartition(
                    name = name,
                    typeName = typeName,
                    typeGuid = typeGuid,
                    uniqueGuid = uniqueGuid,
                    firstLba = firstLba,
                    lastLba = lastLba,
                    flags = flags
                )
            )
        }
        return result
    }

    private fun isAllZero(data: ByteArray, offset: Int, length: Int): Boolean {
        for (index in offset until offset + length) {
            if (data[index].toInt() != 0) return false
        }
        return true
    }

    /**
     * Formats a 16-byte GUID using the reference's mixed-endian convention:
     * the first three groups are little-endian integers, the tail is raw hex.
     */
    private fun formatGuid(data: ByteArray, offset: Int): String {
        val g1 = WireFormat.leU32(data, offset)
        val g2 = WireFormat.leU16(data, offset + 4)
        val g3 = WireFormat.leU16(data, offset + 6)
        val g4 = WireFormat.leU16(data, offset + 8)
        val g5 = WireFormat.toHex(data.copyOfRange(offset + 10, offset + 16)).lowercase()
        return "%08x-%04x-%04x-%04x-%s".format(g1, g2, g3, g4, g5)
    }

    private fun decodeUtf16Le(data: ByteArray, offset: Int, length: Int): String {
        val raw = String(data, offset, length, Charsets.UTF_16LE)
        val nul = raw.indexOf('\u0000')
        return (if (nul >= 0) raw.substring(0, nul) else raw).trim()
    }

    /** Partition type GUIDs, keyed by their first little-endian dword. */
    val EFI_TYPES: Map<Long, String> = linkedMapOf(
        0x00000000L to "EFI_UNUSED",
        0x024DEE41L to "EFI_MBR",
        0xC12A7328L to "EFI_SYSTEM",
        0x21686148L to "EFI_BIOS_BOOT",
        0xD3BFE2DEL to "EFI_IFFS",
        0xF4019732L to "EFI_SONY_BOOT",
        0xBFBFAFE7L to "EFI_LENOVO_BOOT",
        0xE3C9E316L to "EFI_MSR",
        0xEBD0A0A2L to "EFI_BASIC_DATA",
        0x5808C8AAL to "EFI_LDM_META",
        0xAF9B60A0L to "EFI_LDM",
        0xDE94BBA4L to "EFI_RECOVERY",
        0x37AFFC90L to "EFI_GPFS",
        0xE75CAF8FL to "EFI_STORAGE_SPACES",
        0x75894C1EL to "EFI_HPUX_DATA",
        0xE2A1E728L to "EFI_HPUX_SERVICE",
        0x0FC63DAFL to "EFI_LINUX_DATA",
        0xA19D880FL to "EFI_LINUX_RAID",
        0x44479540L to "EFI_LINUX_ROOT32",
        0x4F68BCE3L to "EFI_LINUX_ROOT64",
        0x69DAD710L to "EFI_LINUX_ROOT_ARM32",
        0xB921B045L to "EFI_LINUX_ROOT_ARM64",
        0x0657FD6DL to "EFI_LINUX_SWAP",
        0xE6D6D379L to "EFI_LINUX_LVM",
        0x933AC7E1L to "EFI_LINUX_HOME",
        0x3B8F8425L to "EFI_LINUX_SRV",
        0x7FFEC5C9L to "EFI_LINUX_DM_CRYPT",
        0xCA7D7CCBL to "EFI_LINUX_LUKS",
        0x8DA63339L to "EFI_LINUX_RESERVED",
        0x83BD6B9DL to "EFI_FREEBSD_BOOT",
        0x516E7CB4L to "EFI_FREEBSD_DATA",
        0x516E7CB5L to "EFI_FREEBSD_SWAP",
        0x516E7CB6L to "EFI_FREEBSD_UFS",
        0x516E7CB8L to "EFI_FREEBSD_VINUM",
        0x516E7CBAL to "EFI_FREEBSD_ZFS",
        0x48465300L to "EFI_OSX_HFS",
        0x55465300L to "EFI_OSX_UFS",
        0x6A898CC3L to "EFI_OSX_ZFS",
        0x52414944L to "EFI_OSX_RAID",
        0x426F6F74L to "EFI_OSX_RECOVERY",
        0x4C616265L to "EFI_OSX_LABEL",
        0x5265636FL to "EFI_OSX_TV_RECOVERY",
        0x53746F72L to "EFI_OSX_CORE_STORAGE",
        0x6A82CB45L to "EFI_SOLARIS_BOOT",
        0x6A85CF4DL to "EFI_SOLARIS_ROOT",
        0x6A87C46FL to "EFI_SOLARIS_SWAP",
        0x6A8B642BL to "EFI_SOLARIS_BACKUP",
        0x6A8EF2E9L to "EFI_SOLARIS_VAR",
        0x6A90BA39L to "EFI_SOLARIS_HOME",
        0x6A9283A5L to "EFI_SOLARIS_ALTERNATE",
        0x49F48D32L to "EFI_NETBSD_SWAP",
        0x49F48D5AL to "EFI_NETBSD_FFS",
        0x49F48D82L to "EFI_NETBSD_LFS",
        0x49F48DAAL to "EFI_NETBSD_RAID",
        0x2DB519C4L to "EFI_NETBSD_CONCAT",
        0x2DB519ECL to "EFI_NETBSD_ENCRYPT",
        0xFE3A2A5DL to "EFI_CHROMEOS_KERNEL",
        0x3CB8E202L to "EFI_CHROMEOS_ROOTFS",
        0x2E0A753DL to "EFI_CHROMEOS_FUTURE",
        0x42465331L to "EFI_HAIKU",
        0x85D5E45EL to "EFI_MIDNIGHTBSD_BOOT",
        0x85D5E45AL to "EFI_MIDNIGHTBSD_DATA",
        0x85D5E45BL to "EFI_MIDNIGHTBSD_SWAP",
        0x85D5E45CL to "EFI_MIDNIGHTBSD_VINUM",
        0x85D5E45DL to "EFI_MIDNIGHTBSD_ZFS",
        0x45B0969EL to "EFI_CEPH_JOURNAL",
        0x4FBD7E29L to "EFI_CEPH_OSD",
        0x89C57F98L to "EFI_CEPH_CREATE",
        0x824CC7A0L to "EFI_OPENBSD",
        0xCEF5A9ADL to "EFI_QNX",
        0xC91818F9L to "EFI_PLAN9",
        0x9D275380L to "EFI_VMWARE_VMKCORE",
        0xAA31E02AL to "EFI_VMWARE_VMFS",
        0x9198EFFCL to "EFI_VMWARE_RESERVED"
    )
}
