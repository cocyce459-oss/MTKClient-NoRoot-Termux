# Legacy Termux bridge (archived)

This directory holds the original Termux-based approach, kept for reference only.
**It is superseded by the native Android app in the repository root and is not
maintained.**

## Why it was replaced

The bridge was a ~150-line Python script that shelled out to `termux-usb` and
then invoked upstream `stage2.py`. It required a full Termux toolchain:

| Requirement | Approximate installed size |
|---|---|
| Termux `clang`, `binutils`, LLVM | 1.5 – 2.5 GB |
| Python + venv + pip dependencies | 0.7 – 1.5 GB |
| libusb, Termux:API | ~10 MB |
| **Total** | **~2 – 4 GB** |

The native app replaces all of it with `android.hardware.usb.UsbManager`, which
is already part of the operating system, plus a Kotlin port of the BROM
protocol. See [`/docs/MIGRATION.md`](../docs/MIGRATION.md) for the full
comparison.

## Known defects in the archived bridge

These are recorded here so nobody wastes time debugging them:

1. **It could never run as committed.** `mtk.py` executes a sibling `stage2.py`,
   but this repository never contained `stage2.py`, `requirements.txt`, or the
   `mtkclient/` package. Every invocation hit
   `[ERROR] stage2.py not found at ...`.
2. **The README documented the wrong binary.** It described `printgpt`, `r`,
   `w`, `e`, `daa` and `oem` — commands belonging to upstream `mtk.py`, the full
   DA-based client. `stage2.py` only implements `rpmb`, `preloader`, `data`,
   `boot2`, `memread`, `memwrite`, `keys`, `seccfg` and `reboot`.
3. **The permission race was structural.** `termux-usb` shows its dialog *after*
   BROM has already started its watchdog countdown, so the user had roughly
   three seconds to accept. The native app inverts this: permission is granted
   (often implicitly, via the USB attach dialog) *before* BROM is contacted.
4. **`/dev/bus/usb` polling is not how Android exposes USB.** The bridge scanned
   device nodes and retried for 60 seconds; Android hands you a `UsbDevice`
   object directly.

## Files

- `mtk.py` — the archived bridge script
- `README-termux.md` — its original documentation, preserved verbatim
