package dev.cocyce.mtknative.brom

/** Raised when the device does not behave as the BROM protocol expects. */
class BromException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Transport abstraction over the BROM USB bulk endpoints.
 *
 * Every logical frame is handed to [write] separately, mirroring the reference
 * implementation's `usbwrite()` call granularity. Some BROM revisions are
 * sensitive to transfer boundaries, so callers must not pre-coalesce frames.
 *
 * Keeping this as an interface means [BromProtocol] is pure logic: it can be
 * exercised on a desktop JVM against recorded wire vectors with no Android
 * runtime and no physical device.
 */
interface Wire {
    /** Sends one frame. Must block until the bytes are accepted by the host stack. */
    fun write(data: ByteArray)

    /**
     * Reads exactly [length] bytes.
     *
     * @throws BromException if the device returns fewer bytes than requested.
     */
    fun read(length: Int): ByteArray
}

/** Injectable delay so protocol timing can be skipped under test. */
interface Sleeper {
    fun sleep(millis: Long)
}

/** Production [Sleeper]. */
object ThreadSleeper : Sleeper {
    override fun sleep(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw BromException("Interrupted while waiting for device")
        }
    }
}

/** No-op [Sleeper] for tests. */
object NoopSleeper : Sleeper {
    override fun sleep(millis: Long) = Unit
}
