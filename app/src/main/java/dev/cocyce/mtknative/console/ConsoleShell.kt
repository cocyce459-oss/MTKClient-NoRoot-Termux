package dev.cocyce.mtknative.console

import dev.cocyce.mtknative.brom.BromException
import dev.cocyce.mtknative.brom.BromOpcodes
import dev.cocyce.mtknative.brom.WireFormat
import dev.cocyce.mtknative.engine.MtkSession
import dev.cocyce.mtknative.gpt.GptTable
import java.io.File

/**
 * In-app command line that mirrors `mtk.py` syntax.
 *
 * The fork's README documented `printgpt`, `r`, `w`, `e` and friends, but the
 * bridge it shipped actually invoked `stage2.py`, which supports a completely
 * different and much smaller command set — so none of those ever worked. This
 * shell implements the documented surface against the native BROM engine, and
 * keeps `stage2.py`'s own commands (`rpmb`, `preloader`, `data`, `boot2`) too.
 *
 * Commands that need the Download Agent rather than BROM (`w`, `e`, `daa`,
 * `oem`) are rejected explicitly instead of silently doing nothing.
 */
class ConsoleShell(
    private val session: MtkSession,
    private val print: (String) -> Unit
) {

    /** Runs one line, catching and reporting any failure rather than crashing. */
    suspend fun execute(line: String) {
        val tokens = ConsoleParser.tokenize(line)
        if (tokens.isEmpty()) return

        val command = tokens[0].lowercase()
        val args = tokens.drop(1)
        try {
            when (command) {
                "help", "?" -> printHelp()
                "info", "device" -> cmdInfo()
                "printgpt", "gpt" -> cmdPrintGpt()
                "r", "read", "dump" -> cmdReadPartitions(args)
                "memread" -> cmdMemRead(args)
                "memwrite" -> cmdMemWrite(args)
                "preloader" -> cmdPreloader(args)
                "rpmb" -> cmdRpmb(args)
                "data" -> cmdData(args)
                "boot2" -> cmdBoot2(args)
                "reboot" -> cmdReboot()
                "ls", "dir" -> cmdListOutput()
                "connect" -> cmdConnect()
                "disconnect", "close" -> cmdDisconnect()
                "w", "write", "e", "erase", "daa", "oem", "seccfg" ->
                    unsupportedDa(command)
                else -> {
                    print("Unknown command: $command")
                    print("Type 'help' for the list of supported commands.")
                }
            }
        } catch (error: BromException) {
            print("ERROR: ${error.message}")
        } catch (error: IllegalArgumentException) {
            print("ERROR: ${error.message}")
        } catch (error: Exception) {
            print("ERROR: ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    // ------------------------------------------------------------------
    // Commands
    // ------------------------------------------------------------------

    private suspend fun cmdConnect() {
        val info = session.connect()
        print("Connected: ${info.summary}")
    }

    private fun cmdDisconnect() {
        session.disconnect()
        print("Disconnected.")
    }

    private suspend fun cmdInfo() {
        session.use { engine ->
            val info = engine.deviceInfo
            if (info == null) {
                print("Not connected.")
                return@use
            }
            print("Device   : ${info.label}")
            print("USB ID   : ${info.usbId}")
            print("Mode     : ${if (info.isBromMode) "BROM" else "Preloader"}")
            print("hwcode   : ${WireFormat.hex32(info.hwcode)}")
            print("SoC      : ${info.chip.displayName}")
            info.chip.daPayloadAddr?.let { print("DA addr  : ${WireFormat.hex64(it)}") }
            info.chip.meidAddr?.let { print("MEID addr: ${WireFormat.hex64(it)}") }
            info.chip.sejBase?.let { print("SEJ base : ${WireFormat.hex64(it)}") }
            print("Output   : ${session.outputDir.absolutePath}")
        }
    }

    private suspend fun cmdPrintGpt() {
        session.use { engine ->
            val table = engine.readGpt()
            print(table.formatTable())
        }
    }

    private suspend fun cmdReadPartitions(args: List<String>) {
        val positional = ConsoleParser.positional(args)
        if (positional.isEmpty()) {
            print("Usage: r <partition[,partition...]> [outfile...]")
            print("Example: r boot,vbmeta boot.img,vbmeta.img")
            return
        }
        val names = positional[0].split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val outNames = if (positional.size > 1) positional[1].split(",") else emptyList()

        session.use { engine ->
            val table = engine.readGpt()
            var missing = 0
            names.forEachIndexed { index, name ->
                val partition = table.byName(name)
                if (partition == null) {
                    print("Partition '$name' not found in GPT.")
                    missing++
                    return@forEachIndexed
                }
                val fileName = outNames.getOrNull(index)?.takeIf { it.isNotBlank() }
                    ?: defaultName(name)
                val file = File(session.outputDir, fileName)
                val written = file.outputStream().use { engine.dumpPartition(partition, table, it) }
                print("Saved '$name' -> ${file.absolutePath} (${GptTable.formatSize(written)})")
            }
            if (missing == names.size) {
                print("No partitions were dumped. Run 'printgpt' to list valid names.")
            }
        }
    }

    private suspend fun cmdMemRead(args: List<String>) {
        val positional = ConsoleParser.positional(args)
        if (positional.size < 2) {
            print("Usage: memread <address> <length> [--filename out.bin]")
            print("Example: memread 0x0 0x100")
            return
        }
        val start = ConsoleParser.parseNumber(positional[0])
        val length = ConsoleParser.parseNumber(positional[1]).toInt()
        val fileName = ConsoleParser.flagValue(args, "--filename")

        session.use { engine ->
            if (fileName != null) {
                val file = File(session.outputDir, fileName)
                file.outputStream().use { engine.memRead(start, length, it) }
                print("Saved ${GptTable.formatSize(length.toLong())} to ${file.absolutePath}")
            } else {
                val data = engine.memRead(start, length, null) ?: ByteArray(0)
                print("${WireFormat.hex32(start.toInt())}: ${WireFormat.toHex(data)}")
            }
        }
    }

    private suspend fun cmdMemWrite(args: List<String>) {
        val positional = ConsoleParser.positional(args)
        if (positional.size < 2) {
            print("Usage: memwrite <address> <hexstring|0xdword|filename>")
            print("Example: memwrite 0x0 0x12345678")
            print("Example: memwrite 0x200000 1122334455667788")
            return
        }
        val start = ConsoleParser.parseNumber(positional[0])
        val spec = positional[1]

        session.use { engine ->
            val data = resolveMemWriteData(spec)
            if (data == null) {
                print("No such file: $spec")
                return@use
            }
            val ok = engine.memWrite(start, data)
            print(
                if (ok) "Successfully wrote ${data.size} bytes to ${WireFormat.hex32(start.toInt())}."
                else "Failed to write to ${WireFormat.hex32(start.toInt())}."
            )
        }
    }

    /**
     * Resolves a `memwrite` payload: an existing file is read verbatim, otherwise
     * the spec is decoded by [ConsoleParser.decodeMemWritePayload] using the
     * reference's precedence.
     *
     * @return null when [spec] names a file that does not exist
     */
    private fun resolveMemWriteData(spec: String): ByteArray? {
        val asFile = File(spec).takeIf { it.isFile }
            ?: File(session.outputDir, spec).takeIf { it.isFile }
        if (asFile != null) return asFile.readBytes()
        if (ConsoleParser.looksLikePath(spec)) return null
        return ConsoleParser.decodeMemWritePayload(spec)
    }

    private suspend fun cmdPreloader(args: List<String>) {
        session.use { engine ->
            val result = engine.dumpPreloader { name -> session.openOutput(name) }
            print("Preloader image : ${result.imageName} (${GptTable.formatSize(result.imageBytes)})")
            print("Preloader header: hdr_${result.imageName} (${GptTable.formatSize(result.headerBytes)})")
            print("Saved under ${session.outputDir.absolutePath}")
        }
    }

    private suspend fun cmdRpmb(args: List<String>) {
        val start = ConsoleParser.flagValue(args, "--start")?.let { ConsoleParser.parseNumber(it) } ?: 0L
        val length = ConsoleParser.flagValue(args, "--length")?.let { ConsoleParser.parseNumber(it) } ?: 0L
        val reverse = args.contains("--reverse")
        val fileName = ConsoleParser.flagValue(args, "--filename") ?: "rpmb.bin"
        dumpRegion(BromOpcodes.Partition.RPMB, start, length, fileName, reverse, "rpmb")
    }

    private suspend fun cmdData(args: List<String>) {
        val start = ConsoleParser.flagValue(args, "--start")?.let { ConsoleParser.parseNumber(it) } ?: 0L
        val length = ConsoleParser.flagValue(args, "--length")?.let { ConsoleParser.parseNumber(it) } ?: DEFAULT_DUMP_LENGTH
        val fileName = ConsoleParser.flagValue(args, "--filename") ?: "data.bin"
        dumpRegion(BromOpcodes.Partition.USER, start, length, fileName, false, "data")
    }

    private suspend fun cmdBoot2(args: List<String>) {
        val start = ConsoleParser.flagValue(args, "--start")?.let { ConsoleParser.parseNumber(it) } ?: 0L
        val length = ConsoleParser.flagValue(args, "--length")?.let { ConsoleParser.parseNumber(it) } ?: 0x40000L
        val fileName = ConsoleParser.flagValue(args, "--filename") ?: "boot2.bin"
        dumpRegion(BromOpcodes.Partition.BOOT2, start, length, fileName, false, "boot2")
    }

    private suspend fun dumpRegion(
        type: Int,
        start: Long,
        length: Long,
        fileName: String,
        reverse: Boolean,
        label: String
    ) {
        session.use { engine ->
            val file = File(session.outputDir, fileName)
            val written = file.outputStream().use { out ->
                if (type == BromOpcodes.Partition.RPMB) {
                    engine.dumpRpmb(start, length, reverse, out)
                } else {
                    engine.dumpFlash(type, start, length, out, label)
                }
            }
            print("Saved $label -> ${file.absolutePath} (${GptTable.formatSize(written)})")
        }
    }

    private suspend fun cmdReboot() {
        session.use { engine ->
            engine.reboot()
            print("Reboot command sent.")
        }
    }

    private fun cmdListOutput() {
        val dir = session.outputDir
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (files.isEmpty()) {
            print("No files in ${dir.absolutePath}")
            return
        }
        print("Files in ${dir.absolutePath}:")
        files.forEach {
            print("  %-40s %s".format(it.name, GptTable.formatSize(it.length())))
        }
    }

    private fun unsupportedDa(command: String) {
        print("'$command' needs the MediaTek Download Agent, which this BROM-only build does not drive.")
        print("Available without a DA: printgpt, r, memread, memwrite, preloader, rpmb, data, boot2, reboot.")
        print("Rebuild with the full engine, or use upstream mtkclient on a desktop, for '$command'.")
    }

    private fun defaultName(partition: String): String = "$partition.img"

    private fun printHelp() {
        print(HELP)
    }

    private companion object {
        const val DEFAULT_DUMP_LENGTH = 32L * 0x200

        val HELP = """
MTKClient Native — BROM console

Connection
  connect                     Scan for a device and request USB permission
  info                        Show chip identity and register bases
  disconnect                  Release the USB interface

Read-only inspection
  printgpt                    Print the GUID partition table
  memread <addr> <len>        Dump memory as hex [--filename out.bin]
  r <part[,part]> [out]       Dump partitions to files (e.g. r boot,vbmeta boot.img,vbmeta.img)

Region dumps
  preloader                   Dump the preloader from boot1, via BRLYT
  rpmb                        Dump RPMB [--start] [--length] [--reverse] [--filename]
  data                        Dump the user area [--start] [--length] [--filename]
  boot2                       Dump boot2 [--start] [--length] [--filename]

Control
  memwrite <addr> <data>      Write memory; data is hex, a 0x dword, or a,b,c dwords
  reboot                      Reboot the target
  ls                          List files written so far

Numbers accept decimal or 0x hex, matching upstream mtkclient.

WARNING: memwrite and reboot modify the device. Everything else here is read-only.
DA-based operations (w, e, daa, oem) are not available in this BROM-only build.
""".trimIndent()
    }
}
