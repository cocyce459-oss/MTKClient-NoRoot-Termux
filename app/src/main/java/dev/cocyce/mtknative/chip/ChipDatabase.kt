package dev.cocyce.mtknative.chip

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
data class ChipConfig(
    val hwcode: Int,
    val name: String,
    val daPayloadAddr: Long? = null,
    val meidAddr: Long? = null,
    val socidAddr: Long? = null,
    val sejBase: Long? = null
) {
    /** Marketing name, falling back to the raw hwcode. */
    val displayName: String get() = name.ifBlank { "MT%04X".format(hwcode) }
}

object ChipDatabase {

    /** hwcode read from BROM at [HwcodeAddress]. */
    const val HwcodeAddress: Int = 0x8000000

    private val TABLE: Map<Int, ChipConfig> = mapOf(
        0x0279 to ChipConfig(0x0279, "MT6797/MT6767", 0x201000L, 0x1030ACL, null, 0x1000A000L),
        0x0321 to ChipConfig(0x0321, "MT6735/T,MT8735A", 0x201000L, 0x1030B0L, null, 0x10008000L),
        0x0326 to ChipConfig(0x0326, "MT6755/MT6750/M/T/S", 0x201000L, 0x1030ACL, null, 0x1000A000L),
        0x0335 to ChipConfig(0x0335, "MT6737M/MT6735G", 0x201000L, 0x1030B0L, null, 0x10008000L),
        0x0337 to ChipConfig(0x0337, "MT6753", 0x201000L, 0x1030B0L, null, 0x10008000L),
        0x0507 to ChipConfig(0x0507, "MT6759", 0x201000L, null, null, null),
        0x0551 to ChipConfig(0x0551, "MT6757/MT6757D", 0x201000L, 0x1030B4L, null, 0x1000A000L),
        0x0562 to ChipConfig(0x0562, "MT6799", 0x201000L, 0x1033B8L, 0x1033C8L, 0x1000A000L),
        0x0571 to ChipConfig(0x0571, "MT0571", null, null, null, null),
        0x0598 to ChipConfig(0x0598, "ELBRUS/MT0598", 0x201000L, null, null, 0x1000A000L),
        0x0601 to ChipConfig(0x0601, "MT6750", 0x201000L, null, null, 0x1000A000L),
        0x0633 to ChipConfig(0x0633, "MT6570/MT8321", 0x201000L, null, null, 0x1000A000L),
        0x0688 to ChipConfig(0x0688, "MT6758", 0x201000L, 0x102BF8L, 0x102C08L, 0x10080000L),
        0x0690 to ChipConfig(0x0690, "MT6763", 0x201000L, 0x102B78L, 0x102B88L, 0x1000A000L),
        0x0699 to ChipConfig(0x0699, "MT6739/MT6731/MT8765", 0x201000L, 0x102AF8L, 0x102B08L, 0x1000A000L),
        0x0707 to ChipConfig(0x0707, "MT6768/MT6769", 0x201000L, 0x102AF8L, 0x102B08L, 0x1000A000L),
        0x0717 to ChipConfig(0x0717, "MT6761/MT6762/MT3369/MT8766B/MT8761/AC8259/AC8257", 0x201000L, 0x102AF8L, 0x102B08L, 0x1000A000L),
        0x0725 to ChipConfig(0x0725, "MT6779", 0x201000L, 0x102B38L, 0x102B48L, 0x1000A000L),
        0x0766 to ChipConfig(0x0766, "MT6765/MT8768t", 0x201000L, 0x102AF8L, 0x102B08L, 0x1000A000L),
        0x0788 to ChipConfig(0x0788, "MT6771/MT8385/MT8183/MT8666", 0x201000L, 0x102B38L, 0x102B48L, 0x1000A000L),
        0x0813 to ChipConfig(0x0813, "MT6785", 0x201000L, 0x102B38L, 0x102B48L, 0x1000A000L),
        0x0816 to ChipConfig(0x0816, "MT6885/MT6883/MT6889/MT6880/MT6890", 0x201000L, 0x102B78L, 0x102B88L, 0x1000A000L),
        0x0886 to ChipConfig(0x0886, "MT6873", 0x201000L, 0x102B78L, 0x102B88L, 0x1000A000L),
        0x0907 to ChipConfig(0x0907, "MT6983", 0x201000L, 0x1008ECL, 0x100934L, 0x1000A000L),
        0x0908 to ChipConfig(0x0908, "MT8696", 0x201000L, null, null, null),
        0x0930 to ChipConfig(0x0930, "MT8195 Chromebook", 0x201000L, null, null, null),
        0x0950 to ChipConfig(0x0950, "MT6891/MT6893", 0x201000L, 0x102B98L, 0x102BA8L, 0x1000A000L),
        0x0959 to ChipConfig(0x0959, "MT6877/MT6877V/MT8791N", 0x201000L, 0x102B98L, 0x102BA8L, 0x1000A000L),
        0x0989 to ChipConfig(0x0989, "MT6833", 0x201000L, 0x102B98L, 0x102BA8L, 0x1000A000L),
        0x0992 to ChipConfig(0x0992, "MT6880/MT6890 Modem", 0x201000L, null, null, 0x1000A000L),
        0x0996 to ChipConfig(0x0996, "MT6853", 0x201000L, 0x102B78L, 0x102B88L, 0x1000A000L),
        0x1066 to ChipConfig(0x1066, "MT6781", 0x201000L, 0x102B98L, 0x102BA8L, 0x1000A000L),
        0x1129 to ChipConfig(0x1129, "MT6855", 0x201000L, 0x1008ECL, 0x100934L, 0x1000A000L),
        0x1172 to ChipConfig(0x1172, "MT6895", 0x201000L, 0x1008ECL, 0x100934L, 0x1C009000L),
        0x1203 to ChipConfig(0x1203, "MT6897", 0x201000L, null, 0x20E7090L, 0x1040E000L),
        0x1208 to ChipConfig(0x1208, "MT6789/MT8781V", 0x201000L, 0x1008ECL, 0x100934L, 0x1000A000L),
        0x1209 to ChipConfig(0x1209, "MT6835V/ZA", 0x2001000L, 0x1008ECL, 0x100934L, 0x1000A000L),
        0x1229 to ChipConfig(0x1229, "MT6886", 0x2001000L, 0x1008ECL, 0x100934L, 0x1C009000L),
        0x1236 to ChipConfig(0x1236, "MT6989W", 0x2001000L, 0x1008ECL, 0x100934L, 0x1040E000L),
        0x1296 to ChipConfig(0x1296, "MT6985", 0x201000L, 0x1008ECL, 0x100934L, 0x1C009000L),
        0x1357 to ChipConfig(0x1357, "MT6991", 0x201000L, null, null, 0x1800E000L),
        0x1375 to ChipConfig(0x1375, "MT6878", 0x2010000L, null, null, 0x1040E000L),
        0x1471 to ChipConfig(0x1471, "MT6993", 0x201000L, 0x1008ECL, 0x100934L, 0x1800E000L),
        0x2523 to ChipConfig(0x2523, "MT2523", 0x2008000L, 0x11142C34L, null, 0x1000A000L),
        0x2601 to ChipConfig(0x2601, "MT2601", 0x2008000L, 0x11142C34L, null, 0x1000A000L),
        0x2625 to ChipConfig(0x2625, "MT2625", 0x4001000L, null, null, 0x1000A000L),
        0x3967 to ChipConfig(0x3967, "MT3967", 0x201000L, null, null, null),
        0x5932 to ChipConfig(0x5932, "MT5932", 0x201000L, null, null, null),
        0x6225 to ChipConfig(0x6225, "MT6225", null, null, null, 0x80140000L),
        0x6226 to ChipConfig(0x6226, "MT6226", null, null, null, 0x80140000L),
        0x6236 to ChipConfig(0x6236, "MT6236", null, null, null, null),
        0x6238 to ChipConfig(0x6238, "MT6238", null, null, null, null),
        0x6253 to ChipConfig(0x6253, "MT6253", null, null, null, null),
        0x6255 to ChipConfig(0x6255, "MT6255", null, null, null, 0x80140000L),
        0x6256 to ChipConfig(0x6256, "MT6256", null, null, null, null),
        0x625A to ChipConfig(0x625A, "MT625a", null, null, null, null),
        0x6261 to ChipConfig(0x6261, "MT6261/MT2503", 0x201000L, null, null, 0xA0110000L),
        0x6268 to ChipConfig(0x6268, "MT6268", null, null, null, null),
        0x6270 to ChipConfig(0x6270, "MT6270", null, null, null, null),
        0x6276 to ChipConfig(0x6276, "MT6276", null, null, null, null),
        0x6280 to ChipConfig(0x6280, "MT6280", null, null, null, 0x80080000L),
        0x6291 to ChipConfig(0x6291, "MT6291", null, null, null, null),
        0x6516 to ChipConfig(0x6516, "MT6516", 0x201000L, null, null, 0x1002D000L),
        0x6571 to ChipConfig(0x6571, "MT6571", 0x2009000L, null, null, null),
        0x6572 to ChipConfig(0x6572, "MT6572", 0x2008000L, 0x11142C34L, null, null),
        0x6573 to ChipConfig(0x6573, "MT6573/MT6260", 0x90006000L, null, null, 0x7002A000L),
        0x6575 to ChipConfig(0x6575, "MT6575/MT8317", 0xC2001000L, 0xF0002AF4L, null, 0xC101A000L),
        0x6577 to ChipConfig(0x6577, "MT6577", 0xC2001000L, null, null, 0xC101A000L),
        0x6580 to ChipConfig(0x6580, "MT6580", 0x201000L, 0x1030B4L, null, 0x1000A000L),
        0x6582 to ChipConfig(0x6582, "MT6582/MT6574/MT8382", 0x201000L, 0x1030CCL, null, 0x1000A000L),
        0x6583 to ChipConfig(0x6583, "MT6583/6589", 0x12001000L, null, null, 0x1000A000L),
        0x6592 to ChipConfig(0x6592, "MT6592/MT8392", 0x111000L, 0x1030A8L, null, 0x1000A000L),
        0x6595 to ChipConfig(0x6595, "MT6595", 0x111000L, 0x1030A4L, null, 0x1000A000L),
        0x6752 to ChipConfig(0x6752, "MT6752", 0x201000L, 0x1030B4L, null, 0x1000A000L),
        0x6795 to ChipConfig(0x6795, "MT6795", 0x110000L, 0x1030A0L, null, 0x1000A000L),
        0x6899 to ChipConfig(0x6899, "MT6899", 0x201000L, null, null, 0x1040E000L),
        0x7682 to ChipConfig(0x7682, "MT7682", 0x201000L, null, null, null),
        0x7686 to ChipConfig(0x7686, "MT7686", 0x201000L, null, null, null),
        0x8127 to ChipConfig(0x8127, "MT8127/MT3367/AC8227L", 0x201000L, 0x1031CCL, null, 0x1000A000L),
        0x8135 to ChipConfig(0x8135, "MT8135", 0x12001000L, null, null, null),
        0x8163 to ChipConfig(0x8163, "MT8163", 0x201000L, 0x1031C0L, null, 0x1000A000L),
        0x8167 to ChipConfig(0x8167, "MT8167/MT8516/MT8362", 0x201000L, 0x103478L, 0x103488L, 0x1000A000L),
        0x8168 to ChipConfig(0x8168, "MT8168/MT6357", 0x201000L, 0x106438L, 0x106448L, 0x1000A000L),
        0x8172 to ChipConfig(0x8172, "MT8173", 0xC0000L, 0x1230B0L, null, 0x1000A000L),
        0x8176 to ChipConfig(0x8176, "MT8176", 0xC0000L, 0x1230B0L, null, 0x1000A000L),
        0x8512 to ChipConfig(0x8512, "MT8512", 0x111000L, 0x104638L, 0x104648L, 0x1000A000L),
        0x8518 to ChipConfig(0x8518, "MT8518 VoiceAssistant", null, null, null, null),
        0x8590 to ChipConfig(0x8590, "MT8590/MT7683/MT8521/MT7623", 0x201000L, 0x1031D8L, null, 0x1000A000L),
        0x8695 to ChipConfig(0x8695, "MT8695", 0x201000L, 0x1032B8L, null, 0x1000A000L)
    )

    val size: Int get() = TABLE.size

    /** Resolves a hwcode, or null when the SoC is not catalogued. */
    fun find(hwcode: Int): ChipConfig? = TABLE[hwcode]

    /**
     * Resolves a hwcode, synthesising a placeholder for unknown chips so the
     * caller can still display something meaningful and continue.
     */
    fun findOrUnknown(hwcode: Int): ChipConfig =
        TABLE[hwcode] ?: ChipConfig(hwcode, "MT%04X (uncatalogued)".format(hwcode))
}
