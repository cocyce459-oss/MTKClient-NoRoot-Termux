# Migrating from the Termux bridge

## What the old setup required

```bash
pkg update && pkg upgrade -y
pkg install python git termux-api libusb clang binutils -y   # ~2-4 GB
# + Termux:APK from F-Droid (not Play Store)
python3 -m venv ~/.venv
git clone https://github.com/cocyce459-oss/MTKClient-NoRoot-Termux
. ~/.venv/bin/activate
pip install -r requirements.txt
```

## What the native app requires

Install one APK. That is the entire setup.

No Termux, no Termux:API, no Python, no libusb, no compiler toolchain, no
virtualenv, no `git clone`, and no permissions to grant in advance.

## Command mapping

The old README documented commands from upstream `mtk.py` (the full DA client)
while the bridge actually executed `stage2.py` (the BROM client). Those two
command sets barely overlap, which is why so much of the documented surface never
worked. This table gives the honest mapping.

| Old README said | Actually ran | Native app |
|---|---|---|
| `python3 mtk.py printgpt` | ✗ `stage2.py` has no `printgpt` | ✓ `printgpt`, or the **Partitions** tab |
| `python3 mtk.py memread 0x0 0x100` | ✓ | ✓ `memread 0x0 0x100` |
| `python3 mtk.py r boot,vbmeta boot.img,vbmeta.img` | ✗ not a `stage2.py` command | ✓ `r boot,vbmeta boot.img,vbmeta.img` |
| `python3 mtk.py w boot patched_boot.img` | ✗ DA-only | ✗ rejected with an explanation |
| `python3 mtk.py e metadata,userdata,md_udc` | ✗ DA-only | ✗ rejected with an explanation |
| `python3 mtk.py daa seccfg unlock` | ✗ DA-only | ✗ rejected with an explanation |
| `python3 mtk.py oem lock` | ✗ DA-only | ✗ rejected with an explanation |
| `python3 mtk.py --debugmode printgpt` | ✗ | engine log is always on, at DEBUG |
| *(undocumented)* `stage2.py preloader` | ✓ | ✓ `preloader` |
| *(undocumented)* `stage2.py rpmb` | ✓ | ✓ `rpmb` |
| *(undocumented)* `stage2.py data` / `boot2` | ✓ | ✓ `data` / `boot2` |
| *(undocumented)* `stage2.py memwrite` | ✓ | ✓ `memwrite` |
| *(undocumented)* `stage2.py reboot` | ✓ | ✓ `reboot`, or the Device tab button |
| `stage2.py keys` / `seccfg` | ✓ but needs hardware crypto | ✗ out of BROM-core scope |

`seccfg` is a partial exception worth calling out: upstream can *generate* an
unlock image locally, but it needs the on-device SEJ engine to hash it, and then
needs the DA to *write* it. Neither is in scope here.

## Where the dumps go

Termux wrote to the current directory (`logs/`). The app writes to its own
external files directory, which any file manager can reach and which needs no
storage permission:

```
/sdcard/Android/data/dev.cocyce.mtknative/files/Documents/
```

`ls` in the console lists what has been written.

## What got better beyond size

**The permission race is gone.** `termux-usb` showed its dialog *after* BROM's
~3 second watchdog had already started. The native app declares a USB device
filter in its manifest, so plugging the target in makes Android offer to open the
app, and accepting that dialog grants USB permission implicitly — before anything
touches the bus. When the app is already open, `requestPermission()` suspends
until the user answers.

**Device detection is real.** The bridge polled `/dev/bus/usb` 600 times over 60
seconds and could not tell a MediaTek boot device from a flash drive. The app
matches VID/PID against a table ported from `usb_ids.py`, reports the chip name
after reading the hwcode, and can be launched by the system on attach.

**Interface selection is more permissive.** Upstream filtered for
`bInterfaceClass == 10` (CDC data). BROM exposes no CDC interface, so that filter
can fail on exactly the device you are trying to reach. The port claims the first
interface carrying a bulk IN/OUT pair, falling back to a per-PID hint.

**`printgpt` actually exists.** The old README led with it as the "safe test"
command, but `stage2.py` never implemented it. It is implemented natively here:
probe enough sectors to cover both 512- and 4096-byte geometries, decode the
header and entry array, re-read if the entries extend past the probe.

**Errors are specific.** "Permission denied or MTK execution failed" became
distinct messages for no host support, no device in time, permission declined,
open failed, no bulk endpoint pair, interface claim failed, short read, and
watchdog timeout — each with the action to take.

## Known defects in the archived bridge

Preserved in `legacy-termux/` for reference. These are why it is archived rather
than fixed:

1. `mtk.py` executes a sibling `stage2.py` that was **never committed**, along
   with `requirements.txt` and the `mtkclient/` package. Every run hit
   `[ERROR] stage2.py not found at ...`.
2. The README documented upstream `mtk.py`'s DA command set against a bridge that
   ran `stage2.py`.
3. `os.system()` string interpolation to build the `termux-usb` command line,
   with `shlex.quote` doing the heavy lifting.
4. A 60 second `/dev/bus/usb` polling loop standing in for what Android exposes
   as a `UsbDevice` object.
5. `find_usb_devices()` matched *any* USB node, so a connected flash drive or
   keyboard could trigger a permission request.

## Keeping the old setup working

Nothing was deleted. `legacy-termux/mtk.py` and its original README are intact.
To actually run the old bridge you would still need to supply upstream's
`stage2.py` and the `mtkclient/` package yourself — see
[bkerler/mtkclient](https://github.com/bkerler/mtkclient).
