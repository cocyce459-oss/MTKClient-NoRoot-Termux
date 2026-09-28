package dev.cocyce.mtknative.engine

import dev.cocyce.mtknative.brom.BromException
import dev.cocyce.mtknative.brom.BromOpcodes
import dev.cocyce.mtknative.brom.BromProtocol
import dev.cocyce.mtknative.brom.Sleeper
import dev.cocyce.mtknative.brom.ThreadSleeper
import dev.cocyce.mtknative.brom.WireFormat
import dev.cocyce.mtknative.chip.ChipConfig
import dev.cocyce.mtknative.chip.ChipDatabase
import dev.cocyce.mtknative.gpt.GptParser
import dev.cocyce.mtknative.gpt.GptPartition
import dev.cocyce.mtknative.gpt.GptTable
import dev.cocyce.mtknative.usb.MtkUsbTransport
import java.io.Closeable
import java.io.OutputStream

/** Identity of the device we are talking to, resolved during [MtkEngine.connect]. */
data class DeviceInfo(
    val usbId: String,
    val label: String,
    val isBromMode: Boolean,
    val hwcode: Int,
    val chip: ChipConfig
) {
    val summary: String
        get() = "${chip.displayName} ($usbId) — ${if (isBromMode) "BROM" else "Preloader"} mode"
}

/** Outcome of a preloader dump. */
data class PreloaderResult(
    val imageName: String,
    val imageBytes: Long,
    val headerBytes: Long
)

/**
 * High-level MediaTek operations built on the BROM protocol.
 *
 * This is the native counterpart of `stage2.py`'s command implementations. It is
 * deliberately free of Android view types: it talks to a [MtkUsbTransport], emits
 * text through an [EngineListener], and writes bytes to an [OutputStream]. That
 * keeps the whole engine unit-testable on a desktop JVM.
 */
class MtkEngine(
    private val transport: MtkUsbTransport,
    private val listener: EngineListener,
    sleeper: Sleeper = ThreadSleeper
) : Closeable {

    val brom: BromProtocol = BromProtocol(transport, sleeper)

    var deviceInfo: DeviceInfo? = null
        private set

    // ------------------------------------------------------------------
    // Connection
    // ------------------------------------------------------------------

    /**
     * Opens the transport and identifies the SoC.
     *
     * Mirrors `Stage2.preinit()`: the hwcode register at [ChipDatabase.HwcodeAddress]
     * is read first and used to look the chip up.
     */
    fun connect(): DeviceInfo {
        if (!transport.isConnected) transport.connect()

        val descriptor = transport.descriptor
        val hwcode = try {
            brom.read32(ChipDatabase.HwcodeAddress)
        } catch (error: BromException) {
            listener.onLog(LogLevel.ERROR, "Could not read hwcode: ${error.message}")
            throw BromException(
                "Device did not answer the hwcode probe. It may not be in BROM mode, " +
                    "or the watchdog may have already fired — power-cycle and retry.",
                error
            )
        }

        val chip = ChipDatabase.findOrUnknown(hwcode)
        if (ChipDatabase.find(hwcode) == null) {
            listener.onLog(
                LogLevel.WARN,
                "hwcode ${WireFormat.hex32(hwcode)} is not in the ${ChipDatabase.size}-entry chip table"
            )
        }

        val info = DeviceInfo(
            usbId = transport.usbId,
            label = descriptor?.label ?: "Unknown MediaTek device",
            isBromMode = descriptor?.isBrom ?: true,
            hwcode = hwcode,
            chip = chip
        )
        deviceInfo = info
        listener.onLog(LogLevel.INFO, "Connected: ${info.summary}")
        listener.onLog(LogLevel.INFO, "hwcode ${WireFormat.hex32(hwcode)} -> ${chip.displayName}")
        return info
    }

    // ------------------------------------------------------------------
    // Partition table
    // ------------------------------------------------------------------

    /**
     * Reads and decodes the GUID Partition Table from the user area.
     *
     * The upstream `stage2.py` has no `printgpt` at all — the fork's README
     * documented that command but it never existed on this code path. It is
     * implemented here for real: probe enough sectors to cover both 512- and
     * 4096-byte geometries, then re-read if the entry array extends further.
     */
    fun readGpt(): GptTable {
        listener.onLog(LogLevel.INFO, "Reading partition table…")
        var data = brom.readFlash(BromOpcodes.Partition.USER, 0L, GPT_PROBE_BYTES)
            ?: throw BromException("Partition table probe returned no data")

        var table = GptParser.parse(data)
        if (table != null) {
            val header = table.header
            val needed = header.partEntryStartLba * table.sectorSize +
                header.numPartEntries.toLong() * header.partEntrySize
            if (needed > data.size) {
                listener.onLog(
                    LogLevel.DEBUG,
                    "Entry array needs $needed bytes, re-reading (had ${data.size})"
                )
                val wider = brom.readFlash(BromOpcodes.Partition.USER, 0L, needed)
                if (wider != null) {
                    data = wider
                    table = GptParser.parse(data)
                }
            }
        }

        val resolved = table ?: throw BromException(
            "No 'EFI PART' signature found at LBA1 for either 512- or 4096-byte sectors. " +
                "The device may use a raw MBR layout, or eMMC may not be initialised."
        )
        listener.onLog(LogLevel.INFO, "Found ${resolved.partitions.size} partitions")
        return resolved
    }

    // ------------------------------------------------------------------
    // Flash reads
    // ------------------------------------------------------------------

    /** Streams a GPT partition to [out], reporting progress. */
    fun dumpPartition(
        partition: GptPartition,
        table: GptTable,
        out: OutputStream
    ): Long {
        val offset = partition.byteOffset(table.sectorSize)
        val length = partition.byteLength(table.sectorSize)
        listener.onLog(
            LogLevel.INFO,
            "Dumping '%s': offset ${WireFormat.hex64(offset)}, length ${GptTable.formatSize(length)}"
        )
        return dumpFlash(BromOpcodes.Partition.USER, offset, length, out, partition.name)
    }

    /**
     * Streams raw flash sectors to [out].
     *
     * @param type eMMC hardware partition, see [BromOpcodes.Partition]
     */
    fun dumpFlash(
        type: Int,
        start: Long,
        length: Long,
        out: OutputStream,
        label: String = "flash"
    ): Long {
        var written = 0L
        var lastReported = 0L
        brom.readFlash(type, start, length) { chunk ->
            out.write(chunk)
            written += chunk.size
            if (written - lastReported >= PROGRESS_STEP || written >= length) {
                listener.onProgress(label, written, length)
                lastReported = written
            }
        }
        out.flush()
        listener.onLog(LogLevel.INFO, "$label: wrote ${GptTable.formatSize(written)}")
        return written
    }

    /** Dumps the RPMB region, the reference's `rpmb` command. */
    fun dumpRpmb(start: Long, length: Long, reverse: Boolean, out: OutputStream): Long {
        listener.onLog(LogLevel.INFO, "Reading RPMB…")
        var written = 0L
        brom.readRpmb(start, length, reverse) { chunk ->
            out.write(chunk)
            written += chunk.size
        }
        out.flush()
        listener.onLog(LogLevel.INFO, "rpmb: wrote ${GptTable.formatSize(written)}")
        return written
    }

    /**
     * Dumps the preloader from eMMC boot1, the reference's `preloader` command.
     *
     * @param openStream maps a suggested file name to a sink, so the caller
     *                   decides whether that is app storage or a SAF document
     */
    fun dumpPreloader(openStream: (String) -> OutputStream): PreloaderResult {
        listener.onLog(LogLevel.INFO, "Reading preloader container from boot1…")
        val probe = brom.readFlash(BromOpcodes.Partition.BOOT1, 0L, PRELOADER_PROBE_BYTES.toLong())
            ?: throw BromException("Could not read boot1")

        val layout = BrlytScanner.scan(probe)
            ?: throw BromException(
                "boot1 does not contain an EMMC_BOOT/BRLYT container. " +
                    "This device may use NAND/UFS, or the preloader is not at offset 0."
            )

        val total = layout.imageOffset.toLong() + layout.imageLength
        val data = brom.readFlash(BromOpcodes.Partition.BOOT1, 0L, total)
            ?: throw BromException("Could not read the full preloader region")

        val name = layout.suggestedName ?: DEFAULT_PRELOADER_NAME
        openStream(name).use { it.write(data, layout.imageOffset, layout.imageLength) }
        openStream("hdr_$name").use { it.write(data, 0, layout.imageOffset) }

        listener.onLog(
            LogLevel.INFO,
            "Preloader '%s': ${GptTable.formatSize(layout.imageLength.toLong())} " +
                "(+ ${GptTable.formatSize(layout.imageOffset.toLong())} header)"
        )
        return PreloaderResult(name, layout.imageLength.toLong(), layout.imageOffset.toLong())
    }

    // ------------------------------------------------------------------
    // Memory access
    // ------------------------------------------------------------------

    /**
     * Reads physical memory.
     *
     * @param out when non-null the bytes are streamed there and null is returned
     */
    fun memRead(start: Long, length: Int, out: OutputStream?): ByteArray? {
        if (out == null) {
            val data = brom.memRead(start.toInt(), length)
            listener.onLog(
                LogLevel.INFO,
                "${WireFormat.hex32(start.toInt())}: ${WireFormat.toHex(data ?: ByteArray(0))}"
            )
            return data
        }
        var total = 0
        brom.memRead(start.toInt(), length) { chunk ->
            out.write(chunk)
            total += chunk.size
        }
        out.flush()
        listener.onLog(LogLevel.INFO, "memread: wrote $total bytes")
        return null
    }

    /** Writes physical memory. */
    fun memWrite(start: Long, data: ByteArray): Boolean {
        val ok = brom.memWrite(start.toInt(), data)
        listener.onLog(
            if (ok) LogLevel.INFO else LogLevel.ERROR,
            if (ok) "Wrote ${data.size} bytes to ${WireFormat.hex32(start.toInt())}"
            else "Failed to write to ${WireFormat.hex32(start.toInt())}"
        )
        return ok
    }

    // ------------------------------------------------------------------
    // Control
    // ------------------------------------------------------------------

    /** Reboots the target. */
    fun reboot() {
        listener.onLog(LogLevel.INFO, "Rebooting device…")
        brom.reboot()
    }

    override fun close() {
        runCatching { transport.close() }
        deviceInfo = null
    }

    private companion object {
        /** Enough to cover LBA1 plus a full entry array for both sector sizes. */
        const val GPT_PROBE_BYTES = 0x10000L

        /** The reference probes the first 0x4000 bytes of boot1. */
        const val PRELOADER_PROBE_BYTES = 0x4000

        const val DEFAULT_PRELOADER_NAME = "preloader.bin"

        /** Report progress at most every 4 MiB to avoid flooding the UI. */
        const val PROGRESS_STEP = 4L * 1024 * 1024
    }
}
