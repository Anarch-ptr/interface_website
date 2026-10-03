package com.filamentvision.backend.input.runtime

import com.filamentvision.backend.input.decoder.*
import com.filamentvision.backend.input.framing.DatagramEnvelopeParser
import com.filamentvision.backend.input.profile.*
import com.filamentvision.backend.input.reassembly.StructuredChunkFrameReassembler
import com.filamentvision.backend.input.reassembly.StructuredReassemblyObserver
import com.filamentvision.backend.input.transport.TransportDatagramParser

/** One synchronous chain. Its reassembler belongs to exactly one processor. */
class CompiledRaspberryPiInputProfile internal constructor(
    val profile: RaspberryPiUdpInputProfile,
    internal val envelopeParser: DatagramEnvelopeParser,
    internal val transportParser: TransportDatagramParser,
    internal val reassembler: StructuredChunkFrameReassembler,
    internal val decoder: PayloadDecoder,
) {
    companion object {
        fun compile(profile: RaspberryPiUdpInputProfile,
            reassemblyObserver: StructuredReassemblyObserver = object : StructuredReassemblyObserver {},
        ): CompiledRaspberryPiInputProfile {
            require(RaspberryPiUdpInputProfileValidator().validate(profile).isValid) { "Invalid Raspberry Pi UDP input profile" }
            val canonical = profile.copy(envelope = DatagramEnvelopeProfile(
                normalizeTwoByteHex(profile.envelope.headerHex), normalizeTwoByteHex(profile.envelope.footerHex),
            ))
            val limits = canonical.transportLimits
            return CompiledRaspberryPiInputProfile(
                canonical,
                DatagramEnvelopeParser(boundary(canonical.envelope.headerHex), boundary(canonical.envelope.footerHex)),
                TransportDatagramParser(),
                StructuredChunkFrameReassembler(
                    maxInFlightFrames = limits.maxInFlightFrames,
                    maxFrameBytes = limits.maxFrameBytes.toLong(),
                    maxChunkCount = limits.maxChunkCount,
                    maxBufferedBytes = minOf(64L * 1024 * 1024, limits.maxFrameBytes.toLong() * limits.maxInFlightFrames),
                    frameTimeoutNanos = limits.frameTimeoutMs * 1_000_000,
                    observer = reassemblyObserver,
                ),
                when (val payload = canonical.payload) {
                    is ImagePayloadProfile.Jpeg -> JpegDecoder(payload.maxFrameBytes, payload.expectedWidth, payload.expectedHeight)
                    is ImagePayloadProfile.Gray8 -> Gray8Decoder(payload.width, payload.height)
                    is ImagePayloadProfile.Binary -> BinaryDecoder(payload.width, payload.height, payload.packing)
                    is ImagePayloadProfile.Rgb888 -> Rgb888Decoder(payload.width, payload.height, payload.channelOrder)
                },
            )
        }

        private fun boundary(canonicalHex: String): ByteArray =
            canonicalHex.split(' ').map { it.toInt(16).toByte() }.toByteArray()
    }
}
