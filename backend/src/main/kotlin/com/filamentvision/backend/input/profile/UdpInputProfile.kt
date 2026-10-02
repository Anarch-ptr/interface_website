package com.filamentvision.backend.input.profile

enum class UdpCameraId { A, B }

enum class TransportMode { SINGLE_DATAGRAM, STRUCTURED_CHUNKED }

enum class BinaryPacking { BYTE_PER_PIXEL, BIT_PACKED }

enum class RgbChannelOrder { RGB, BGR }

enum class DiameterValueEncoding { FLOAT32, FLOAT64, INT16, INT32, ASCII }

enum class DiameterByteOrder { BIG_ENDIAN, LITTLE_ENDIAN }

enum class DiameterUnit { MM, MICROMETER, INCH }

enum class FramingFormat { HEX, ASCII }

enum class FramingScope { DATAGRAM, FRAME }

sealed interface PayloadProfile {
    data class Gray8(val width: Int, val height: Int) : PayloadProfile

    data class Binary(
        val width: Int,
        val height: Int,
        val binaryPacking: BinaryPacking,
    ) : PayloadProfile

    data class Jpeg(
        val maxFrameBytes: Int,
        val expectedWidth: Int? = null,
        val expectedHeight: Int? = null,
    ) : PayloadProfile

    data class Rgb888(
        val width: Int,
        val height: Int,
        val channelOrder: RgbChannelOrder,
    ) : PayloadProfile

    data class Diameter(
        val valueEncoding: DiameterValueEncoding,
        val byteOrder: DiameterByteOrder,
        val scale: Double = 1.0,
        val offset: Double = 0.0,
        val unit: DiameterUnit = DiameterUnit.MM,
        val directDiameterFusionConfidence: Double = 1.0,
    ) : PayloadProfile
}

data class ApplicationFramingProfile(
    val headerEnabled: Boolean = false,
    val headerFormat: FramingFormat = FramingFormat.HEX,
    val headerBytes: String = "",
    val headerScope: FramingScope = FramingScope.DATAGRAM,
    val stripHeader: Boolean = true,
    val footerEnabled: Boolean = false,
    val footerFormat: FramingFormat = FramingFormat.HEX,
    val footerBytes: String = "",
    val footerScope: FramingScope = FramingScope.DATAGRAM,
    val stripFooter: Boolean = true,
)

data class TransportLimits(
    val maxInFlightFrames: Int = 4,
    val maxFrameBytes: Int = 8 * 1024 * 1024,
    val frameTimeoutMs: Long = 1_000,
    val maxChunkCount: Int = 4_096,
    val maxChunkPayloadBytes: Int = 1_400,
)

data class UdpInputProfile(
    val cameraId: UdpCameraId,
    val enabled: Boolean,
    val sourceIp: String?,
    val listenPort: Int,
    val transportMode: TransportMode,
    val payload: PayloadProfile,
    val applicationFraming: ApplicationFramingProfile,
    val transportLimits: TransportLimits,
)

data class UdpInputProfileSet(
    val cameraA: UdpInputProfile,
    val cameraB: UdpInputProfile,
)

internal object UdpProfileHardLimits {
    const val MAX_WIDTH = 8_192
    const val MAX_HEIGHT = 8_192
    const val MAX_DECODED_PIXELS = 16_777_216L
    const val MAX_DECODED_BYTES = 64L * 1024 * 1024
    const val MAX_FRAME_BYTES = 32 * 1024 * 1024
    const val MAX_FRAMING_BYTES = 64
}
