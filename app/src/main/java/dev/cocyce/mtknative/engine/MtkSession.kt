package dev.cocyce.mtknative.engine

import android.content.Context
import android.hardware.usb.UsbDevice
import android.os.Environment
import dev.cocyce.mtknative.brom.BromException
import dev.cocyce.mtknative.usb.MtkUsbTransport
import dev.cocyce.mtknative.usb.UsbPermissionManager
import java.io.File
import java.io.OutputStream

/**
 * Owns the USB connection lifecycle for one device session.
 *
 * Replaces the old bridge's `start_universal_bypass()` loop: instead of shelling
 * out to `termux-usb` and racing a 3 second popup, permission is requested
 * asynchronously and the transport is only opened once it is actually granted.
 */
class MtkSession(
    private val context: Context,
    private val listener: EngineListener
) {

    val permissions = UsbPermissionManager(context)

    private var transport: MtkUsbTransport? = null
    private var engine: MtkEngine? = null

    val isConnected: Boolean get() = engine != null && transport?.isConnected == true

    val deviceInfo: DeviceInfo? get() = engine?.deviceInfo

    /** Where dumps land: app-specific external storage when available. */
    val outputDir: File
        get() {
            val external = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            val dir = external ?: File(context.filesDir, "dumps")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    /** Scans for candidate devices without requesting permission. */
    fun scan(): List<UsbDevice> = permissions.connectedDevices()

    /**
     * Connects to the first MediaTek device that appears, prompting for
     * permission if needed.
     *
     * @throws dev.cocyce.mtknative.usb.UsbTimeoutException if nothing is plugged in
     * @throws dev.cocyce.mtknative.usb.UsbPermissionDeniedException if declined
     */
    suspend fun connect(timeoutMs: Long = UsbPermissionManager.DEFAULT_SCAN_TIMEOUT_MS): DeviceInfo {
        engine?.let { existing ->
            if (transport?.isConnected == true && existing.deviceInfo != null) {
                return existing.deviceInfo!!
            }
        }
        disconnect()

        listener.onLog(LogLevel.INFO, "Waiting for a MediaTek device in BROM mode…")
        val device = permissions.awaitPermittedDevice(timeoutMs) { found ->
            listener.onLog(LogLevel.DEBUG, "Detected ${found.vendorId}:${found.productId}")
        }
        return open(device)
    }

    /** Connects to a specific device the user picked from the UI. */
    suspend fun connectTo(device: UsbDevice): DeviceInfo {
        if (!permissions.requestPermission(device)) {
            throw BromException("USB permission declined for this device.")
        }
        disconnect()
        return open(device)
    }

    private fun open(device: UsbDevice): DeviceInfo {
        val newTransport = MtkUsbTransport(permissions.usbManager, device)
        newTransport.connect()
        transport = newTransport

        val newEngine = MtkEngine(newTransport, listener)
        engine = newEngine
        return try {
            newEngine.connect()
        } catch (error: BromException) {
            disconnect()
            throw error
        }
    }

    /**
     * Runs [block] against a connected engine, connecting first if needed.
     *
     * All engine work is synchronous and blocking, so callers must invoke this
     * from a background dispatcher.
     */
    suspend fun <T> use(block: (MtkEngine) -> T): T {
        val active = engine?.takeIf { transport?.isConnected == true } ?: run {
            connect()
            engine!!
        }
        return block(active)
    }

    fun disconnect() {
        engine?.let { runCatching { it.close() } }
        engine = null
        transport = null
    }

    /** Opens an output stream for [name] inside [outputDir]. */
    fun openOutput(name: String): OutputStream = File(outputDir, name).outputStream()
}
