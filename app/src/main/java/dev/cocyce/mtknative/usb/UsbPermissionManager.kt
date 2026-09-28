package dev.cocyce.mtknative.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * USB discovery and permission handling on the Android host stack.
 *
 * This replaces the whole Termux permission dance: the old bridge shelled out to
 * `termux-usb -r`, which required Termux:API from F-Droid and forced the user to
 * win a ~3 second race against BROM's watchdog. Here the system permission dialog
 * is asynchronous — BROM is not contacted until permission is actually granted,
 * and the app can even be auto-launched on plug-in via the manifest's
 * `USB_DEVICE_ATTACHED` filter.
 */
class UsbPermissionManager(private val context: Context) {

    /** Shared [UsbManager]; also used to open transports. */
    val usbManager: UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    /** True when this device can act as a USB host at all (needs OTG support). */
    val isUsbHostSupported: Boolean
        get() = context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)

    /** All attached devices that look like a MediaTek boot interface. */
    fun connectedDevices(): List<UsbDevice> =
        usbManager.deviceList.values.filter { MtkDeviceFilter.isCandidate(it) }

    /** Attached devices that are already usable without prompting. */
    fun permittedDevices(): List<UsbDevice> =
        connectedDevices().filter { usbManager.hasPermission(it) }

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    /**
     * Asks the system for access to [device] and suspends until the user answers.
     *
     * Returns immediately with `true` when permission is already held.
     */
    suspend fun requestPermission(device: UsbDevice): Boolean {
        if (usbManager.hasPermission(device)) return true

        return suspendCancellableCoroutine { continuation ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    if (intent?.action != ACTION_USB_PERMISSION) return
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    val target = extractDevice(intent)
                    if (target != null && target.deviceId != device.deviceId) return

                    runCatching { context.unregisterReceiver(this) }
                    if (continuation.isActive) continuation.resume(granted)
                }
            }

            val filter = IntentFilter(ACTION_USB_PERMISSION)
            ContextCompat.registerReceiver(
                context,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )

            continuation.invokeOnCancellation {
                runCatching { context.unregisterReceiver(receiver) }
            }

            val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
            // The system must be able to fill in EXTRA_PERMISSION_GRANTED, so the
            // PendingIntent has to stay mutable on Android 12+.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0

            try {
                usbManager.requestPermission(device, PendingIntent.getBroadcast(context, 0, intent, flags))
            } catch (error: SecurityException) {
                Log.e(TAG, "requestPermission rejected", error)
                runCatching { context.unregisterReceiver(receiver) }
                if (continuation.isActive) continuation.resume(false)
            }
        }
    }

    /**
     * Waits for a MediaTek device to appear on the bus.
     *
     * Direct equivalent of the old `start_universal_bypass()` polling loop, but
     * driven by the Android device list instead of `/dev/bus/usb`.
     *
     * @param onDiscovered invoked each time a newly attached device is seen
     * @return the first device found, or null on timeout
     */
    suspend fun awaitDevice(
        timeoutMs: Long = DEFAULT_SCAN_TIMEOUT_MS,
        pollMs: Long = DEFAULT_POLL_MS,
        onDiscovered: (UsbDevice) -> Unit = {}
    ): UsbDevice? {
        val seen = HashSet<Int>()
        val deadline = System.currentTimeMillis() + timeoutMs

        while (System.currentTimeMillis() < deadline) {
            for (device in connectedDevices()) {
                if (seen.add(device.deviceId)) onDiscovered(device)
                return device
            }
            delay(pollMs)
        }
        return null
    }

    /**
     * Waits for a device and returns one that is ready to talk to.
     *
     * @throws UsbPermissionDeniedException if the user declined the dialog.
     */
    suspend fun awaitPermittedDevice(
        timeoutMs: Long = DEFAULT_SCAN_TIMEOUT_MS,
        onDiscovered: (UsbDevice) -> Unit = {}
    ): UsbDevice {
        val device = awaitDevice(timeoutMs, onDiscovered = onDiscovered)
            ?: throw UsbTimeoutException(
                "No MediaTek device appeared within ${timeoutMs / 1000}s. " +
                    "Power the target off, hold both volume keys, then connect it via OTG."
            )
        if (!requestPermission(device)) {
            throw UsbPermissionDeniedException(
                "USB permission was declined for ${MtkDeviceFilter.describe(device)}."
            )
        }
        return device
    }

    @Suppress("DEPRECATION")
    private fun extractDevice(intent: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

    companion object {
        private const val TAG = "UsbPermissionManager"
        const val ACTION_USB_PERMISSION = "dev.cocyce.mtknative.action.USB_PERMISSION"
        const val DEFAULT_SCAN_TIMEOUT_MS = 60_000L
        const val DEFAULT_POLL_MS = 100L
    }
}

/** Thrown when no MediaTek device shows up on the bus in time. */
class UsbTimeoutException(message: String) : Exception(message)

/** Thrown when the user declines the system USB permission dialog. */
class UsbPermissionDeniedException(message: String) : Exception(message)
