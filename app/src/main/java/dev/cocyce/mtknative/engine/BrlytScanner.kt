package dev.cocyce.mtknative.engine

import dev.cocyce.mtknative.brom.WireFormat

/**
 * Decodes the MediaTek preloader "BRLYT" boot layout.
 *
 * Ported from `stage2.py`'s `preloader()`. The preloader lives in eMMC boot1 and
 * starts with an `EMMC_BOOT` header that points at a `BRLYT` directory, which in
 * turn points at the real `MMM\x01` image. Pure logic, so it is unit-testable
 * against a synthetic boot1 image.
 */
object BrlytScanner {

    private val MAGIC_EMMC_BOOT = "EMMC_BOOT".toByteArray(Charsets.US_ASCII)
    private val MAGIC_BRLYT = "BRLYT".toByteArray(Charsets.US_ASCII)
    private val MAGIC_MMM = byteArrayOf(0x4D, 0x4D, 0x4D, 0x01)
    private val MARKER_BLOADER_INFO = "MTK_BLOADER_INFO".toByteArray(Charsets.US_ASCII)

    /** A located preloader image inside a boot1 dump. */
    data class Layout(
        /** Byte offset of the preloader image within boot1. */
        val imageOffset: Int,
        /** Byte length of the preloader image. */
        val imageLength: Int,
        /** File name advertised by the `MTK_BLOADER_INFO` marker, if present. */
        val suggestedName: String?
    )

    /**
     * Inspects a boot1 buffer (the reference reads the first 0x4000 bytes).
     *
     * @return the layout, or null when the buffer is not a recognisable MediaTek
     *         preloader container.
     */
    fun scan(boot1: ByteArray): Layout? {
        if (!startsWith(boot1, 0, MAGIC_EMMC_BOOT)) return null

        if (boot1.size < 0x14) return null
        val brlytOffset = WireFormat.leU32(boot1, 0x10)
        if (brlytOffset < 0 || brlytOffset + 5 > boot1.size) return null
        if (!startsWith(boot1, brlytOffset, MAGIC_BRLYT)) return null

        val infoOffset = brlytOffset + 0x0C
        if (infoOffset + 4 > boot1.size) return null
        val imageOffset = WireFormat.leU32(boot1, infoOffset)
        if (imageOffset < 0 || imageOffset + 0x24 > boot1.size) return null
        if (!startsWith(boot1, imageOffset, MAGIC_MMM)) return null

        val imageLength = WireFormat.leU32(boot1, imageOffset + 0x20)
        if (imageLength <= 0) return null

        return Layout(
            imageOffset = imageOffset,
            imageLength = imageLength,
            suggestedName = findSuggestedName(boot1)
        )
    }

    private fun findSuggestedName(buffer: ByteArray): String? {
        val index = indexOf(buffer, MARKER_BLOADER_INFO)
        if (index < 0) return null
        val from = index + 0x1B
        val to = index + 0x3D
        if (to > buffer.size) return null
        val raw = String(buffer, from, to - from, Charsets.US_ASCII)
        val trimmed = raw.trimEnd('\u0000').trim()
        return trimmed.ifBlank { null }
    }

    private fun startsWith(data: ByteArray, offset: Int, prefix: ByteArray): Boolean {
        if (offset < 0 || offset + prefix.size > data.size) return false
        for (index in prefix.indices) {
            if (data[offset + index] != prefix[index]) return false
        }
        return true
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (start in 0..haystack.size - needle.size) {
            for (index in needle.indices) {
                if (haystack[start + index] != needle[index]) continue@outer
            }
            return start
        }
        return -1
    }
}
