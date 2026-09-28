# Architecture

## The premise

The Termux approach wrapped a desktop toolchain around a protocol that is only a
few hundred lines of logic. Almost everything it installed existed to *host* that
logic, not to perform it:

| Termux dependency | What it was actually for | Native replacement |
|---|---|---|
| `libusb` + `pyusb` | Reaching the USB bus | `android.hardware.usb` (in the OS) |
| `termux-api` / `termux-usb` | Asking the user for USB permission | `UsbManager.requestPermission()` |
| Python 3 + venv | Running ~30 KB of protocol logic | Kotlin, compiled to dex |
| `pycryptodome` | AES/SHA for the DA and key paths | Not needed for BROM core |
| `clang`, `binutils` | Building native wheels for the above | Nothing to build |
| `pyside6`, `unicorn`, `keystone`, `capstone` | GUI and exploit tooling | Material 3 views; unused on this path |

Removing the host rather than shrinking it is what takes the footprint from
gigabytes to tens of megabytes.

## Layering

Strictly downward dependencies. Each layer is testable without the one below it.

```
ui/            MainActivity, fragments, MtkViewModel        (Android views)
console/       ConsoleShell, ConsoleParser                  (mtk.py syntax)
engine/        MtkEngine, MtkSession, BrlytScanner          (operations)
gpt/           GptParser                                    (pure logic)
chip/          ChipDatabase                                 (generated data)
brom/          BromProtocol, WireFormat, Wire, BromOpcodes  (protocol)
usb/           MtkUsbTransport, UsbPermissionManager,       (Android USB)
               MtkDeviceFilter
```

The seam that makes this work is `brom/Wire`:

```kotlin
interface Wire {
    fun write(data: ByteArray)
    fun read(length: Int): ByteArray
}
```

`MtkUsbTransport` implements it over `UsbDeviceConnection`; tests implement it
over a recorder. So `BromProtocol` — the part that must be byte-exact or the
device may brick — is exercised on a desktop JVM with no Android runtime and no
hardware. Same for `GptParser`, `BrlytScanner` and `ConsoleParser`, none of which
import a single `android.*` type.

`EngineListener` plays the same role upwards: the engine emits log lines and
progress through it, so the GUI and the console tab are two interchangeable
consumers of one engine.

## What was monolithed away

**Chip database.** Upstream `brom_config.py` is 95 KB of per-chip Python classes
and e-fuse address layouts. BROM core needs only `hwcode → identity` plus a few
register bases. `tools/generate_chipdb.py` imports the real `hwconfig` dict and
distils it into a generated Kotlin table: **89 chips in 9.6 KB**. The generator is
committed so the table can be regenerated, and it is the reason `ChipDatabase.kt`
is marked do-not-edit.

**Payload binaries.** The 58 files in upstream `payloads/` exist for the exploit
and Download Agent paths. The BROM backdoor needs none of them, so none are
required for the app's core operations. They are still vendored into the APK
(90 KB deflated) because they are nearly free and unlock later work.

**DA binaries.** The six `Loader/*.bin` Download Agents are 49 MB raw and
27.6 MB deflated — the dominant term in the APK. They are *bundled* so the
installed app is fully offline, but they are fetched at build time rather than
committed, keeping the repository small. `Loader/Preloader/` (838 device-specific
dumps) is excluded outright: nothing on the BROM path reads it.

**Serial, TCP/IP, GUI and Windows code.** Upstream carries `seriallib.py`,
`mtk_tcpip_*.py`, a PySide6 GUI, and bundled `libusb*.dll` / `winfsp*.dll`. None
of it is reachable from an Android USB-host app.

## USB permission: the structural fix

The old bridge's central problem was not a bug, it was an ordering constraint it
could not satisfy:

```
device plugged in → BROM starts a ~3 s watchdog
                  → mtk.py notices via /dev/bus/usb polling
                  → shells out to `termux-usb -r`
                  → Termux:API shows a dialog
                  → user must accept before the watchdog fires
```

The native flow inverts the last two steps:

```
device plugged in → Android matches res/xml/device_filter.xml
                  → offers to open MTKClient Native
                  → accepting grants USB permission implicitly
                  → only then is BROM contacted
```

Where the app is already open, `UsbPermissionManager.requestPermission()`
suspends until the system dialog resolves. Nothing touches the bus until
permission actually exists, so the watchdog race disappears rather than being
made more winnable.

Two implementation details matter here:

- The `PendingIntent` must stay **mutable** on Android 12+ so the system can fill
  in `EXTRA_PERMISSION_GRANTED`, and must carry `setPackage(...)` on Android 14+
  because implicit `PendingIntent`s are rejected.
- Interface selection is deliberately *more* permissive than upstream. The
  reference filters for `bInterfaceClass == 10` (CDC data); BROM exposes no CDC
  interface, so that filter can fail. The port claims the first interface that
  actually carries a bulk IN/OUT pair, falling back to an explicit hint from the
  VID/PID table.

## Threading

All protocol work is blocking and runs on `Dispatchers.IO`. `MtkViewModel.guard()`
serialises it behind an `AtomicBoolean`, so two operations can never drive one
USB connection concurrently. The gate is not the `LiveData` flag, because
`postValue` is asynchronous and two rapid calls could both observe "not busy".

## Scope boundary

This build implements the BROM core. It does not implement the Download Agent
protocols (xflash / xmlflash / legacy), the SLA/DAA bypass exploits, or hardware
crypto engines (SEJ / DXCC / GCPU). Those are what make `w`, `e`, `daa` and `oem`
work upstream, and they account for the bulk of upstream's 29,000 lines.

The console states this explicitly when such a command is entered instead of
failing silently — a tool that claims to have unlocked a bootloader when it has
not is worse than one that refuses.
