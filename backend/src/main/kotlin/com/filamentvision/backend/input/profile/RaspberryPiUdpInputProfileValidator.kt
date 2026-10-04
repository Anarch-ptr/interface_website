package com.filamentvision.backend.input.profile

import com.filamentvision.backend.input.decoder.DecodeValidationException
import com.filamentvision.backend.input.decoder.DecodedAllocationPolicy

/** Returns exactly two ASCII HEX bytes in the canonical persisted representation. */
fun normalizeTwoByteHex(value: String): String {
    val compact = value.filterNot(Char::isWhitespace)
    require(compact.length == 4 && compact.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
        "Envelope value must contain exactly two HEX bytes"
    }
    val uppercase = compact.uppercase()
    return "${uppercase.substring(0, 2)} ${uppercase.substring(2, 4)}"
}

class RaspberryPiUdpInputProfileValidator {
    fun validate(profile: RaspberryPiUdpInputProfile): UdpInputValidationResult {
        val errors = mutableListOf<UdpInputValidationError>()
        errors += UdpNetworkProfileValidator().validate(profile.network).errors.map { networkError ->
            val legacyPath = when (networkError.path) {
                "listenPort" -> "listenPort"
                "bind.interfaceId" -> "networkInterface.interfaceId"
                "bind.ipv4" -> "networkInterface.ipv4"
                "sourceFilter.hostOrIpv4" -> "raspberryPiIpv4"
                else -> "network.${networkError.path}"
            }
            networkError.copy(path = legacyPath)
        }
        validateLimits(profile.transportLimits, errors)
        validatePayload(profile.payload, profile.transportLimits, errors)
        validateEnvelope("envelope.headerHex", profile.envelope.headerHex, errors)
        validateEnvelope("envelope.footerHex", profile.envelope.footerHex, errors)
        return UdpInputValidationResult(errors.toList())
    }

    private fun validateLimits(limits: TransportLimits, errors: MutableList<UdpInputValidationError>) {
        if (limits.maxInFlightFrames !in 1..32) errors.range("transportLimits.maxInFlightFrames", "Maximum in-flight frames must be between 1 and 32")
        if (limits.maxFrameBytes !in 1..UdpProfileHardLimits.MAX_FRAME_BYTES) errors.range("transportLimits.maxFrameBytes", "Maximum frame bytes must be between 1 and 32 MiB")
        if (limits.frameTimeoutMs !in 100L..10_000L) errors.range("transportLimits.frameTimeoutMs", "Frame timeout must be between 100 and 10000 ms")
        if (limits.maxChunkCount !in 1..65_535) errors.range("transportLimits.maxChunkCount", "Maximum chunk count must be between 1 and 65535")
        if (limits.maxChunkPayloadBytes !in 1..60_000) errors.range("transportLimits.maxChunkPayloadBytes", "Maximum chunk payload must be between 1 and 60000 bytes")
    }

    private fun validatePayload(payload: ImagePayloadProfile, limits: TransportLimits, errors: MutableList<UdpInputValidationError>) {
        when (payload) {
            is ImagePayloadProfile.Jpeg -> {
                if (payload.maxFrameBytes !in 1..UdpProfileHardLimits.MAX_FRAME_BYTES || payload.maxFrameBytes > limits.maxFrameBytes) {
                    errors.range("payload.maxFrameBytes", "JPEG maximum frame bytes must be positive and within the 32 MiB hard and transport limits")
                }
                validateDimension("payload.expectedWidth", payload.expectedWidth, DecodedAllocationPolicy.HARD_MAX_WIDTH, errors)
                validateDimension("payload.expectedHeight", payload.expectedHeight, DecodedAllocationPolicy.HARD_MAX_HEIGHT, errors)
                if (payload.expectedWidth != null && payload.expectedHeight != null) {
                    checkedPixels(payload.expectedWidth, payload.expectedHeight, errors)
                }
            }
            is ImagePayloadProfile.Gray8 -> validateRaw(payload.width, payload.height, limits, errors) { it }
            is ImagePayloadProfile.Binary -> validateRaw(payload.width, payload.height, limits, errors) {
                when (payload.packing) {
                    BinaryPacking.BYTE_PER_PIXEL -> it
                    BinaryPacking.BIT_PACKED -> DecodedAllocationPolicy.ceilDiv(it, 8)
                }
            }
            is ImagePayloadProfile.Rgb888 -> validateRaw(payload.width, payload.height, limits, errors) {
                DecodedAllocationPolicy.checkedMultiply(it, 3)
            }
        }
    }

    private fun validateRaw(
        width: Int,
        height: Int,
        limits: TransportLimits,
        errors: MutableList<UdpInputValidationError>,
        wireBytes: (Long) -> Long,
    ) {
        validateDimension("payload.width", width, DecodedAllocationPolicy.HARD_MAX_WIDTH, errors)
        validateDimension("payload.height", height, DecodedAllocationPolicy.HARD_MAX_HEIGHT, errors)
        val pixels = checkedPixels(width, height, errors) ?: return
        try {
            if (wireBytes(pixels) > limits.maxFrameBytes.toLong()) {
                errors.range("payload.expectedPayloadBytes", "Payload exceeds configured maximum frame bytes")
            }
        } catch (_: DecodeValidationException) {
            errors.add(UdpInputValidationError("payload.expectedPayloadBytes", "ARITHMETIC_OVERFLOW", "Payload size calculation overflowed"))
        }
    }

    private fun checkedPixels(width: Int, height: Int, errors: MutableList<UdpInputValidationError>): Long? {
        if (width !in 1..DecodedAllocationPolicy.HARD_MAX_WIDTH || height !in 1..DecodedAllocationPolicy.HARD_MAX_HEIGHT) return null
        return try {
            DecodedAllocationPolicy.validateImage(width, height).pixelCount.toLong()
        } catch (_: DecodeValidationException) {
            errors.range("payload.decodedPixels", "Decoded image exceeds the pixel or decoded byte hard limit")
            null
        }
    }

    private fun validateDimension(path: String, dimension: Int?, maximum: Int, errors: MutableList<UdpInputValidationError>) {
        if (dimension != null && dimension !in 1..maximum) errors.range(path, "Dimension must be between 1 and $maximum")
    }

    private fun validateEnvelope(path: String, value: String, errors: MutableList<UdpInputValidationError>) {
        try {
            normalizeTwoByteHex(value)
        } catch (_: IllegalArgumentException) {
            errors.invalid(path, "Envelope value must contain exactly two HEX bytes")
        }
    }

    private fun MutableList<UdpInputValidationError>.invalid(path: String, message: String) {
        add(UdpInputValidationError(path, "INVALID_VALUE", message))
    }

    private fun MutableList<UdpInputValidationError>.range(path: String, message: String) {
        add(UdpInputValidationError(path, "OUT_OF_RANGE", message))
    }
}
