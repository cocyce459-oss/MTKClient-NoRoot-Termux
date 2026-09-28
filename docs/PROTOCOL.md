# BROM wire protocol reference

MediaTek's boot ROM exposes a small command server over the USB bulk endpoints of
its download-mode interface. This document describes it completely, because the
native port in `dev.cocyce.mtknative.brom` implements exactly this and nothing
more.

Everything here was derived from `stage2.py` in
[bkerler/mtkclient](https://github.com/bkerler/mtkclient) (GPL-3.0) and is
pinned by machine-checked vectors — see [Verification](#verification).

## Framing

Every request starts with a 32-bit magic sent **big-endian**:

```
F0 0D D0 0D
```

It is followed by an opcode and its operands, also big-endian. Response *data*
and acknowledgements are **little-endian**. Getting this mixed up is the single
easiest way to break a port.

Each logical field is written as its own USB bulk transfer. This is not an
optimisation choice: the reference sets `pktsize = len(data)` in `usbwrite()`, so
every call becomes exactly one transfer, and some BROM revisions are sensitive to
transfer boundaries. Reads loop until the requested byte count arrives, chunked
by the endpoint's `wMaxPacketSize`.

## Opcodes

| Opcode | Name | Operands | Response |
|---|---|---|---|
| `0x4000` | write32 | `addr:u32be`, `len:u32be`, `data` (LE, padded to 4) | `D0 D0 D0 D0` per word |
| `0x4001` | jump | `addr:u32be` | `D0 D0 D0 D0` after ~5 s |
| `0x4002` | read32 | `addr:u32be`, `len:u32be` | `len` bytes, little-endian |
| `0x5000` | clear cache | — | `D0 D0 D0 D0` |
| `0x3000` | reboot | — | none |
| `0x3001` | kick watchdog | — | — |
| `0x6000` | eMMC init | — | `D1 D1 D1 D1` when ready |
| `0x6001` | eMMC status | — | `u32le`; `1` means already initialised |
| `0x1002` | eMMC switch | `partition:u32be` | — |
| `0x1000` | eMMC read | `startSector:u32be`, `sectorCount:u32be` | `sectorCount × 512` bytes |
| `0x2000` | block read (RPMB) | `start:u16be`, `count:u16be` | `count × 256` bytes |

Note that `0x2000` is the only opcode taking **16-bit** operands. Requests above
0xFFFF blocks must be split and continued one block at a time.

eMMC partition indices used with `0x1002`:

| Index | Partition |
|---|---|
| 0 | user area |
| 1 | boot1 (holds the preloader) |
| 2 | boot2 |
| 3 | RPMB |

## Chunking rules

**`memread`** issues requests of at most `0x100` bytes:

```
size = min(remaining, 0x100)
send MAGIC, 0x4002, addr + pos, size
read size bytes
```

**`memwrite`** uses the same `0x100` ceiling, zero-pads each chunk up to a
4-byte boundary, and reads a **single** acknowledgement only after the final
frame — not one per chunk:

```
for each chunk:
    send MAGIC, 0x4000, addr + pos, size, pad4(chunk)
read 4 bytes, expect D0 D0 D0 D0
```

A 3-byte write to address 0 therefore produces
`F00DD00D 00004000 00000000 00000003 AABBCC00` — note the length field says `3`
while the payload carries 4 bytes.

**`readflash`** rounds up to whole sectors:

```
sectors     = length / 512 + (1 if length % 512 else 0)
startSector = start / 512
send MAGIC, 0x1002, partition
send MAGIC, 0x1000, startSector, sectors
read sectors × 512
return buffer[start % 512 : start % 512 + length]
```

The final slice matters: a read at a non-sector-aligned offset returns one extra
partial sector, and the caller trims it.

## eMMC initialisation

The control flow is deliberately asymmetric and must be reproduced exactly:

```
send MAGIC, 0x6001
if read_u32le() != 1:          # controller not already up
    send MAGIC, 0x6000
    sleep 2 s
    if read_u32le() == 0xD1D1D1D1: return success
    latch "initialised" anyway   # reference behaviour, preserved
return failure
```

## Device identity

BROM and preloader enumerate under these USB descriptors, ported from
`mtkclient/config/usb_ids.py`:

| VID | PID | Mode |
|---|---|---|
| `0E8D` | `0003` | MediaTek **BROM** |
| `0E8D` | `2000` `2001` `20FF` `3000` `6000` | MediaTek preloader |
| `1004` | `6000` | LG preloader |
| `22D9` | `0006` | OPPO preloader |
| `0FCE` | `F200` `D1E9` `D1E2` `D1EC` `D1DD` | Sony BROM |

The SoC is identified by reading a 32-bit hwcode from physical address
`0x8000000`, then looking it up in the chip table.

## Important caveat: this is BROM, not the Download Agent

`0xF00DD00D` is BROM's own debug interface. It requires **no exploit payload and
no Download Agent** — `stage2.py` loads none, and neither does this port. That is
why the native app needs no payload binaries for its core operations.

The trade-off is real and worth stating plainly:

- **Available**: partition table reads, memory reads/writes, raw sector dumps of
  user/boot1/boot2/RPMB, preloader extraction, reboot.
- **Not available**: `w` (flash a partition), `e` (erase), `daa`/`oem` (bootloader
  unlock), and anything else that requires the DA protocol (xflash / xmlflash /
  legacy) or an SLA/DAA bypass exploit for secured chips.

Those paths live behind the Download Agent, are roughly 29,000 lines of Python
upstream, and are out of scope for this build. The console rejects such commands
explicitly rather than pretending to succeed.

## Verification

Cross-checked against the reference parser as well as the reference protocol: a
synthetic disk image built the way `GptParserTest` builds one was fed to
upstream's own `gpt.parse()`, which returned the same header fields, entry
geometry, flags and GUID strings the Kotlin decoder produces — including the
mixed-endian GUID convention (`aaaa0001-0201-0403-0605-0708090a0b0c`).

### Deliberate deviations from upstream

| Where | Upstream | Here | Why |
|---|---|---|---|
| Partition type name | `EFI_LINUX_DAYA` | `EFI_LINUX_DATA` | upstream typo in the `efi_type` enum (`gpt.py`) |
| USB interface selection | filters `bInterfaceClass == 10` (CDC data) | first interface with a bulk IN/OUT pair | BROM exposes no CDC interface, so the filter can fail on the device you want |
| `printgpt` | not implemented in `stage2.py` | implemented | the fork's README documented it against a binary that never had it |

Everything else — frame layout, chunk sizes, padding, acknowledgement handling,
sector rounding, the asymmetric eMMC init flow — is reproduced exactly.

The port is checked against vectors recorded from the reference implementation
itself, not against a re-derivation of it:

```bash
pip install pyusb pycryptodome pycryptodomex colorama
git clone https://github.com/bkerler/mtkclient /tmp/mtkclient
python3 tools/golden_vectors.py /tmp/mtkclient
gradle :app:testDebugUnitTest --tests '*BromProtocolVectorTest*'
```

`tools/golden_vectors.py` monkeypatches `usbwrite`/`usbread` on the real `Stage2`
class and records, for 17 operations:

- the exact transmitted bytes,
- **the boundary of every individual transfer**, and
- the length of every read requested.

Those recordings live in `app/src/test/resources/golden_vectors.json`. The test
replays each operation through the Kotlin `BromProtocol` against a recording
transport and asserts all three properties match. Frame boundaries are compared
because a port emitting identical bytes in different chunk sizes would still be
wrong on real hardware.

CI re-records the vectors from upstream on every push
(`protocol-drift` job in `.github/workflows/android.yml`) so a future upstream
protocol change surfaces as a named, specific failure.
