package dev.cocyce.mtknative.console

/**
 * Command-line lexing and number parsing for the console.
 *
 * Deliberately free of Android types so it can be unit tested on a plain JVM.
 */
object ConsoleParser {

    private val HEX_ONLY = Regex("[0-9a-fA-F]+")

    /**
     * Splits a command line into tokens, honouring single and double quotes.
     *
     * Quotes are removed but their contents are preserved verbatim, so
     * `r boot "my file.img"` yields two tokens.
     */
    fun tokenize(line: String): List<String> {
        val tokens = ArrayList<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var hasToken = false

        for (char in line) {
            when {
                quote != null -> if (char == quote) quote = null else current.append(char)
                char == '"' || char == '\'' -> {
                    quote = char
                    hasToken = true
                }
                char.isWhitespace() -> {
                    if (hasToken) {
                        tokens.add(current.toString())
                        current.setLength(0)
                        hasToken = false
                    }
                }
                else -> {
                    current.append(char)
                    hasToken = true
                }
            }
        }
        if (hasToken) tokens.add(current.toString())
        return tokens
    }

    /**
     * Parses a number the way `stage2.py`'s `getint()` does: decimal first,
     * then base 16. Accepts an optional `0x` prefix and a leading minus.
     *
     * @throws IllegalArgumentException when the token is not numeric
     */
    fun parseNumber(value: String): Long {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) throw IllegalArgumentException("Empty numeric argument")

        trimmed.toLongOrNull()?.let { return it }

        val negative = trimmed.startsWith("-")
        val unsigned = trimmed.removePrefix("-")
        val hex = unsigned.removePrefix("0x").removePrefix("0X")
        val parsed = hex.toLongOrNull(16)
            ?: throw IllegalArgumentException("Not a number: '$value'")
        return if (negative) -parsed else parsed
    }

    /** True when [spec] looks like a bare hex byte string such as `11223344`. */
    fun isHexByteString(spec: String): Boolean {
        val clean = spec.removePrefix("0x").removePrefix("0X")
        return clean.isNotEmpty() && clean.length % 2 == 0 && clean.matches(HEX_ONLY)
    }

    /** Decodes a hex byte string into bytes. */
    fun hexToBytes(spec: String): ByteArray =
        spec.removePrefix("0x").removePrefix("0X")
            .chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()

    /** Reads a `--flag value` or `--flag=value` option. */
    fun flagValue(args: List<String>, flag: String): String? {
        args.firstOrNull { it.startsWith("$flag=") }?.let { return it.substringAfter('=') }
        val index = args.indexOf(flag)
        if (index in 0 until args.size - 1) return args[index + 1]
        return null
    }

    /** Positional (non-flag) arguments. */
    fun positional(args: List<String>): List<String> = args.filterNot { it.startsWith("--") }
    /**
     * Decodes a `memwrite` payload using the reference's precedence exactly.
     *
     * Order matters and is easy to get wrong:
     *  1. a spec containing `0x` is a **dword** written little-endian, so
     *     `0x12345678` becomes `78 56 34 12`;
     *  2. otherwise an even-length hex string is decoded as raw bytes, so
     *     `1122334455667788` stays in that order;
     *  3. anything else is parsed as a number and written as a little-endian dword.
     *
     * A comma-separated list of dwords is accepted as a convenience extension.
     * File-name inputs are resolved by the caller, before reaching this function.
     *
     * @throws IllegalArgumentException when the spec is not numeric
     */
    fun decodeMemWritePayload(spec: String): ByteArray {
        if (spec.contains(",")) {
            val words = spec.split(",").map { parseNumber(it.trim()).toInt() }
            val out = ByteArray(words.size * 4)
            words.forEachIndexed { index, word -> leU32(word).copyInto(out, index * 4) }
            return out
        }
        if (spec.contains("0x", ignoreCase = true)) return leU32(parseNumber(spec).toInt())
        if (isHexByteString(spec)) return hexToBytes(spec)
        return leU32(parseNumber(spec).toInt())
    }

    /**
     * True when [spec] should be treated as a path rather than literal data, so a
     * missing file reports "no such file" instead of being reinterpreted as hex.
     */
    fun looksLikePath(spec: String): Boolean =
        spec.contains('/') || spec.endsWith(".bin") || spec.endsWith(".img")

    private fun leU32(value: Int): ByteArray = ByteArray(4) { ((value ushr (8 * it)) and 0xFF).toByte() }
}
