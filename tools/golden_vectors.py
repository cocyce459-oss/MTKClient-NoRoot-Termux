#!/usr/bin/env python3
"""
Record byte-exact wire vectors from the upstream reference implementation.

Rather than transcribing the BROM protocol by hand and hoping it is right, this
script instruments the real `Stage2` class from bkerler/mtkclient: it replaces
`usbwrite`/`usbread` with recorders, drives each protocol operation, and writes
out the exact bytes transmitted, the boundaries of each individual transfer, and
the length of every read requested.

The result is `app/src/test/resources/golden_vectors.json`, which
`BromProtocolVectorTest` replays the Kotlin port against. If upstream ever
changes the protocol, re-running this script and re-running the tests will show
exactly which frames drifted.

Usage:
    pip install pyusb pycryptodome pycryptodomex colorama
    python3 tools/golden_vectors.py /path/to/mtkclient

    # then confirm the Kotlin port still agrees:
    #   gradle :app:testDebugUnitTest --tests '*BromProtocolVectorTest*'
"""
import importlib.util
import json
import os
import sys

ACK_OK = b"\xD0\xD0\xD0\xD0"
ACK_EMMC_READY = b"\xD1\xD1\xD1\xD1"

DEFAULT_DEST = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app", "src", "test", "resources", "golden_vectors.json",
)


class Recorder:
    """Stands in for the reference's usbwrite/usbread pair."""

    def __init__(self, responder):
        self.frames = []
        self.read_sizes = []
        self.responder = responder

    @property
    def tx(self):
        return "".join(self.frames)

    def usbwrite(self, data):
        if isinstance(data, str):
            data = data.encode()
        raw = bytes(data)
        self.frames.append(raw.hex())
        return len(raw)

    def usbread(self, length=None, **kwargs):
        size = length if length else 512
        self.read_sizes.append(size)
        reply = self.responder(size)
        assert len(reply) == size, "responder returned %d of %d" % (len(reply), size)
        return reply


def load_stage2(mtkclient_root):
    sys.path.insert(0, mtkclient_root)
    path = os.path.join(mtkclient_root, "stage2.py")
    if not os.path.isfile(path):
        raise SystemExit("stage2.py not found under %s" % mtkclient_root)
    spec = importlib.util.spec_from_file_location("stage2_reference", path)
    module = importlib.util.module_from_spec(spec)
    try:
        spec.loader.exec_module(module)
    except SystemExit:
        pass  # stage2.py calls exit() at import time on some paths
    return module.Stage2


def make_instance(stage2_class, responder):
    """Builds a Stage2 without touching USB hardware."""
    instance = stage2_class.__new__(stage2_class)
    recorder = Recorder(responder)
    instance.usbwrite = recorder.usbwrite
    instance.usbread = recorder.usbread
    # The reference guards every flash read behind this latch; setting it keeps
    # the recorded frames to the operation under test.
    instance.emmc_inited = True
    instance.info = lambda *a, **k: None
    instance.error = lambda *a, **k: None
    instance.warning = lambda *a, **k: None
    instance.debug = lambda *a, **k: None
    instance.cdc = type("FakeCdc", (), {"connected": True})()
    return instance, recorder


def ack_responder(length):
    """BROM replies D0D0D0D0 to writes/jumps; data reads are don't-care."""
    return ACK_OK if length == 4 else b"\x00" * length


def build_cases():
    def cold_boot_responder(state):
        def respond(length):
            if length != 4:
                return b"\x00" * length
            state["calls"] = state.get("calls", 0) + 1
            return b"\x00\x00\x00\x00" if state["calls"] == 1 else ACK_EMMC_READY
        return respond

    sink = lambda data: None  # noqa: E731
    return [
        ("read32_single", ack_responder, lambda s: s.read32(0x1000, 1)),
        ("read32_three", ack_responder, lambda s: s.read32(0x2000, 3)),
        ("write32_int", ack_responder, lambda s: s.write32(0x100, 0xDEADBEEF)),
        ("write32_list", ack_responder,
         lambda s: s.write32(0x200, [0x11111111, 0x22222222, 0x33333333])),
        ("jump", ack_responder, lambda s: s.jump(0x22000000)),
        ("cmd_C8_clearcache", ack_responder, lambda s: s.cmd_C8(0)),
        ("memread_16", ack_responder, lambda s: s.memread(0x0, 0x10)),
        ("memread_0x250", ack_responder, lambda s: s.memread(0x8000000, 0x250)),
        ("memwrite_bytes", ack_responder,
         lambda s: s.memwrite(0x200000, bytes.fromhex("1122334455667788"))),
        ("memwrite_odd_pad", ack_responder,
         lambda s: s.memwrite(0x0, bytes.fromhex("AABBCC"))),
        ("reboot", ack_responder, lambda s: s.reboot()),
        ("readflash_user_0x800", ack_responder,
         lambda s: s.readflash(type_=0, start=0x400, length=0x800)),
        ("readflash_type1_0x4000", ack_responder,
         lambda s: s.readflash(type_=1, start=0, length=0x4000)),
        ("readflash_odd_len", ack_responder,
         lambda s: s.readflash(type_=0, start=0x100, length=0x350)),
        ("init_emmc_already",
         lambda n: b"\x01\x00\x00\x00" if n == 4 else b"\x00" * n,
         lambda s: s.init_emmc()),
        ("init_emmc_coldboot", cold_boot_responder({}), lambda s: s.init_emmc()),
        ("rpmb_0x200", ack_responder,
         lambda s: s.rpmb(0, 0x200, os.devnull, False)),
    ]


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    root = os.path.abspath(sys.argv[1])
    dest = os.path.abspath(sys.argv[2]) if len(sys.argv) > 2 else DEFAULT_DEST

    stage2 = load_stage2(root)
    vectors = {}
    for name, responder, operation in build_cases():
        instance, recorder = make_instance(stage2, responder)
        # rpmb/preloader open files; keep the latch set so no probe is emitted.
        instance.emmc_inited = True
        try:
            operation(instance)
        except Exception as error:  # recorded, not fatal: surfaces as a test failure
            vectors[name] = {"error": repr(error)[:200]}
            continue
        vectors[name] = {
            "tx": recorder.tx,
            "tx_frames": recorder.frames,
            "rx_requests": recorder.read_sizes,
        }

    with open(dest, "w") as handle:
        json.dump(vectors, handle, indent=1, sort_keys=True)

    failures = [name for name, value in vectors.items() if "error" in value]
    print("recorded %d vectors -> %s" % (len(vectors), dest))
    for name, value in sorted(vectors.items()):
        if "error" in value:
            print("  %-26s ERROR %s" % (name, value["error"]))
        else:
            print("  %-26s tx=%4d bytes  frames=%2d  reads=%s" % (
                name, len(value["tx"]) // 2, len(value["tx_frames"]),
                value["rx_requests"][:4]))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
