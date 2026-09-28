package dev.cocyce.mtknative.usb

import android.hardware.usb.UsbDevice

/**
 * USB identity table for MediaTek boot-ROM and preloader modes.
 *
 * Ported from `mtkclient/config/usb_ids.py` (`default_ids`) plus the vendor
 * allow-list used by `UsbClass.connect()`. Android gives us the device
 * descriptors directly, so no libusb enumeration is involved.
 */
object MtkDeviceFilter {

    const val VID_MEDIATEK = 0x0E8D
    const val VID_LG = 0x1004
    const val VID_OPPO = 0x22D9
    const val VID_SONY = 0x0FCE

    /** Vendors the reference implementation considers when scanning the bus. */
    val ALLOWED_VENDORS = setOf(VID_MEDIATEK, VID_LG, VID_OPPO, VID_SONY)

    /** A boot-mode USB identity we know how to drive. */
    data class BootDevice(
        val vendorId: Int,
        val productId: Int,
        val label: String,
        /**
         * Interface number to claim, or -1 to auto-detect the first interface
         * exposing both a bulk IN and a bulk OUT endpoint.
         * Mirrors the per-PID values in `default_ids`.
         */
        val interfaceHint: Int = -1,
        /** True when the device is sitting in BROM (as opposed to preloader) mode. */
        val isBrom: Boolean = false
    ) {
        val id: String get() = "%04X:%04X".format(vendorId, productId)
    }

    val KNOWN_DEVICES: List<BootDevice> = listOf(
        BootDevice(VID_MEDIATEK, 0x0003, "MediaTek BROM", -1, isBrom = true),
        BootDevice(VID_MEDIATEK, 0x2000, "MediaTek Preloader", -1),
        BootDevice(VID_MEDIATEK, 0x2001, "MediaTek Preloader", -1),
        BootDevice(VID_MEDIATEK, 0x20FF, "MediaTek Preloader", -1),
        BootDevice(VID_MEDIATEK, 0x3000, "MediaTek Preloader", -1),
        BootDevice(VID_MEDIATEK, 0x6000, "MediaTek Preloader", 2),
        BootDevice(VID_LG, 0x6000, "LG Preloader", 2),
        BootDevice(VID_OPPO, 0x0006, "OPPO Preloader", -1),
        BootDevice(VID_SONY, 0xF200, "Sony BROM", -1, isBrom = true),
        BootDevice(VID_SONY, 0xD1E9, "Sony BROM (XA1)", -1, isBrom = true),
        BootDevice(VID_SONY, 0xD1E2, "Sony BROM", -1, isBrom = true),
        BootDevice(VID_SONY, 0xD1EC, "Sony BROM (L1)", -1, isBrom = true),
        BootDevice(VID_SONY, 0xD1DD, "Sony BROM (F3111)", -1, isBrom = true)
    )

    private val BY_ID: Map<Long, BootDevice> =
        KNOWN_DEVICES.associateBy { key(it.vendorId, it.productId) }

    private fun key(vendorId: Int, productId: Int): Long =
        (vendorId.toLong() shl 16) or productId.toLong()

    /** Resolves a descriptor to a known boot device, or null if unrecognised. */
    fun match(vendorId: Int, productId: Int): BootDevice? = BY_ID[key(vendorId, productId)]

    fun match(device: UsbDevice): BootDevice? = match(device.vendorId, device.productId)

    /**
     * True when the device is worth offering to the user even if it is not in
     * [KNOWN_DEVICES] — MediaTek occasionally enumerates with a product id we
     * have not catalogued, and BROM still speaks the same protocol.
     */
    fun isCandidate(device: UsbDevice): Boolean =
        match(device) != null || device.vendorId in ALLOWED_VENDORS

    /** Human-readable summary for the UI, e.g. `0E8D:0003 MediaTek BROM`. */
    fun describe(device: UsbDevice): String {
        val known = match(device)
        val name = known?.label ?: "Unknown MediaTek device"
        return "%04X:%04X  %s".format(device.vendorId, device.productId, name)
    }
}
