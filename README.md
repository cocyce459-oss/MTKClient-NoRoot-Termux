# MTKClient Native

> [!CAUTION]
> **This tool reads and writes low-level device partitions. `memwrite` and
> `reboot` modify the target. Incorrect use can permanently hard-brick a device.
> Nothing here is responsible for that. Read-only commands (`printgpt`,
> `memread`, `r`, `preloader`, `rpmb`, `data`, `boot2`) do not modify anything.**

A **native Android APK** for talking to MediaTek devices in BROM mode over
USB-OTG. No root, no Termux, no Python, no libusb, no compiler toolchain — and no
gigabytes of runtime to install first.

This repository began as a Termux bridge script. It is now an Android app; the
bridge is archived under [`legacy-termux/`](legacy-termux/) and
[docs/MIGRATION.md](docs/MIGRATION.md) explains the move.

## Why

The old approach wrapped a desktop toolchain around a protocol that is only a few
hundred lines of logic. Nearly everything it installed existed to *host* that
logic rather than perform it:

| Termux setup | Approx. size | Native equivalent |
|---|---|---|
| `clang`, `binutils`, LLVM | 1.5 – 2.5 GB | nothing to compile |
| Python + venv + pip deps | 0.7 – 1.5 GB | Kotlin, already in the APK |
| `libusb` + Termux:API | ~10 MB | `android.hardware.usb` (in the OS) |
| mtkclient checkout | 60 MB | 9.6 KB generated chip table |
| **Total** | **~2 – 4 GB** | **~30 MB APK** (~2–3 MB slim) |

Measured, not estimated — see [docs/BUILDING.md](docs/BUILDING.md#resulting-sizes):

| Build | Bundled assets (deflated) | APK |
|---|---|---|
| Full | 27.68 MB | **~30 MB** |
| Slim (`-PslimAssets=true`) | 0.09 MB | **~2–3 MB** |

## Get the APK

Push any branch, or run the workflow manually:

```bash
gh workflow run "Build APK"
gh run download --name MTKClient-Native-release
adb install -r app-release.apk
```

The workflow runs the tests first, so an artifact only exists if the protocol
vectors passed. Pushing a `v*` tag publishes a GitHub Release with the APK.
Building locally instead: [docs/BUILDING.md](docs/BUILDING.md).

The app requests **no permissions**. Dumps land in app-specific external storage,
and USB host access needs no manifest permission at all.

## Use it

1. Power the target phone **off**.
2. Hold **both volume keys** and connect it to the host phone with a USB-OTG cable.
3. Android offers to open MTKClient Native — accept. That grants USB permission
   *before* BROM is contacted, so there is no countdown to lose.
4. In the **Partitions** tab, tap *Read partition table*. That is `printgpt`, and
   it is read-only.
5. Tap any partition to dump it.

Already have the app open? Use **Device → Scan & connect**, or the **Console** tab:

```
printgpt
info
r boot,vbmeta boot.img,vbmeta.img
memread 0x0 0x100
preloader
ls
help
```

Console syntax matches upstream `mtkclient`, and numbers accept decimal or `0x`
hex exactly as `stage2.py`'s `getint()` did.

## What works, and what does not

This build drives MediaTek's **BROM** backdoor interface directly. That needs no
exploit payload and no Download Agent — which is precisely why the app can be
30 MB instead of 4 GB.

**Works:** partition table, memory read/write, raw dumps of user / boot1 / boot2
/ RPMB, preloader extraction, chip identification, reboot.

**Does not work, and says so:** `w`, `e`, `daa`, `oem` — flashing, erasing and
bootloader unlock all require the Download Agent protocols (xflash / xmlflash /
legacy) and, on secured chips, an SLA/DAA bypass. That is the bulk of upstream's
29,000 lines and is out of scope here.

The console rejects those commands with an explanation rather than pretending to
succeed. A tool that claims to have unlocked a bootloader when it has not is
worse than one that refuses.

## Correctness

The riskiest part of this port is the wire protocol: get a byte order or a chunk
boundary wrong and you are writing to the wrong address on someone's phone. So it
is not verified against a re-derivation of the protocol — it is verified against
the reference implementation itself.

[`tools/golden_vectors.py`](tools/golden_vectors.py) monkeypatches
`usbwrite`/`usbread` on the real `Stage2` class from bkerler/mtkclient and
records, for 17 operations, the exact bytes transmitted, **the boundary of every
individual transfer**, and the length of every read requested. Those recordings
are committed as `app/src/test/resources/golden_vectors.json`, and
`BromProtocolVectorTest` replays each operation through the Kotlin
`BromProtocol` and asserts all three match.

Frame boundaries are compared because identical bytes sent in different chunk
sizes would still be wrong on real hardware. CI re-records from upstream on every
push, so a future protocol change surfaces as a named failure.

```bash
gradle :app:testDebugUnitTest --tests '*BromProtocolVectorTest*'
```

## Layout

```
app/src/main/java/dev/cocyce/mtknative/
  brom/     BromProtocol, WireFormat, Wire, BromOpcodes   the protocol
  usb/      MtkUsbTransport, UsbPermissionManager, MtkDeviceFilter
  engine/   MtkEngine, MtkSession, BrlytScanner           operations
  gpt/      GptParser                                     pure logic
  chip/     ChipDatabase                                  generated, do not edit
  console/  ConsoleShell, ConsoleParser                   mtk.py syntax
  ui/       MainActivity, fragments, MtkViewModel         Material 3
tools/      golden_vectors.py, generate_chipdb.py         verification + codegen
docs/       PROTOCOL, ARCHITECTURE, BUILDING, MIGRATION
legacy-termux/                                            archived bridge
```

Dependencies point strictly downward, and `brom/Wire` is the seam that lets the
protocol be tested on a desktop JVM with no Android runtime and no device.
`gpt/`, `chip/`, `console/ConsoleParser`, `engine/BrlytScanner` and all of `brom/`
import no `android.*` types at all. See
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Documentation

- [docs/PROTOCOL.md](docs/PROTOCOL.md) — the BROM wire format, opcodes, chunking rules, device IDs
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — layering, what was removed and why, the permission fix
- [docs/BUILDING.md](docs/BUILDING.md) — CI and local builds, signing, asset vendoring, sizes
- [docs/MIGRATION.md](docs/MIGRATION.md) — command-by-command mapping from the Termux bridge

## Licence

GPL-3.0 — see [LICENSE](LICENSE). This is a derivative of
[bkerler/mtkclient](https://github.com/bkerler/mtkclient) (GPL-3.0, © B. Kerler
2018–2025); the protocol, chip data and asset vendoring all originate there. The
MediaTek Download Agent binaries fetched at build time remain the property of
MediaTek and are not redistributed in this repository.
