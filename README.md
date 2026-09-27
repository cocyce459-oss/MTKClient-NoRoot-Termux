# MTKClient-NoRoot-Termux

> [!CAUTION]
> **WARNING: This tool deals with low-level device partitions. Incorrect usage can permanently HARD BRICK your device. This tool is currently in BETA TESTING. I am not responsible for any damages.**

**Fixed fork:** Termux USB permission popup now works reliably. The first fully functional MTKClient for Termux that works WITHOUT ROOT. Optimized 'Sniper' script for catching BROM/Preloader VCOM ports on unrooted Android devices via OTG.

## ✅ What's Fixed in This Fork

- **USB Permission Popup Now Works:** The script no longer tries to launch the non-existent `mtk_main.py`. It properly requests Termux USB permissions and directly executes `stage2.py`.
- **Proper Environment Passing:** The `TERMUX_USB_FD` environment variable is correctly passed to the MTK client after permission is granted.
- **Better Device Detection:** Scans `/dev/bus/usb` reliably with validation and retry logic (60-second timeout).
- **Clear User Feedback:** Better messages tell you exactly what's happening and what to do.
- **Safe Command Testing:** Use `printgpt` to safely verify your device connection without risk of data loss.

---

## Key Details

- **Zero Root Required:** Unlike the original mtkclient, this version is designed to run in a standard Termux environment without root access.
- **Optimized for Mobile:** All GUI, Windows-specific, and non-essential files have been stripped to keep the script small and fast.
- **Enhanced Connection:** Features a custom polling loop that looks for USB devices thousands of times per second to overcome Android's single-look limitation.

---

## Quick Start (5 Minutes)

### 1. Install Prerequisites

```bash
pkg update && pkg upgrade -y
pkg install python git termux-api libusb clang binutils -y
```

**Important:** Install **Termux:api** from [F-Droid](https://f-droid.org/repo/com.termux.api_1002.apk) or [GitHub](https://github.com/termux/termux-api-package/releases), **NOT** from the Play Store.

### 2. Clone and Setup

```bash
python3 -m venv ~/.venv
git clone https://github.com/cocyce459-oss/MTKClient-NoRoot-Termux
cd MTKClient-NoRoot-Termux
. ~/.venv/bin/activate
pip install -r requirements.txt
```

### 3. Test Connection (Safe & Non-Destructive)

**Before doing anything risky, test that your device connects properly:**

```bash
# Check for USB devices
termux-usb -l

# Power off your target phone completely
# Hold BOTH volume buttons on the target phone
# Connect it to the host phone via USB/OTG (while holding volume buttons)

# Run this command to print the device partition table (READ-ONLY, SAFE)
python3 mtk.py printgpt
```

**What to expect:**
1. Terminal will print: `[*] Waiting for device in BROM mode...`
2. Device will be detected: `[+] Found USB device: /dev/bus/usb/...`
3. A **Termux API popup** will appear on screen
4. **Quickly press "OK"** in the popup (you have ~3 seconds before the device times out)
5. If successful, you'll see the partition table printed to terminal
6. If it fails, try again — timing is critical

**If the popup doesn't appear:**
- Ensure Termux:api is installed (from F-Droid, not Play Store)
- Grant USB permission: Open Termux Settings → Permissions → USB → Toggle ON
- Restart Termux and try again

---

## How to Use

### General Syntax

```bash
python3 mtk.py <command> [options]
```

### Safe Testing Commands (No Risk of Data Loss)

```bash
# Print partition table (READ-ONLY) — USE THIS TO TEST FIRST
python3 mtk.py printgpt

# Read device info (READ-ONLY)
python3 mtk.py memread 0x0 0x100
```

### Common Operations

#### Dump Boot and VBMeta

```bash
python3 mtk.py r boot,vbmeta boot.img,vbmeta.img
```

#### Unlock Bootloader

```bash
python3 mtk.py e metadata,userdata,md_udc
python3 mtk.py daa seccfg unlock
```

#### Lock Bootloader

```bash
python3 mtk.py oem lock
```

#### Flash Boot (for rooting)

```bash
python3 mtk.py w boot patched_boot.img
```

#### Read GPT Table

```bash
python3 mtk.py printgpt
```

#### Erase Userdata (Factory Reset)

```bash
python3 mtk.py e userdata
```

---

## Flags Reference

### Type 1 Flags (After Command)

**Format:** `python3 mtk.py [command] --[flag]`

| Flag | Purpose |
|------|---------|
| `--force` | Bypass signature or size mismatches to force a flash |
| `--reset` | Reboot device normally after process completes |
| `--skip [partition]` | Ignore a specific partition during bulk read/write |

### Type 2 Flags (Before Command)

**Format:** `python3 mtk.py --[flag] [command]`

| Flag | Purpose |
|------|---------|
| `--nobatt` | For devices that require connection without battery to trigger BROM |
| `--stage2` | Force the SLA/DAA bypass payload for newer, secured MediaTek chipsets |
| `--debugmode` | Provide full log of the connection process (for debugging failures) |

**Example:**
```bash
python3 mtk.py --debugmode printgpt
```

---

## Troubleshooting

### "Device is recognized but won't connect"

This was the original issue in the upstream repository. **This fork fixes it.** If you still experience this:

1. Ensure Termux:api is from F-Droid, not Play Store
2. Check USB permission: `termux-usb -l` should show `/dev/bus/usb/...` entries
3. Try with `--debugmode` to see full logs:
   ```bash
   python3 mtk.py --debugmode printgpt
   ```

### "No popup appears when connecting device"

- Restart Termux
- Go to Termux Settings → Permissions → Grant USB permission manually
- Try again

### "Device times out after 3 seconds"

- Timing is critical. Practice holding the volume buttons while connecting
- Some devices need you to hold power + volume buttons simultaneously
- Refer to your device's BROM/EDL mode documentation

### "Command fails with error about mtkclient"

Ensure all files are present:
```bash
ls -la mtkclient/
ls -la mtkclient/Loader/
```

If directories are empty, you may need to pull the complete mtkclient library files separately.

---

## Version History

### v2.1.4-fixed (This Fork)
- ✅ Fixed Termux USB permission popup handling
- ✅ Corrected script execution path (no more `mtk_main.py` error)
- ✅ Added proper `TERMUX_USB_FD` environment variable passing
- ✅ Improved device scanning and retry logic
- ✅ Enhanced error messages and user guidance

### v2.1.4 (Original)
- Initial release with USB bridge support

---

## For Suggestions and Bug Reports

Contact: **sameenataj427@gmail.com**

Or open an issue on this fork: https://github.com/cocyce459-oss/MTKClient-NoRoot-Termux/issues

---

## License

GNU General Public License v3.0 — See LICENSE file for details.
