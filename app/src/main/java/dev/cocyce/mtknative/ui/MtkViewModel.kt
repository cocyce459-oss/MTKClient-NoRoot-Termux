package dev.cocyce.mtknative.ui

import android.app.Application
import android.hardware.usb.UsbDevice
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import dev.cocyce.mtknative.R
import dev.cocyce.mtknative.brom.BromException
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Date
import java.util.Locale

/** One line in the on-screen log. */
data class LogLine(
    val timestamp: String,
    val level: LogLevel,
    val message: String
)

/** Connection lifecycle surfaced to the UI. */
sealed class ConnectionState {
    object Idle : ConnectionState()
    object Scanning : ConnectionState()
    data class AwaitingPermission(val deviceId: String) : ConnectionState()
    data class Connected(val info: DeviceInfo) : ConnectionState()
    data class Failed(val reason: String) : ConnectionState()
}

/** Bulk transfer progress. */
data class Progress(val label: String, val done: Long, val total: Long) {
    val fraction: Float
        get() = if (total <= 0) 0f else (done.toFloat() / total.toFloat()).coerceIn(0f, 1f)

    val text: String
        get() = if (total <= 0) "$label: ${GptTable.formatSize(done)}"
        else "$label: ${GptTable.formatSize(done)} / ${GptTable.formatSize(total)}"
}

/**
 * Bridges the blocking engine to observable UI state.
 *
 * The ViewModel is itself the [EngineListener], so every log line and progress
 * tick from the protocol layer lands here regardless of whether the GUI or the
 * console tab started the operation.
 */
class MtkViewModel(application: Application) : AndroidViewModel(application), EngineListener {

    val session = MtkSession(application, this)

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val logBuffer = ArrayList<LogLine>()
    private val consoleBuffer = StringBuilder()

    init {
        consoleBuffer.append(application.getString(R.string.console_welcome))
    }

    private val _logs = MutableLiveData<List<LogLine>>(emptyList())
    val logs: LiveData<List<LogLine>> = _logs

    private val _state = MutableLiveData<ConnectionState>(ConnectionState.Idle)
    val state: LiveData<ConnectionState> = _state

    private val _partitions = MutableLiveData<List<GptPartition>>(emptyList())
    val partitions: LiveData<List<GptPartition>> = _partitions

    private val _sectorSize = MutableLiveData(0x200)
    val sectorSize: LiveData<Int> = _sectorSize

    private val _progress = MutableLiveData<Progress?>(null)
    val progress: LiveData<Progress?> = _progress

    private val _console = MutableLiveData(application.getString(R.string.console_welcome))
    val console: LiveData<String> = _console

    private val _busy = MutableLiveData(false)
    val busy: LiveData<Boolean> = _busy

    /**
     * Gate flag. Kept separate from [_busy] because LiveData updates are posted
     * asynchronously and would let two rapid calls both see "not busy".
     */
    private val running = AtomicBoolean(false)

    /** True when the engine is running an operation; used to disable buttons. */
    val isBusy: Boolean get() = running.get()

    private val shell = ConsoleShell(session) { line -> appendConsole(line) }

    // ------------------------------------------------------------------
    // EngineListener (called from IO threads)
    // ------------------------------------------------------------------

    override fun onLog(level: LogLevel, message: String) {
        val line = LogLine(timeFormat.format(Date()), level, message)
        synchronized(logBuffer) {
            logBuffer.add(line)
            while (logBuffer.size > MAX_LOG_LINES) logBuffer.removeAt(0)
            _logs.postValue(logBuffer.toList())
        }
    }

    override fun onProgress(label: String, done: Long, total: Long) {
        _progress.postValue(Progress(label, done, total))
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    fun connect() = guard {
        _state.postValue(ConnectionState.Scanning)
        try {
            val info = withContext(Dispatchers.IO) { session.connect() }
            _state.postValue(ConnectionState.Connected(info))
            onLog(LogLevel.INFO, "Ready. Run printgpt to inspect the partition table.")
        } catch (error: Exception) {
            _state.postValue(ConnectionState.Failed(describe(error)))
            onLog(LogLevel.ERROR, describe(error))
        }
    }

    /** Connects to a device handed to us by the USB attach intent. */
    fun connectToDevice(device: UsbDevice) = guard {
        _state.postValue(ConnectionState.Scanning)
        try {
            val info = withContext(Dispatchers.IO) { session.connectTo(device) }
            _state.postValue(ConnectionState.Connected(info))
            onLog(LogLevel.INFO, "Attached device ready: ${info.summary}")
            // Already inside guard(), so call the unguarded loader directly —
            // refreshPartitions() would bounce off the busy flag.
            loadPartitions()
        } catch (error: Exception) {
            _state.postValue(ConnectionState.Failed(describe(error)))
            onLog(LogLevel.ERROR, describe(error))
        }
    }

    fun disconnect() {
        session.disconnect()
        _state.postValue(ConnectionState.Idle)
        _partitions.postValue(emptyList())
        _progress.postValue(null)
        onLog(LogLevel.INFO, "Disconnected.")
    }

    fun refreshPartitions() = guard { loadPartitions() }

    /** Unguarded partition reload; callers must already hold the busy gate. */
    private suspend fun loadPartitions() {
        runCatching {
            withContext(Dispatchers.IO) { session.use { it.readGpt() } }
        }.onSuccess { table ->
            _sectorSize.postValue(table.sectorSize)
            _partitions.postValue(table.partitions)
        }.onFailure { error ->
            onLog(LogLevel.ERROR, describe(error))
        }
    }

    fun dumpPartition(partition: GptPartition) = guard {
        runCatching {
            withContext(Dispatchers.IO) {
                session.use { engine ->
                    // Read the live table once: sector size and LBAs must agree
                    // with the device, not with a stale value cached in the UI.
                    val table = engine.readGpt()
                    val file = File(session.outputDir, "${partition.name}.img")
                    file.outputStream().use { engine.dumpPartition(partition, table, it) }
                    file.absolutePath
                }
            }
        }.onSuccess { path ->
            onLog(LogLevel.INFO, "Saved ${partition.name} to $path")
        }.onFailure { error ->
            onLog(LogLevel.ERROR, describe(error))
        }.also { _progress.postValue(null) }
    }

    fun reboot() = guard {
        runCatching {
            withContext(Dispatchers.IO) { session.use { it.reboot() } }
        }.onFailure { error -> onLog(LogLevel.ERROR, describe(error)) }
    }

    fun memRead(address: String, length: String) = guard {
        runCatching {
            withContext(Dispatchers.IO) {
                session.use { engine ->
                    engine.memRead(parseNumber(address), parseNumber(length).toInt(), null)
                }
            }
        }.onFailure { error -> onLog(LogLevel.ERROR, describe(error)) }
    }

    fun submitConsole(command: String) {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return
        appendConsole("\$ $trimmed")
        guard {
            withContext(Dispatchers.IO) { shell.execute(trimmed) }
        }
    }

    fun clearLogs() {
        synchronized(logBuffer) {
            logBuffer.clear()
            _logs.postValue(emptyList())
        }
    }

    fun clearConsole() {
        consoleBuffer.setLength(0)
        _console.postValue("")
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /** Serialises operations so two blocking USB sessions never overlap. */
    private fun guard(block: suspend () -> Unit) {
        if (!running.compareAndSet(false, true)) {
            onLog(LogLevel.WARN, "Busy — waiting for the current operation to finish.")
            return
        }
        _busy.value = true
        viewModelScope.launch {
            try {
                block()
            } catch (error: Exception) {
                onLog(LogLevel.ERROR, describe(error))
            } finally {
                running.set(false)
                _busy.value = false
            }
        }
    }

    private fun appendConsole(text: String) {
        synchronized(consoleBuffer) {
            consoleBuffer.append(text).append('\n')
            val overflow = consoleBuffer.length - MAX_CONSOLE_CHARS
            if (overflow > 0) consoleBuffer.delete(0, overflow)
            _console.postValue(consoleBuffer.toString())
        }
    }

    private fun describe(error: Throwable): String = when (error) {
        is BromException -> error.message ?: "BROM protocol error"
        else -> "${error.javaClass.simpleName}: ${error.message}"
    }

    private fun parseNumber(value: String): Long {
        val trimmed = value.trim()
        trimmed.toLongOrNull()?.let { return it }
        val hex = trimmed.removePrefix("0x").removePrefix("0X")
        return hex.toLongOrNull(16) ?: throw IllegalArgumentException("Not a number: '$value'")
    }

    private companion object {
        const val MAX_LOG_LINES = 500
        const val MAX_CONSOLE_CHARS = 200_000
    }
}
