package com.filamentvision.backend.input.framing

import com.filamentvision.backend.input.profile.ApplicationFramingProfile
import com.filamentvision.backend.input.profile.FramingScope

data class ByteRange(
    val bytes: ByteArray,
    val offset: Int = 0,
    val length: Int = bytes.size,
) {
    init {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
    }

    fun copyOwned(): ByteArray = bytes.copyOfRange(offset, offset + length)
}

enum class ApplicationFramingError {
    PAYLOAD_TOO_SHORT,
    HEADER_MISMATCH,
    FOOTER_MISMATCH,
}

sealed interface ApplicationFramingResult {
    data class Accepted(
        val payload: ByteRange,
        val matchedHeader: ByteRange?,
        val matchedFooter: ByteRange?,
    ) : ApplicationFramingResult

    data class Rejected(val error: ApplicationFramingError) : ApplicationFramingResult
}

class ApplicationFramingParser(profile: ApplicationFramingProfile) {
    private data class CompiledBoundary(
        val bytes: ByteArray,
        val scope: FramingScope,
        val strip: Boolean,
    )

    private val header = if (profile.headerEnabled) {
        CompiledBoundary(
            FramingBytesParser.parse(profile.headerFormat, profile.headerBytes),
            profile.headerScope,
            profile.stripHeader,
        )
    } else null
    private val footer = if (profile.footerEnabled) {
        CompiledBoundary(
            FramingBytesParser.parse(profile.footerFormat, profile.footerBytes),
            profile.footerScope,
            profile.stripFooter,
        )
    } else null

    fun parse(scope: FramingScope, bytes: ByteArray): ApplicationFramingResult =
        parse(scope, ByteRange(bytes))

    fun parse(scope: FramingScope, input: ByteRange): ApplicationFramingResult {
        val scopedHeader = header?.takeIf { it.scope == scope }
        val scopedFooter = footer?.takeIf { it.scope == scope }
        val boundaryBytes = (scopedHeader?.bytes?.size ?: 0) + (scopedFooter?.bytes?.size ?: 0)
        if (input.length < boundaryBytes) {
            return ApplicationFramingResult.Rejected(ApplicationFramingError.PAYLOAD_TOO_SHORT)
        }

        val matchedHeader = scopedHeader?.let { boundary ->
            if (!matches(input.bytes, input.offset, boundary.bytes)) {
                return ApplicationFramingResult.Rejected(ApplicationFramingError.HEADER_MISMATCH)
            }
            ByteRange(input.bytes, input.offset, boundary.bytes.size)
        }
        val matchedFooter = scopedFooter?.let { boundary ->
            val footerOffset = input.offset + input.length - boundary.bytes.size
            if (!matches(input.bytes, footerOffset, boundary.bytes)) {
                return ApplicationFramingResult.Rejected(ApplicationFramingError.FOOTER_MISMATCH)
            }
            ByteRange(input.bytes, footerOffset, boundary.bytes.size)
        }

        val payloadOffset = input.offset + if (scopedHeader?.strip == true) scopedHeader.bytes.size else 0
        val strippedFooterBytes = if (scopedFooter?.strip == true) scopedFooter.bytes.size else 0
        val payloadLength = input.length - (payloadOffset - input.offset) - strippedFooterBytes
        return ApplicationFramingResult.Accepted(
            payload = ByteRange(input.bytes, payloadOffset, payloadLength),
            matchedHeader = matchedHeader,
            matchedFooter = matchedFooter,
        )
    }

    private fun matches(source: ByteArray, offset: Int, expected: ByteArray): Boolean =
        expected.indices.all { source[offset + it] == expected[it] }
}
