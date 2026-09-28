#!/usr/bin/env python3
"""
MTK Universal Termux Bridge - Fixed USB Permission Handling
Connects to MediaTek devices in BROM mode via USB on rooted Android/Termux.
Properly requests and handles Termux USB API permissions.
"""

import os
import sys
import time
import shlex
import subprocess
from pathlib import Path


def find_usb_devices():
    """Scan /dev/bus/usb for connected USB devices."""
    devices = []
    usb_base = Path("/dev/bus/usb")
    
    if usb_base.exists():
        try:
            for device_path in usb_base.glob("*/*"):
                if device_path.exists():
                    devices.append(str(device_path))
        except Exception:
            pass
    
    return devices


def request_usb_permission(device_path, args):
    """
    Request USB permission via termux-usb and execute stage2.py with TERMUX_USB_FD.
    
    Args:
        device_path: USB device path (e.g., /dev/bus/usb/001/002)
        args: Command-line arguments to pass to stage2.py
    
    Returns:
        Exit code from the permission request or execution.
    """
    script_dir = os.path.dirname(os.path.abspath(__file__))
    stage2_script = os.path.join(script_dir, "stage2.py")
    
    # Ensure stage2.py exists
    if not os.path.isfile(stage2_script):
        print(f"[ERROR] stage2.py not found at {stage2_script}")
        print("[ERROR] Cannot proceed without the MTK Stage2 client.")
        return 1
    
    # Build the command with proper quoting
    args_str = " ".join(shlex.quote(arg) for arg in args) if args else ""
    device_quoted = shlex.quote(device_path)
    stage2_quoted = shlex.quote(stage2_script)
    
    # Execute stage2.py with USB FD via termux-usb -e flag
    cmd = (
        f"termux-usb -r {device_quoted} "
        f"-e 'export TERMUX_USB_FD=$1; exec python3 {stage2_quoted} {args_str}'"
    )
    
    print(f"[+] Found USB device: {device_path}")
    print("[+] Requesting Termux USB permission...")
    print("[*] Accept the popup when it appears (you have ~3 seconds)")
    print()
    
    result = os.system(cmd)
    return result


def start_universal_bypass(args):
    """
    Continuously scan for USB devices and request permission when found.
    
    Args:
        args: Command-line arguments to pass to stage2.py
    
    Returns:
        Exit code from the USB permission request or detection timeout.
    """
    print("--- MTK Universal Termux Bridge (Fixed) ---")
    print("Waiting for device in BROM mode...")
    print("Scanning /dev/bus/usb for connected devices...")
    print()
    
    max_retries = 600  # ~60 seconds at 0.1s poll interval
    retry_count = 0
    last_devices = set()
    
    while retry_count < max_retries:
        try:
            devices = set(find_usb_devices())
            
            # Only log when devices list changes
            if devices and devices != last_devices:
                for device in devices:
                    if device not in last_devices:
                        print(f"[+] Detected USB device: {device}")
                        result = request_usb_permission(device, args)
                        if result == 0:
                            return 0
                        print("[!] Permission denied or MTK execution failed.")
                        print("[!] Retrying device scan...")
                        print()
                last_devices = devices
            
            # No devices connected yet
            if not devices and last_devices:
                print("[*] USB device disconnected. Waiting for next connection...")
                last_devices = set()
        
        except KeyboardInterrupt:
            print("\n[*] Interrupted by user.")
            return 130
        
        except Exception as e:
            print(f"[!] Scan error: {e}")
        
        time.sleep(0.1)
        retry_count += 1
    
    print("[!] Timeout: No USB device detected in /dev/bus/usb after 60 seconds.")
    print("[!] Ensure the target device is connected via USB and in BROM/Preloader mode.")
    return 1


def main():
    """Main entry point."""
    if len(sys.argv) < 2:
        print("Usage: python3 mtk.py <command> [options]")
        print()
        print("Examples:")
        print("  python3 mtk.py printgpt")
        print("  python3 mtk.py readflash boot boot.img")
        print("  python3 mtk.py --debugmode printgpt")
        print()
        print("Note: Ensure Termux API is installed and USB permission is granted.")
        return 1
    
    return start_universal_bypass(sys.argv[1:])


if __name__ == "__main__":
    sys.exit(main())
