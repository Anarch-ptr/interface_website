package com.filamentvision.backend.input.profile

data class UdpInputValidationError(
    val path: String,
    val code: String,
    val message: String,
)

data class UdpInputValidationResult(
    val errors: List<UdpInputValidationError>,
) {
    val isValid: Boolean get() = errors.isEmpty()
}

class UdpInputProfileValidator {
    fun validate(profiles: UdpInputProfileSet): UdpInputValidationResult {
        val errors = mutableListOf<UdpInputValidationError>()
        validateProfile("cameraA", UdpCameraId.A, profiles.cameraA, errors)
        validateProfile("cameraB", UdpCameraId.B, profiles.cameraB, errors)
        if (
            profiles.cameraA.enabled &&
            profiles.cameraB.enabled &&
            profiles.cameraA.listenPort == profiles.cameraB.listenPort
        ) {
            errors.outOfRange("cameraA.listenPort", "Enabled cameras must use different listen ports")
            errors.outOfRange("cameraB.listenPort", "Enabled cameras must use different listen ports")
        }
        return UdpInputValidationResult(errors.toList())
    }

    private fun validateProfile(
        path: String,
        expectedCamera: UdpCameraId,
        profile: UdpInputProfile,
        errors: MutableList<UdpInputValidationError>,
    ) {
        if (profile.cameraId != expectedCamera) {
            errors.invalid("$path.cameraId", "Camera profile is assigned to the wrong slot")
        }
        if (profile.listenPort !in 1..65_535) {
            errors.outOfRange("$path.listenPort", "Listen port must be between 1 and 65535")
        }
        profile.sourceIp?.let { sourceIp ->
            if (!isCanonicalIpv4(sourceIp)) {
                errors.invalid("$path.sourceIp", "Version 1 accepts only canonical IPv4 literals")
            }
        }
        validateTransportLimits("$path.transportLimits", profile.transportLimits, errors)
        validatePayload("$path.payload", profile.payload, profile.transportLimits, errors)
        validateFraming("$path.applicationFraming", profile.applicationFraming, errors)
    }

    private fun validateTransportLimits(
        path: String,
        limits: TransportLimits,
        errors: MutableList<UdpInputValidationError>,
    ) {
        if (limits.maxInFlightFrames !in 1..32) {
            errors.outOfRange("$path.maxInFlightFrames", "Maximum in-flight frames must be between 1 and 32")
        }
        if (limits.maxFrameBytes !in 1..UdpProfileHardLimits.MAX_FRAME_BYTES) {
            errors.outOfRange("$path.maxFrameBytes", "Maximum frame bytes must be between 1 and 32 MiB")
        }
        if (limits.frameTimeoutMs !in 100L..10_000L) {
            errors.outOfRange("$path.frameTimeoutMs", "Frame timeout must be between 100 and 10000 ms")
        }
        if (limits.maxChunkCount !in 1..8_192) {
            errors.outOfRange("$path.maxChunkCount", "Maximum chunk count must be between 1 and 8192")
        }
        if (limits.maxChunkPayloadBytes !in 1..60_000) {
            errors.outOfRange("$path.maxChunkPayloadBytes", "Maximum chunk payload must be between 1 and 60000 bytes")
        }
    }

    private fun validatePayload(
        path: String,
        payload: PayloadProfile,
        limits: TransportLimits,
        errors: MutableList<UdpInputValidationError>,
    ) {
        when (payload) {
            is PayloadProfile.Gray8 -> validateRawImage(path, payload.width, payload.height, 1, limits, errors)
            is PayloadProfile.Binary -> validateBinary(path, payload, limits, errors)
            is PayloadProfile.Rgb888 -> validateRawImage(path, payload.width, payload.height, 3, limits, errors)
            is PayloadProfile.Jpeg -> validateJpeg(path, payload, limits, errors)
            is PayloadProfile.Diameter -> validateDiameter(path, payload, errors)
        }
    }

    private fun validateRawImage(
        path: String,
        width: Int,
        height: Int,
        wireBytesPerPixel: Int,
        limits: TransportLimits,
        errors: MutableList<UdpInputValidationError>,
    ) {
        val pixels = validateDimensions(path, width, height, errors) ?: return
        validateDecodedAllocation(path, pixels, errors)
        val wireBytes = checkedMultiply(pixels, wireBytesPerPixel.toLong())
        if (wireBytes == null) {
            errors.overflow("$path.expectedPayloadBytes", "Payload size calculation overflowed")
        } else if (wireBytes > limits.maxFrameBytes.toLong()) {
            errors.outOfRange("$path.expectedPayloadBytes", "Payload exceeds configured maximum frame bytes")
        }
    }

    private fun validateBinary(
        path: String,
        payload: PayloadProfile.Binary,
        limits: TransportLimits,
        errors: MutableList<UdpInputValidationError>,
    ) {
        val pixels = validateDimensions(path, payload.width, payload.height, errors) ?: return
        validateDecodedAllocation(path, pixels, errors)
        val wireBytes = when (payload.binaryPacking) {
            BinaryPacking.BYTE_PER_PIXEL -> pixels
            BinaryPacking.BIT_PACKED -> checkedAdd(pixels, 7L)?.div(8L)
        }
        if (wireBytes == null) {
            errors.overflow("$path.expectedPayloadBytes", "Payload size calculation overflowed")
        } else if (wireBytes > limits.maxFrameBytes.toLong()) {
            errors.outOfRange("$path.expectedPayloadBytes", "Payload exceeds configured maximum frame bytes")
        }
    }

    private fun validateJpeg(
        path: String,
        payload: PayloadProfile.Jpeg,
        limits: TransportLimits,
        errors: MutableList<UdpInputValidationError>,
    ) {
        if (payload.maxFrameBytes !in 1..UdpProfileHardLimits.MAX_FRAME_BYTES) {
            errors.outOfRange("$path.maxFrameBytes", "JPEG maximum frame bytes must be between 1 and 32 MiB")
        } else if (payload.maxFrameBytes > limits.maxFrameBytes) {
            errors.outOfRange("$path.maxFrameBytes", "JPEG maximum frame bytes exceed the transport frame limit")
        }
        validateOptionalDimension("$path.expectedWidth", payload.expectedWidth, UdpProfileHardLimits.MAX_WIDTH, errors)
        validateOptionalDimension("$path.expectedHeight", payload.expectedHeight, UdpProfileHardLimits.MAX_HEIGHT, errors)
        val width = payload.expectedWidth
        val height = payload.expectedHeight
        if (
            width != null && height != null &&
            width in 1..UdpProfileHardLimits.MAX_WIDTH &&
            height in 1..UdpProfileHardLimits.MAX_HEIGHT
        ) {
            val pixels = checkedMultiply(width.toLong(), height.toLong())
            if (pixels == null) {
                errors.overflow("$path.decodedPixels", "Decoded pixel calculation overflowed")
            } else {
                validateDecodedAllocation(path, pixels, errors)
            }
        }
    }

    private fun validateDiameter(
        path: String,
        payload: PayloadProfile.Diameter,
        errors: MutableList<UdpInputValidationError>,
    ) {
        if (!payload.scale.isFinite()) errors.invalid("$path.scale", "Diameter scale must be finite")
        if (!payload.offset.isFinite()) errors.invalid("$path.offset", "Diameter offset must be finite")
        if (!payload.directDiameterFusionConfidence.isFinite() || payload.directDiameterFusionConfidence <= 0.0 || payload.directDiameterFusionConfidence > 1.0) {
            errors.outOfRange(
                "$path.directDiameterFusionConfidence",
                "Direct diameter fusion confidence must be greater than 0 and at most 1",
            )
        }
    }

    private fun validateDimensions(
        path: String,
        width: Int,
        height: Int,
        errors: MutableList<UdpInputValidationError>,
    ): Long? {
        var valid = true
        if (width !in 1..UdpProfileHardLimits.MAX_WIDTH) {
            errors.outOfRange("$path.width", "Width must be between 1 and 8192")
            valid = false
        }
        if (height !in 1..UdpProfileHardLimits.MAX_HEIGHT) {
            errors.outOfRange("$path.height", "Height must be between 1 and 8192")
            valid = false
        }
        if (!valid) return null
        val pixels = checkedMultiply(width.toLong(), height.toLong())
        if (pixels == null) {
            errors.overflow("$path.decodedPixels", "Decoded pixel calculation overflowed")
            return null
        }
        if (pixels > UdpProfileHardLimits.MAX_DECODED_PIXELS) {
            errors.outOfRange("$path.decodedPixels", "Decoded image exceeds the pixel limit")
        }
        return pixels
    }

    private fun validateDecodedAllocation(
        path: String,
        pixels: Long,
        errors: MutableList<UdpInputValidationError>,
    ) {
        val decodedBytes = checkedMultiply(pixels, 4L)
        if (decodedBytes == null) {
            errors.overflow("$path.decodedBytes", "Decoded byte calculation overflowed")
        } else if (decodedBytes > UdpProfileHardLimits.MAX_DECODED_BYTES) {
            errors.outOfRange("$path.decodedBytes", "Decoded image exceeds the byte limit")
        }
    }

    private fun validateOptionalDimension(
        path: String,
        value: Int?,
        maximum: Int,
        errors: MutableList<UdpInputValidationError>,
    ) {
        if (value != null && value !in 1..maximum) {
            errors.outOfRange(path, "Expected dimension must be between 1 and $maximum")
        }
    }

    private fun validateFraming(
        path: String,
        framing: ApplicationFramingProfile,
        errors: MutableList<UdpInputValidationError>,
    ) {
        if (framing.headerEnabled) {
            validateFramingBytes("$path.headerBytes", framing.headerFormat, framing.headerBytes, errors)
        }
        if (framing.footerEnabled) {
            validateFramingBytes("$path.footerBytes", framing.footerFormat, framing.footerBytes, errors)
        }
    }

    private fun validateFramingBytes(
        path: String,
        format: FramingFormat,
        value: String,
        errors: MutableList<UdpInputValidationError>,
    ) {
        val byteCount = when (format) {
            FramingFormat.HEX -> {
                val compact = value.filterNot(Char::isWhitespace)
                if (compact.isEmpty() || compact.length % 2 != 0 || compact.any { it.digitToIntOrNull(16) == null }) {
                    errors.invalid(path, "HEX framing must contain an even number of hexadecimal digits")
                    return
                }
                compact.length / 2
            }
            FramingFormat.ASCII -> {
                if (value.isEmpty() || value.any { it.code > 0x7f }) {
                    errors.invalid(path, "ASCII framing must contain only non-empty ASCII text")
                    return
                }
                value.length
            }
        }
        if (byteCount > UdpProfileHardLimits.MAX_FRAMING_BYTES) {
            errors.outOfRange(path, "Application framing is limited to 64 bytes")
        }
    }

    private fun isCanonicalIpv4(value: String): Boolean {
        val parts = value.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            part.isNotEmpty() &&
                part.all(Char::isDigit) &&
                (part.length == 1 || part.first() != '0') &&
                part.toIntOrNull() in 0..255
        }
    }

    private fun checkedMultiply(left: Long, right: Long): Long? =
        try {
            Math.multiplyExact(left, right)
        } catch (_: ArithmeticException) {
            null
        }

    private fun checkedAdd(left: Long, right: Long): Long? =
        try {
            Math.addExact(left, right)
        } catch (_: ArithmeticException) {
            null
        }

    private fun MutableList<UdpInputValidationError>.invalid(path: String, message: String) {
        add(UdpInputValidationError(path, "INVALID_VALUE", message))
    }

    private fun MutableList<UdpInputValidationError>.outOfRange(path: String, message: String) {
        add(UdpInputValidationError(path, "OUT_OF_RANGE", message))
    }

    private fun MutableList<UdpInputValidationError>.overflow(path: String, message: String) {
        add(UdpInputValidationError(path, "ARITHMETIC_OVERFLOW", message))
    }
}
