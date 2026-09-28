package dev.cocyce.mtknative.brom

/**
 * MediaTek BROM wire constants.
 *
 * BROM exposes a small command server on its USB bulk endpoints. Every request
 * begins with the 32-bit magic [MAGIC] sent **big-endian**, followed by an
 * opcode and its operands, also big-endian. Payload data and acknowledgements
 * are little-endian.
 *
 * These values are transcribed from the reference implementation
 * (`stage2.py` in bkerler/mtkclient) and are pinned byte-for-byte by
 * `app/src/test/resources/golden_vectors.json`.
 */
object BromOpcodes {

    /** Preamble on every BROM request frame. Transmitted big-endian. */
    const val MAGIC: Int = 0xF00DD00D.toInt()

    /** Write `len` bytes to a physical address. Data follows, 4-byte aligned. */
    const val OP_WRITE32: Int = 0x4000

    /** Jump the CPU to an address. */
    const val OP_JUMP: Int = 0x4001

    /** Read `len` bytes from a physical address. */
    const val OP_READ32: Int = 0x4002

    /** Flush / invalidate caches. */
    const val OP_CLEAR_CACHE: Int = 0x5000

    /** Reboot the device. No response is returned. */
    const val OP_REBOOT: Int = 0x3000

    /** Kick the watchdog timer. */
    const val OP_KICK_WDT: Int = 0x3001

    /** Read sectors from the currently selected eMMC partition. */
    const val OP_EMMC_READ: Int = 0x1000

    /** Select an eMMC hardware partition (user / boot1 / boot2 / rpmb). */
    const val OP_EMMC_SWITCH: Int = 0x1002

    /** Read 0x100-byte blocks (RPMB path). Operands are 16-bit, not 32-bit. */
    const val OP_BLOCK_READ: Int = 0x2000

    /** eMMC initialisation probe. */
    const val OP_EMMC_INIT: Int = 0x6000

    /** eMMC status probe; a response of 1 means already initialised. */
    const val OP_EMMC_STATUS: Int = 0x6001

    /** Acknowledgement returned after a successful write or jump. */
    val ACK_OK: ByteArray = byteArrayOf(0xD0.toByte(), 0xD0.toByte(), 0xD0.toByte(), 0xD0.toByte())

    /** Acknowledgement returned by the cold-boot eMMC init path. */
    val ACK_EMMC_READY: ByteArray = byteArrayOf(0xD1.toByte(), 0xD1.toByte(), 0xD1.toByte(), 0xD1.toByte())

    /** Largest single memread / memwrite chunk the reference implementation uses. */
    const val MEM_CHUNK: Int = 0x100

    /** eMMC sector size used by [OP_EMMC_READ]. */
    const val SECTOR_SIZE: Int = 0x200

    /** Block size used by the RPMB path ([OP_BLOCK_READ]). */
    const val RPMB_BLOCK: Int = 0x100

    /** eMMC hardware partition indices accepted by [OP_EMMC_SWITCH]. */
    object Partition {
        const val USER: Int = 0
        const val BOOT1: Int = 1
        const val BOOT2: Int = 2
        const val RPMB: Int = 3
    }
}
