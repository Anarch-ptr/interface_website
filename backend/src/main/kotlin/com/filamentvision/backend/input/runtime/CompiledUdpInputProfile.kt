package com.filamentvision.backend.input.runtime

import com.filamentvision.backend.input.decoder.*
import com.filamentvision.backend.input.framing.ApplicationFramingParser
import com.filamentvision.backend.input.profile.*
import com.filamentvision.backend.input.reassembly.*
import com.filamentvision.backend.input.transport.TransportDatagramParser

data class CompiledUdpInputProfile(
    val profile: UdpInputProfile,
    val transportParser: TransportDatagramParser,
    val framingParser: ApplicationFramingParser,
    val reassembler: FrameReassembler,
    val decoder: PayloadDecoder,
) {
    companion object {
        fun compile(profile: UdpInputProfile, reassemblyObserver: StructuredReassemblyObserver = object : StructuredReassemblyObserver {}): CompiledUdpInputProfile {
            val limits = profile.transportLimits
            val reassembler = when (profile.transportMode) {
                TransportMode.SINGLE_DATAGRAM -> SingleDatagramFrameReassembler(limits.maxFrameBytes)
                TransportMode.STRUCTURED_CHUNKED -> StructuredChunkFrameReassembler(
                    maxInFlightFrames = limits.maxInFlightFrames,
                    maxFrameBytes = limits.maxFrameBytes.toLong(),
                    maxChunkCount = limits.maxChunkCount,
                    maxBufferedBytes = minOf(
                        64L * 1024 * 1024,
                        limits.maxFrameBytes.toLong() * limits.maxInFlightFrames,
                    ),
                    frameTimeoutNanos = limits.frameTimeoutMs * 1_000_000,
                    observer = reassemblyObserver,
                )
            }
            return CompiledUdpInputProfile(
                profile = profile,
                transportParser = TransportDatagramParser(),
                framingParser = ApplicationFramingParser(profile.applicationFraming),
                reassembler = reassembler,
                decoder = decoder(profile.payload),
            )
        }

        private fun decoder(payload: PayloadProfile): PayloadDecoder = when (payload) {
            is PayloadProfile.Gray8 -> Gray8Decoder(payload.width, payload.height)
            is PayloadProfile.Binary -> BinaryDecoder(payload.width, payload.height, payload.binaryPacking)
            is PayloadProfile.Rgb888 -> Rgb888Decoder(payload.width, payload.height, payload.channelOrder)
            is PayloadProfile.Jpeg -> JpegDecoder(payload.maxFrameBytes, payload.expectedWidth, payload.expectedHeight)
            is PayloadProfile.Diameter -> DiameterDecoder(
                payload.valueEncoding, payload.byteOrder, payload.scale, payload.offset, payload.unit,
            )
        }
    }
}
