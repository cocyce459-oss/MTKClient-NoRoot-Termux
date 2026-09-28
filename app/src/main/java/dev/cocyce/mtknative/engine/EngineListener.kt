package dev.cocyce.mtknative.engine

/** Severity for engine log lines surfaced in the UI. */
enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/**
 * Sink for engine output.
 *
 * Both the Material GUI and the console tab implement this, so the engine stays
 * completely free of any view-layer dependency.
 */
interface EngineListener {
    fun onLog(level: LogLevel, message: String)

    /**
     * Reports bulk-transfer progress.
     *
     * @param label short description of the current operation
     * @param done bytes completed
     * @param total bytes expected, or -1 when unknown
     */
    fun onProgress(label: String, done: Long, total: Long) {}
}

/** Discards all output; handy for tests. */
object SilentListener : EngineListener {
    override fun onLog(level: LogLevel, message: String) = Unit
}

/** Collects output in memory; handy for tests and the console tab's scrollback. */
class RecordingListener : EngineListener {
    data class Entry(val level: LogLevel, val message: String)

    private val entries = ArrayList<Entry>()
    var lastLabel: String = ""
        private set
    var lastDone: Long = 0
        private set
    var lastTotal: Long = 0
        private set

    override fun onLog(level: LogLevel, message: String) {
        synchronized(entries) { entries.add(Entry(level, message)) }
    }

    override fun onProgress(label: String, done: Long, total: Long) {
        lastLabel = label
        lastDone = done
        lastTotal = total
    }

    fun snapshot(): List<Entry> = synchronized(entries) { entries.toList() }

    fun text(): String = snapshot().joinToString("\n") { "[${it.level}] ${it.message}" }
}
