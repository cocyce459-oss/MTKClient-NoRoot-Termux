package dev.cocyce.mtknative.ui

import android.app.Application
import android.hardware.usb.UsbDevice
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import dev.cocyce.mtknative.R
import dev.cocyce.mtknative.brom.BromException
import dev.cocyce.mtknative.brom.WireFormat
import dev.cocyce.mtknative.console.ConsoleParser
import dev.cocyce.mtknative.console.ConsoleShell
import dev.cocyce.mtknative.engine.DeviceInfo
import dev.cocyce.mtknative.engine.EngineListener
import dev.cocyce.mtknative.engine.LogLevel
import dev.cocyce.mtknative.engine.MtkSession
import dev.cocyce.mtknative.gpt.GptPartition
import dev.cocyce.mtknative.gpt.GptTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the single [MtkSession] and publishes everything the fragments render.
 *
 * Fragments never touch USB directly: they observe this class and call its
 * action methods. Two invariants hold the design together.
 *
 * **1. Work runs inside [guard], never in a nested coroutine.** `guard` launches
 * exactly one coroutine on [Dispatchers.IO] and keeps [busy] true until it
 * completes. Launching a second coroutine inside the block would return
 * immediately and flip `busy` back to false while the transfer was still
 * running, so the UI would re-enable buttons mid-dump.
 *
 * **2. [busy] is not the gate.** `MutableLiveData.postValue` is asynchronous, so
 * a LiveData flag cannot make concurrent operations mutually exclusive — two
 * clicks in the same frame can both read `false`. Exclusion is done with an
 * [AtomicBoolean.compareAndSet]; the LiveData only mirrors it for rendering.
 */
class MtkViewModel(private val app: Application) : AndroidViewModel(app) {

    /**
     * Declared before [session] deliberately.
     *
     * Kotlin evaluates property initializers in declaration order, so a listener
     * defined further down the class would still be null when `MtkSession`
     * captured it — the app would then crash on the first engine callback. The
     * `_progress` field it touches is only read when a callback fires, long after
     * construction, so its later declaration is safe.
     */
    private val listener = object : EngineListener {
        override fun onLog(level: LogLevel, message: String) {
            this@MtkViewModel.onLog(level, message)
        }

        // Parameter order must match EngineListener.onProgress(label, done, total)
        // exactly: Kotlin matches overrides by signature, so a reordered list
        // silently "overrides nothing" rather than adapting.
        override fun onProgress(label: String, done: Long, total: Long) {
            _progress.postValue(if (total <= 0L) null else Progress(done, total, label))
        }
    }

    val session: MtkSession = MtkSession(app, listener)

    /** Console interpreter sharing this session, so both tabs drive one device. */
    private val shell = ConsoleShell(session) { line -> appendConsole(line) }

    private val _state = MutableLiveData<ConnectionState>(ConnectionState.Idle)
    val state: LiveData<ConnectionState> = _state

    private val _device = MutableLiveData<DeviceInfo?>(null)
    val device: LiveData<DeviceInfo?> = _device

    private val _logs = MutableLiveData<List<LogLine>>(emptyList())
    val logs: LiveData<List<LogLine>> = _logs

    private val _gpt = MutableLiveData<GptTable?>(null)
    val gpt: LiveData<GptTable?> = _gpt

    private val _partitions = MutableLiveData<List<GptPartition>>(emptyList())
    val partitions: LiveData<List<GptPartition>> = _partitions

    private val _sectorSize = MutableLiveData(DEFAULT_SECTOR_SIZE)
    val sectorSize: LiveData<Int> = _sectorSize

    private val _busy = MutableLiveData(false)
    val busy: LiveData<Boolean> = _busy

    private val _progress = MutableLiveData<Progress?>(null)
    val progress: LiveData<Progress?> = _progress

    /** Console transcript, already joined, because it binds straight to a TextView. */
    private val _console = MutableLiveData("")
    val console: LiveData<String> = _console

    private val logBuffer = ArrayDeque<LogLine>()
    private val consoleBuffer = ArrayDeque<String>()
    private val running = AtomicBoolean(false)
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    init {
        appendConsole(app.getString(R.string.console_welcome))
        // Surfaces the system permission dialog in the status bar instead of
        // leaving the user staring at "Scanning" with no idea why.
        session.onPermissionWait = { deviceId ->
            _state.postValue(ConnectionState.AwaitingPermission(deviceId))
        }
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    fun connect() = guard {
        _state.postValue(ConnectionState.Scanning)
        runConnect { session.connect() }
    }

    fun connectToDevice(device: UsbDevice) = guard {
        // The plug-in path usually grants permission implicitly, but a device
        // the user picked from a list may still need the dialog, so say so.
        _state.postValue(
            if (session.permissions.hasPermission(device)) ConnectionState.Scanning
            else ConnectionState.AwaitingPermission(usbIdOf(device))
        )
        runConnect { session.connectTo(device) }
    }

    fun disconnect() = guard {
        session.disconnect()
        _state.postValue(ConnectionState.Idle)
        _device.postValue(null)
        _gpt.postValue(null)
        _partitions.postValue(emptyList())
        _progress.postValue(null)
    }

    fun reboot() = guard {
        session.use { engine -> engine.reboot() }
        _state.postValue(ConnectionState.Idle)
    }

    /** Reads and decodes the GPT, then publishes partitions and sector size. */
    fun refreshPartitions() = guard {
        session.use { engine -> publishTable(engine.readGpt()) }
    }

    /**
     * Dumps [partition] to `<outputDir>/<name>.bin`.
     *
     * Uses the cached table for geometry when one is loaded, and re-reads it
     * otherwise, since the engine needs the table to resolve byte offsets.
     */
    fun dumpPartition(partition: GptPartition) = guard {
        session.use { engine ->
            val table = _gpt.value ?: engine.readGpt()
            val target = resolveDumpFile(partition.name)
            val written = target.outputStream().use { out ->
                engine.dumpPartition(partition, table, out)
            }
            publishTable(table)
            onLog(LogLevel.INFO, app.getString(
                R.string.dumped_bytes, GptTable.formatSize(written), target.absolutePath))
        }
    }

    /**
     * Memory read driven by the two text fields, so both arrive unparsed.
     *
     * Numbers follow `stage2.py`'s `getint()` convention via
     * [ConsoleParser.parseNumber]: decimal first, then base 16.
     */
    fun memRead(address: String, length: String) = guard {
        val start: Long
        val count: Long
        try {
            start = ConsoleParser.parseNumber(address.trim())
            count = ConsoleParser.parseNumber(length.trim())
        } catch (error: NumberFormatException) {
            onLog(LogLevel.ERROR, app.getString(R.string.memread_invalid))
            return@guard
        }
        if (count <= 0L) {
            onLog(LogLevel.ERROR, app.getString(R.string.memread_invalid))
            return@guard
        }
        session.use { engine ->
            val target = File(session.outputDir, "memread_${WireFormat.hex32(start.toInt())}.bin")
            target.outputStream().use { out -> engine.memRead(start, count.toInt(), out) }
            onLog(LogLevel.INFO, app.getString(
                R.string.dumped_bytes, GptTable.formatSize(count), target.absolutePath))
        }
    }

    /** Prints the dump directory into the log; the files live in app storage. */
    fun openOutputFolder() {
        onLog(LogLevel.INFO, app.getString(R.string.output_dir, session.outputDir.absolutePath))
    }

    fun clearLogs() {
        logBuffer.clear()
        _logs.value = emptyList()
    }

    fun clearConsole() {
        consoleBuffer.clear()
        _console.value = ""
    }

    /** Runs one console line; output arrives through [console]. */
    fun submitConsole(line: String) = guard { shell.execute(line) }

    /** Appends a line to the log pane. Safe to call from any thread. */
    fun onLog(level: LogLevel, message: String) {
        logBuffer.addLast(LogLine(clock.format(Date()), level, message))
        while (logBuffer.size > MAX_LOG_LINES) logBuffer.removeFirst()
        _logs.postValue(logBuffer.toList())
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /**
     * Serialises operations onto a single background coroutine.
     *
     * @return true when the work was started, false if one is already in flight
     */
    private fun guard(block: suspend () -> Unit): Boolean {
        if (!running.compareAndSet(false, true)) {
            onLog(LogLevel.WARN, app.getString(R.string.busy_wait))
            return false
        }
        _busy.value = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (error: Throwable) {
                onLog(LogLevel.ERROR, describeError(error))
            } finally {
                running.set(false)
                _busy.value = false
                _progress.postValue(null)
            }
        }
        return true
    }

    private fun publishTable(table: GptTable) {
        _gpt.postValue(table)
        _partitions.postValue(table.partitions)
        _sectorSize.postValue(table.sectorSize)
        onLog(LogLevel.INFO, app.getString(R.string.found_partitions, table.partitions.size))
    }

    /** Shared success/failure handling for both connect paths. */
    private suspend fun runConnect(connect: suspend () -> Unit) {
        try {
            connect()
            afterConnect()
        } catch (error: Throwable) {
            _state.postValue(ConnectionState.Failed(describeError(error)))
            throw error
        }
    }

    private fun usbIdOf(device: UsbDevice): String =
        "%04X:%04X".format(device.vendorId, device.productId)

    private fun afterConnect() {
        val info = session.deviceInfo
        _device.postValue(info)
        if (info != null) _state.postValue(ConnectionState.Connected(info))
    }

    private fun resolveDumpFile(name: String): File {
        val safe = name.trim().replace(UNSAFE_FILENAME, "_").ifBlank { "partition" }
        return File(session.outputDir, "$safe.bin").also { it.parentFile?.mkdirs() }
    }

    private fun describeError(error: Throwable): String = when (error) {
        is BromException -> error.message ?: "BROM error"
        is SecurityException -> app.getString(R.string.permission_denied_hint)
        is IllegalStateException -> error.message ?: app.getString(R.string.not_connected)
        else -> error.message ?: error.javaClass.simpleName
    }

    private fun appendConsole(line: String) {
        consoleBuffer.addLast(line)
        while (consoleBuffer.size > MAX_CONSOLE_LINES) consoleBuffer.removeFirst()
        _console.postValue(consoleBuffer.joinToString("\n"))
    }

    companion object {
        private const val MAX_LOG_LINES = 500
        private const val MAX_CONSOLE_LINES = 500

        /** Assumed until a GPT is read; 4Kn devices republish the real value. */
        private const val DEFAULT_SECTOR_SIZE = 0x200

        private val UNSAFE_FILENAME = Regex("[^A-Za-z0-9._-]")
    }
}

/**
 * Connection lifecycle as rendered by the status bar and device tab.
 *
 * `MainActivity.renderState` switches over this exhaustively as an expression,
 * so every member here must be handled there — adding a state means updating
 * both files.
 */
sealed class ConnectionState {
    /** Nothing plugged in, or deliberately disconnected. */
    data object Idle : ConnectionState()

    /** Polling for a MediaTek device in BROM mode. */
    data object Scanning : ConnectionState()

    /** A device was found and the system permission dialog is on screen. */
    data class AwaitingPermission(val deviceId: String) : ConnectionState()

    data class Connected(val info: DeviceInfo) : ConnectionState()

    data class Failed(val reason: String) : ConnectionState()
}

data class LogLine(val timestamp: String, val level: LogLevel, val message: String)

/**
 * Bulk-transfer progress for the status bar.
 *
 * [current]/[total] are byte counts. [text] and [fraction] exist so the view layer
 * renders progress without reimplementing the arithmetic — `MainActivity` binds
 * `progress.text` straight to a TextView and drives the indicator from `fraction`.
 */
data class Progress(val current: Long, val total: Long, val label: String) {
    val fraction: Float
        get() = if (total <= 0L) 0f else (current.toFloat() / total.toFloat()).coerceIn(0f, 1f)

    /** One-line rendering; falls back to the bare label when total is unknown. */
    val text: String
        get() = if (total > 0L) "$label — ${(fraction * 100).toInt()}%" else label
}
