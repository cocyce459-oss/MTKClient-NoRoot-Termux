package dev.cocyce.mtknative.brom

import dev.cocyce.mtknative.brom.BromOpcodes.ACK_OK
import dev.cocyce.mtknative.brom.BromOpcodes.MAGIC
import dev.cocyce.mtknative.brom.BromOpcodes.MEM_CHUNK
import dev.cocyce.mtknative.brom.BromOpcodes.OP_BLOCK_READ
import dev.cocyce.mtknative.brom.BromOpcodes.OP_CLEAR_CACHE
import dev.cocyce.mtknative.brom.BromOpcodes.OP_EMMC_INIT
import dev.cocyce.mtknative.brom.BromOpcodes.OP_EMMC_READ
import dev.cocyce.mtknative.brom.BromOpcodes.OP_EMMC_STATUS
import dev.cocyce.mtknative.brom.BromOpcodes.OP_EMMC_SWITCH
import dev.cocyce.mtknative.brom.BromOpcodes.OP_JUMP
import dev.cocyce.mtknative.brom.BromOpcodes.OP_READ32
import dev.cocyce.mtknative.brom.BromOpcodes.OP_REBOOT
import dev.cocyce.mtknative.brom.BromOpcodes.OP_WRITE32
import dev.cocyce.mtknative.brom.BromOpcodes.RPMB_BLOCK
import dev.cocyce.mtknative.brom.BromOpcodes.SECTOR_SIZE
import dev.cocyce.mtknative.brom.WireFormat.beU16
import dev.cocyce.mtknative.brom.WireFormat.beU32
import dev.cocyce.mtknative.brom.WireFormat.leU32
import dev.cocyce.mtknative.brom.WireFormat.padTo
import java.io.ByteArrayOutputStream

/**
 * Native port of the MediaTek BROM command server.
 *
 * This is a line-by-line port of the `Stage2` protocol class in bkerler/mtkclient's
 * `stage2.py`. Frame layout, chunking, padding and acknowledgement handling are
 * reproduced exactly, because BROM is unforgiving about all four.
 *
 * Every byte sequence emitted here is pinned by `src/test/resources/golden_vectors.json`,
 * which was recorded by instrumenting the reference implementation itself.
 */
class BromProtocol(
    private val wire: Wire,
    private val sleeper: Sleeper = ThreadSleeper
) {

    /** Mirrors the reference's `emmc_inited` latch. */
    var emmcInited: Boolean = false
        private set

    /**
     * Declares the eMMC controller already initialised, so the next flash read
     * skips the probe (and its 2 second settle).
     *
     * Useful after a successful [initEmmc], and used by the wire-vector tests to
     * isolate the flash-read frames exactly as the reference harness does.
     */
    fun assumeEmmcInited() {
        emmcInited = true
    }

    // ------------------------------------------------------------------
    // Register access
    // ------------------------------------------------------------------

    /** Reads [dwords] consecutive 32-bit words starting at [address]. */
    fun read32(address: Int, dwords: Int): IntArray {
        val result = IntArray(dwords)
        for (pos in 0 until dwords) {
            wire.write(beU32(MAGIC))
            wire.write(beU32(OP_READ32))
            wire.write(beU32(address + pos * 4))
            wire.write(beU32(4))
            result[pos] = leU32(wire.read(4))
        }
        return result
    }

    fun read32(address: Int): Int = read32(address, 1)[0]

    /** Writes [values] as consecutive 32-bit words starting at [address]. */
    fun write32(address: Int, values: IntArray): Boolean {
        for (pos in values.indices) {
            wire.write(beU32(MAGIC))
            wire.write(beU32(OP_WRITE32))
            wire.write(beU32(address + pos * 4))
            wire.write(beU32(4))
            wire.write(WireFormat.leU32(values[pos]))
            if (!wire.read(4).contentEquals(ACK_OK)) return false
        }
        return true
    }

    fun write32(address: Int, value: Int): Boolean = write32(address, intArrayOf(value))

    /** Jumps execution to [address]. The reference waits 5 s for the device to settle. */
    fun jump(address: Int): Boolean {
        wire.write(beU32(MAGIC))
        wire.write(beU32(OP_JUMP))
        wire.write(beU32(address))
        sleeper.sleep(JUMP_SETTLE_MS)
        return wire.read(4).contentEquals(ACK_OK)
    }

    /** Flushes caches (the reference's `cmd_C8`). */
    fun clearCache(): Boolean {
        wire.write(beU32(MAGIC))
        wire.write(beU32(OP_CLEAR_CACHE))
        return wire.read(4).contentEquals(ACK_OK)
    }

    /** Reboots the device. BROM returns nothing for this opcode. */
    fun reboot() {
        wire.write(beU32(MAGIC))
        wire.write(beU32(OP_REBOOT))
    }

    // ------------------------------------------------------------------
    // Bulk memory access
    // ------------------------------------------------------------------

    /**
     * Reads [length] bytes of physical memory from [start] in [MEM_CHUNK]-sized requests.
     *
     * @param sink receives each chunk as it arrives; when null the result is buffered
     *             and returned instead.
     */
    fun memRead(start: Int, length: Int, sink: ((ByteArray) -> Unit)? = null): ByteArray? {
        var bytesToRead = length
        var pos = 0
        val buffer = if (sink == null) ByteArrayOutputStream() else null
        while (bytesToRead > 0) {
            val size = minOf(bytesToRead, MEM_CHUNK)
            wire.write(beU32(MAGIC))
            wire.write(beU32(OP_READ32))
            wire.write(beU32(start + pos))
            wire.write(beU32(size))
            val chunk = wire.read(size)
            if (sink != null) sink(chunk) else buffer!!.write(chunk, 0, chunk.size)
            bytesToRead -= size
            pos += size
        }
        return buffer?.toByteArray()
    }

    /**
     * Writes [data] to physical memory at [start] in [MEM_CHUNK]-sized frames.
     *
     * Each chunk is zero-padded to a 4-byte boundary, and a single acknowledgement
     * is read only after the final frame — matching the reference exactly.
     */
    fun memWrite(start: Int, data: ByteArray): Boolean {
        var bytesToWrite = data.size
        var pos = 0
        while (bytesToWrite > 0) {
            val size = minOf(bytesToWrite, MEM_CHUNK)
            wire.write(beU32(MAGIC))
            wire.write(beU32(OP_WRITE32))
            wire.write(beU32(start + pos))
            wire.write(beU32(size))
            wire.write(padTo(data.copyOfRange(pos, pos + size), 4))
            bytesToWrite -= size
            pos += size
        }
        return wire.read(4).contentEquals(ACK_OK)
    }

    // ------------------------------------------------------------------
    // eMMC
    // ------------------------------------------------------------------

    /**
     * Probes / initialises the eMMC controller.
     *
     * Note the deliberately asymmetric control flow, preserved from the reference:
     * a status response of `1` means the controller is already up and nothing
     * further is sent; otherwise an init command follows and a `D1D1D1D1` reply
     * signals success, while any other reply leaves the latch set.
     */
    fun initEmmc(): Boolean {
        wire.write(beU32(MAGIC))
        wire.write(beU32(OP_EMMC_STATUS))
        if (leU32(wire.read(4)) != 1) {
            wire.write(beU32(MAGIC))
            wire.write(beU32(OP_EMMC_INIT))
            sleeper.sleep(EMMC_SETTLE_MS)
            if (leU32(wire.read(4)) == EMMC_READY_WORD) return true
            emmcInited = true
        }
        return false
    }

    /**
     * Reads raw sectors from an eMMC hardware partition.
     *
     * @param type hardware partition index, see [BromOpcodes.Partition]
     * @param start byte offset; it is rounded down to a sector boundary internally
     * @param length number of bytes wanted
     * @param sink receives sector-sized chunks when streaming to storage
     * @return the exact start..start+length window when [sink] is null, else null
     */
    fun readFlash(
        type: Int,
        start: Long,
        length: Long,
        sink: ((ByteArray) -> Unit)? = null
    ): ByteArray? {
        if (!emmcInited) initEmmc()

        val sectors = sectorsFor(length, SECTOR_SIZE)
        val startSector = start / SECTOR_SIZE

        wire.write(beU32(MAGIC))
        wire.write(beU32(OP_EMMC_SWITCH))
        wire.write(beU32(type))

        wire.write(beU32(MAGIC))
        wire.write(beU32(OP_EMMC_READ))
        wire.write(beU32(startSector))
        wire.write(beU32(sectors))

        var bytesToRead = length
        val buffer = if (sink == null) ByteArrayOutputStream() else null
        for (sector in 0 until sectors) {
            val chunk = wire.read(SECTOR_SIZE)
            if (chunk.size != SECTOR_SIZE) {
                throw BromException(
                    "Short read on sector $sector: got ${chunk.size} bytes, expected $SECTOR_SIZE"
                )
            }
            val size = minOf(bytesToRead, chunk.size.toLong()).toInt()
            val trimmed = if (size == chunk.size) chunk else chunk.copyOf(size)
            if (sink != null) sink(trimmed) else buffer!!.write(trimmed, 0, trimmed.size)
            bytesToRead -= size
        }
        if (sink != null) return null

        val raw = buffer!!.toByteArray()
        val offset = (start % SECTOR_SIZE).toInt()
        if (offset >= raw.size) return ByteArray(0)
        val end = minOf(raw.size.toLong(), offset + length).toInt()
        return raw.copyOfRange(offset, end)
    }

    /**
     * Reads the RPMB region in [RPMB_BLOCK]-sized blocks.
     *
     * RPMB uses 16-bit start/count operands rather than the 32-bit ones used
     * everywhere else in the protocol, so requests are capped at 0xFFFF blocks
     * and continued one block at a time beyond that.
     *
     * @param reverse byte-reverses each block, as the reference's `--reverse` does
     */
    fun readRpmb(
        start: Long,
        length: Long,
        reverse: Boolean,
        sink: (ByteArray) -> Unit
    ) {
        if (!emmcInited) initEmmc()

        var block = if (start == 0L) 0L else start / RPMB_BLOCK
        if (block > 0xFFFFL) block = 0xFFFFL

        val blocks = if (length == 0L) {
            (RPMB_DEFAULT_TOTAL / RPMB_BLOCK)
        } else {
            sectorsFor(length, RPMB_BLOCK)
        }

        wire.write(beU32(MAGIC))
        wire.write(beU32(OP_EMMC_SWITCH))
        wire.write(beU32(1))

        var bytesToRead = blocks.toLong() * RPMB_BLOCK
        var count = blocks
        if (count > 0xFFFF) count = 0xFFFF

        wire.write(beU32(MAGIC))
        wire.write(beU32(OP_BLOCK_READ))
        wire.write(beU16(block.toInt()))
        wire.write(beU16(count))

        var lastBlock = block.toInt()
        for (index in 0 until count) {
            var chunk = wire.read(RPMB_BLOCK)
            if (reverse) chunk = chunk.reversedArray()
            if (chunk.size != RPMB_BLOCK) {
                throw BromException("Short read on RPMB block $index: got ${chunk.size} bytes")
            }
            val size = minOf(bytesToRead, chunk.size.toLong()).toInt()
            sink(if (size == chunk.size) chunk else chunk.copyOf(size))
            bytesToRead -= size
            lastBlock = block.toInt() + index
        }

        // Large dumps spill past the 16-bit count field; continue block by block.
        while (bytesToRead > 0) {
            wire.write(beU32(MAGIC))
            wire.write(beU32(OP_BLOCK_READ))
            wire.write(beU16(lastBlock + 1))
            wire.write(beU16(1))
            val chunk = wire.read(RPMB_BLOCK)
            val size = minOf(bytesToRead, chunk.size.toLong()).toInt()
            sink(if (size == chunk.size) chunk else chunk.copyOf(size))
            bytesToRead -= size
            lastBlock += 1
        }
    }

    /** Rounds [length] up to a whole number of [unit]-sized blocks. */
    private fun sectorsFor(length: Long, unit: Int): Int {
        var sectors = length / unit
        if (length % unit != 0L) sectors += 1
        return sectors.toInt()
    }

    private companion object {
        /** The reference sleeps 5 s after a jump before reading the ack. */
        const val JUMP_SETTLE_MS = 5000L

        /** The reference sleeps 2 s after the cold-boot eMMC init command. */
        const val EMMC_SETTLE_MS = 2000L

        /** `0xD1D1D1D1` as a signed Kotlin Int, the eMMC "ready" reply. */
        val EMMC_READY_WORD: Int = 0xD1D1D1D1.toInt()

        /** RPMB dump size used when the caller passes length 0 (16 MiB). */
        const val RPMB_DEFAULT_TOTAL = 16L * 1024 * 1024
    }
}
