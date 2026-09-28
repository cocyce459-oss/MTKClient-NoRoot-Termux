package dev.cocyce.mtknative.engine

import dev.cocyce.mtknative.brom.WireFormat.leU32
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Verifies the preloader BRLYT layout decoder against synthetic boot1 images. */
class BrlytScannerTest {

    @Test
    fun locates_preloader_image_and_header() {
        val boot1 = buildBoot1(name = "preloader_mt6735.bin")
        val layout = BrlytScanner.scan(boot1)

        assertNotNull("a valid container should decode", layout)
        layout!!
        assertEquals(IMAGE_OFFSET, layout.imageOffset)
        assertEquals(IMAGE_LENGTH, layout.imageLength)
        assertEquals("preloader_mt6735.bin", layout.suggestedName)
    }

    @Test
    fun tolerates_missing_name_marker() {
        val boot1 = buildBoot1(name = null)
        val layout = BrlytScanner.scan(boot1)

        assertNotNull(layout)
        assertNull("no MTK_BLOADER_INFO means no suggested name", layout!!.suggestedName)
        assertEquals(IMAGE_OFFSET, layout.imageOffset)
    }

    @Test
    fun rejects_buffer_without_emmc_boot_magic() {
        val boot1 = buildBoot1()
        "NOT_BOOT!".toByteArray(Charsets.US_ASCII).copyInto(boot1, 0)
        assertNull(BrlytScanner.scan(boot1))
    }

    @Test
    fun rejects_all_zero_buffer() {
        assertNull(BrlytScanner.scan(ByteArray(0x4000)))
    }

    @Test
    fun rejects_broken_brlyt_pointer() {
        val boot1 = buildBoot1()
        // Point the BRLYT directory somewhere that holds no BRLYT magic.
        leU32(0x300).copyInto(boot1, 0x10)
        assertNull(BrlytScanner.scan(boot1))
    }

    @Test
    fun rejects_broken_mmm_magic() {
        val boot1 = buildBoot1()
        boot1[IMAGE_OFFSET] = 0x00
        assertNull(BrlytScanner.scan(boot1))
    }

    @Test
    fun rejects_truncated_buffer() {
        val boot1 = buildBoot1()
        // Cut off before the image header's length field at IMAGE_OFFSET + 0x20.
        assertNull(BrlytScanner.scan(boot1.copyOf(IMAGE_OFFSET + 0x10)))
    }

    private companion object {
        const val BRLYT_OFFSET = 0x200
        const val IMAGE_OFFSET = 0x400
        const val IMAGE_LENGTH = 0x1000
        const val MARKER_OFFSET = 0x440

        fun buildBoot1(name: String? = "preloader_test.bin"): ByteArray {
            val boot1 = ByteArray(0x600)

            "EMMC_BOOT".toByteArray(Charsets.US_ASCII).copyInto(boot1, 0)
            leU32(BRLYT_OFFSET).copyInto(boot1, 0x10)

            "BRLYT".toByteArray(Charsets.US_ASCII).copyInto(boot1, BRLYT_OFFSET)
            leU32(IMAGE_OFFSET).copyInto(boot1, BRLYT_OFFSET + 0x0C)

            byteArrayOf(0x4D, 0x4D, 0x4D, 0x01).copyInto(boot1, IMAGE_OFFSET)
            leU32(IMAGE_LENGTH).copyInto(boot1, IMAGE_OFFSET + 0x20)

            if (name != null) {
                "MTK_BLOADER_INFO".toByteArray(Charsets.US_ASCII).copyInto(boot1, MARKER_OFFSET)
                val encoded = name.toByteArray(Charsets.US_ASCII)
                encoded.copyInto(boot1, MARKER_OFFSET + 0x1B, 0, minOf(encoded.size, 0x22))
            }
            return boot1
        }
    }
}
