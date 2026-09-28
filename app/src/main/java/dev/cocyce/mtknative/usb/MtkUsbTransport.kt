package dev.cocyce.mtknative.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import dev.cocyce.mtknative.brom.BromException
import dev.cocyce.mtknative.brom.Wire
import java.io.Closeable

/**
 * BROM transport built directly on the Android USB host stack.
 *
 * This is the single biggest win of the native port: `UsbDeviceConnection`
 * replaces libusb, pyusb, the Termux runtime and the `termux-usb` permission
 * shim. No root, no 3-second popup race, and nothing to install.
 *
 * Transfer semantics deliberately match the reference implementation:
 *  - one `bulkTransfer` per logical frame, because `usbwrite()` sets
 *    `pktsize = len(data)` and therefore emits each call as a single transfer;
 *  - reads loop until the exact requested byte count arrives, as `usbread()`
 *    does when chunking by `wMaxPacketSize`.
 */
class MtkUsbTransport(
    private val usbManager: UsbManager,
    private val device: UsbDevice,
    private val timeoutMs: Int = DEFAULT_TIMEOUT_MS
) : Wire, Closeable {

    private var connection: UsbDeviceConnection? = null
    private var claimedInterface: UsbInterface? = null
    private var endpointIn: UsbEndpoint? = null
    private var endpointOut: UsbEndpoint? = null

    /** Scratch buffer reused across reads to avoid per-packet allocation. */
    private var readScratch: ByteArray? = null

    val isConnected: Boolean get() = connection != null && endpointIn != null && endpointOut != null

    val descriptor: MtkDeviceFilter.BootDevice? get() = MtkDeviceFilter.match(device)

    val vendorId: Int get() = device.vendorId

    val productId: Int get() = device.productId

    /** e.g. `0E8D:0003`. */
    val usbId: String get() = "%04X:%04X".format(device.vendorId, device.productId)

    /** e.g. `0E8D:0003  MediaTek BROM`. */
    val deviceName: String get() = MtkDeviceFilter.describe(device)

    /**
     * Opens the device, claims an interface and locates the bulk endpoints.
     *
     * @param interfaceHint interface number to prefer, or -1 to auto-detect.
     * @throws BromException if the device cannot be opened or has no bulk pair.
     */
    fun connect(interfaceHint: Int = descriptor?.interfaceHint ?: -1) {
        if (isConnected) return

        val conn = usbManager.openDevice(device)
            ?: throw BromException(
                "Could not open ${device.vendorId.toHexString()}:${device.productId.toHexString()}. " +
                    "USB permission may have been revoked — reconnect the device and try again."
            )
        connection = conn

        val iface = selectInterface(conn, interfaceHint)
            ?: run {
                close()
                throw BromException(
                    "No interface with a bulk IN/OUT pair was found. " +
                        "The device may not be in BROM/preloader mode."
                )
            }

        if (!conn.claimInterface(iface, true)) {
            close()
            throw BromException("Could not claim USB interface ${iface.id}.")
        }
        claimedInterface = iface

        var foundIn: UsbEndpoint? = null
        var foundOut: UsbEndpoint? = null
        for (index in 0 until iface.endpointCount) {
            val endpoint = iface.getEndpoint(index)
            if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (endpoint.direction == UsbConstants.USB_DIR_IN) {
                if (foundIn == null) foundIn = endpoint
            } else {
                if (foundOut == null) foundOut = endpoint
            }
        }
        endpointIn = foundIn
        endpointOut = foundOut

        if (foundIn == null || foundOut == null) {
            close()
            throw BromException("Interface ${iface.id} is missing a bulk IN or OUT endpoint.")
        }

        Log.i(TAG, "Connected to ${MtkDeviceFilter.describe(device)} on interface ${iface.id}")
    }

    /**
     * Chooses the interface to drive.
     *
     * The reference filters on `bInterfaceClass == 10` (CDC data), which fails on
     * BROM because it exposes no CDC interface at all. Auto-detection here accepts
     * any interface that actually carries a bulk IN/OUT pair, which is strictly
     * more permissive and is what makes BROM mode reachable on Android.
     */
    private fun selectInterface(conn: UsbDeviceConnection, hint: Int): UsbInterface? {
        if (hint in 0 until device.interfaceCount) {
            val candidate = device.getInterface(hint)
            if (hasBulkPair(candidate)) return candidate
        }
        for (index in 0 until device.interfaceCount) {
            val candidate = device.getInterface(index)
            if (hasBulkPair(candidate)) return candidate
        }
        return if (device.interfaceCount > 0) device.getInterface(0) else null
    }

    private fun hasBulkPair(iface: UsbInterface): Boolean {
        var hasIn = false
        var hasOut = false
        for (index in 0 until iface.endpointCount) {
            val endpoint = iface.getEndpoint(index)
            if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (endpoint.direction == UsbConstants.USB_DIR_IN) hasIn = true else hasOut = true
        }
        return hasIn && hasOut
    }

    override fun write(data: ByteArray) {
        val conn = connection ?: throw BromException("Transport is not connected")
        val endpoint = endpointOut ?: throw BromException("No bulk OUT endpoint")
        if (data.isEmpty()) return

        var offset = 0
        var consecutiveFailures = 0
        while (offset < data.size) {
            val remaining = data.size - offset
            // bulkTransfer always reads from index 0, so resend the tail on a short write.
            val chunk = if (offset == 0) data else data.copyOfRange(offset, data.size)
            val sent = conn.bulkTransfer(endpoint, chunk, remaining, timeoutMs)
            if (sent <= 0) {
                consecutiveFailures++
                if (consecutiveFailures >= MAX_WRITE_RETRIES) {
                    throw BromException(
                        "Bulk OUT failed after $MAX_WRITE_RETRIES attempts " +
                            "(${offset}/${data.size} bytes written)"
                    )
                }
                continue
            }
            offset += sent
            consecutiveFailures = 0
        }
    }

    override fun read(length: Int): ByteArray {
        val conn = connection ?: throw BromException("Transport is not connected")
        val endpoint = endpointIn ?: throw BromException("No bulk IN endpoint")
        if (length <= 0) return ByteArray(0)

        val packetSize = endpoint.maxPacketSize.takeIf { it > 0 } ?: length
        val scratchSize = minOf(length, packetSize)
        val scratch = readScratch?.takeIf { it.size >= scratchSize }
            ?: ByteArray(scratchSize).also { readScratch = it }

        val out = ByteArray(length)
        var received = 0
        while (received < length) {
            val want = minOf(length - received, scratchSize)
            val count = conn.bulkTransfer(endpoint, scratch, want, timeoutMs)
            if (count < 0) {
                throw BromException(
                    "Bulk IN timed out after ${received}/${length} bytes",
                    cause = null
                )
            }
            if (count == 0) continue
            scratch.copyInto(out, received, 0, count)
            received += count
        }
        return out
    }

    override fun close() {
        val conn = connection
        val iface = claimedInterface
        if (conn != null && iface != null) {
            try {
                conn.releaseInterface(iface)
            } catch (error: RuntimeException) {
                Log.w(TAG, "releaseInterface failed", error)
            }
        }
        conn?.close()
        connection = null
        claimedInterface = null
        endpointIn = null
        endpointOut = null
        readScratch = null
    }

    private fun Int.toHexString(): String = "%04X".format(this)

    companion object {
        private const val TAG = "MtkUsbTransport"
        const val DEFAULT_TIMEOUT_MS = 2000
        private const val MAX_WRITE_RETRIES = 3
    }
}
