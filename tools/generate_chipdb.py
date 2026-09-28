#!/usr/bin/env python3
"""
Regenerate app/src/main/java/dev/cocyce/mtknative/chip/ChipDatabase.kt from the
upstream mtkclient chip table.

The reference stores this as a 95 KB Python module full of per-chip classes and
e-fuse layouts. BROM core only needs the hwcode -> identity mapping plus a
handful of addresses, so we distil it into a compact Kotlin table (a few KB).

Usage:
    python3 tools/generate_chipdb.py /path/to/mtkclient
"""
import sys
import os
import json
from datetime import date

FIELDS = ["da_payload_addr", "meid_addr", "socid_addr", "sej_base"]

HEADER = '''package dev.cocyce.mtknative.chip

/**
 * MediaTek SoC identity table.
 *
 * GENERATED FILE - do not edit by hand.
 * Regenerate with `python3 tools/generate_chipdb.py /path/to/mtkclient`.
 *
 * Distilled from upstream `mtkclient/config/brom_config.py` (GPL-3.0). The
 * reference module is ~95 KB of per-chip classes and e-fuse layouts; BROM core
 * needs only the hwcode -> identity mapping and a few register bases, so this
 * table keeps just that. That single substitution is worth roughly 90 KB of
 * source and removes a hard dependency on the Python runtime.
 */
'''


def camel(snake):
    parts = snake.split("_")
    return parts[0] + "".join(p.capitalize() for p in parts[1:])


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    root = sys.argv[1]
    sys.path.insert(0, root)
    from mtkclient.config.brom_config import hwconfig  # noqa: E402

    rows = []
    for hwcode, cfg in sorted(hwconfig.items()):
        row = {"hwcode": int(hwcode), "name": getattr(cfg, "name", "") or ""}
        for field in FIELDS:
            value = getattr(cfg, field, None)
            row[camel(field)] = int(value) if value is not None else None
        rows.append(row)

    def lit(value):
        # NB: str.format() ignores % specifiers, so the hex conversion must use
        # {:X}. Addresses are emitted as Long literals: some chip register bases
        # (e.g. 0xF0002AF4) exceed the signed 32-bit range.
        return "null" if value is None else "0x{:X}L".format(value)

    out = [HEADER]
    out.append("data class ChipConfig(\n")
    out.append("    val hwcode: Int,\n")
    out.append("    val name: String,\n")
    out.append("    val daPayloadAddr: Long? = null,\n")
    out.append("    val meidAddr: Long? = null,\n")
    out.append("    val socidAddr: Long? = null,\n")
    out.append("    val sejBase: Long? = null\n")
    out.append(") {\n")
    out.append("    /** Marketing name, falling back to the raw hwcode. */\n")
    out.append('    val displayName: String get() = name.ifBlank { "MT%04X".format(hwcode) }\n')
    out.append("}\n\n")
    out.append("object ChipDatabase {\n\n")
    out.append("    /** hwcode read from BROM at [HwcodeAddress]. */\n")
    out.append("    const val HwcodeAddress: Int = 0x8000000\n\n")
    out.append("    private val TABLE: Map<Int, ChipConfig> = mapOf(\n")
    lines = []
    for row in rows:
        lines.append(
            "        0x{hwcode:04X} to ChipConfig(0x{hwcode:04X}, \"{name}\", {da}, {meid}, {socid}, {sej})".format(
                hwcode=row["hwcode"],
                name=row["name"].replace('"', '\\"'),
                da=lit(row["daPayloadAddr"]),
                meid=lit(row["meidAddr"]),
                socid=lit(row["socidAddr"]),
                sej=lit(row["sejBase"]),
            )
        )
    out.append(",\n".join(lines))
    out.append("\n    )\n\n")
    out.append("    val size: Int get() = TABLE.size\n\n")
    out.append("    /** Resolves a hwcode, or null when the SoC is not catalogued. */\n")
    out.append("    fun find(hwcode: Int): ChipConfig? = TABLE[hwcode]\n\n")
    out.append("    /**\n")
    out.append("     * Resolves a hwcode, synthesising a placeholder for unknown chips so the\n")
    out.append("     * caller can still display something meaningful and continue.\n")
    out.append("     */\n")
    out.append("    fun findOrUnknown(hwcode: Int): ChipConfig =\n")
    out.append('        TABLE[hwcode] ?: ChipConfig(hwcode, "MT%04X (uncatalogued)".format(hwcode))\n')
    out.append("}\n")

    dest = os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
        "app/src/main/java/dev/cocyce/mtknative/chip/ChipDatabase.kt",
    )
    with open(dest, "w") as handle:
        handle.write("".join(out))
    print("wrote %s (%d chips)" % (dest, len(rows)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
