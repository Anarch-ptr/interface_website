package com.filamentvision.backend.input.framing

import com.filamentvision.backend.input.profile.FramingFormat

object FramingBytesParser {
    private const val MAX_BYTES = 64

    fun parse(format: FramingFormat, text: String): ByteArray {
        val bytes = when (format) {
            FramingFormat.HEX -> parseHex(text)
            FramingFormat.ASCII -> parseAscii(text)
        }
        require(bytes.size <= MAX_BYTES) { "Application framing is limited to $MAX_BYTES bytes" }
        return bytes
    }

    private fun parseHex(text: String): ByteArray {
        val compact = text.filterNot(Char::isWhitespace)
        require(compact.isNotEmpty() && compact.length % 2 == 0) { "HEX framing must contain complete bytes" }
        require(compact.all { it.digitToIntOrNull(16) != null }) { "HEX framing contains a non-hex character" }
        return ByteArray(compact.length / 2) { index ->
            compact.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun parseAscii(text: String): ByteArray {
        require(text.isNotEmpty()) { "ASCII framing cannot be empty" }
        require(text.all { it.code in 0..0x7f }) { "ASCII framing only supports 7-bit ASCII" }
        return text.encodeToByteArray()
    }
}
