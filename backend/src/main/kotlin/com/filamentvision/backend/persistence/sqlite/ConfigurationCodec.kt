package com.filamentvision.backend.persistence.sqlite

import com.filamentvision.core.config.*
import com.filamentvision.core.model.FusionConfiguration
import java.io.*

/** Versioned adapter representation. Core never sees bytes or serialization concerns. */
internal object ConfigurationCodec {
    private const val LEGACY_VERSION = 1
    private const val VERSION = 2

    fun encode(configuration: MonitoringConfiguration): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            val legacy = configuration.legacy
            out.writeInt(if (legacy == null) VERSION else LEGACY_VERSION)
            out.vision(configuration.vision)
            // Transitional v1 consumers retain exact bytes until their boundary migrates in Tasks 15–17.
            if (legacy != null) {
                out.vision(legacy.cameraB)
                with(legacy.fusion) {
                    out.writeDouble(cameraAWeight); out.writeDouble(cameraBWeight)
                    out.writeDouble(minimumConfidence); out.writeLong(maximumTimestampDifferenceMillis)
                }
            }
            out.writeDouble(configuration.targetDiameter)
            out.writeInt(configuration.writeBatchSize)
            out.writeLong(configuration.flushIntervalMillis)
            out.writeInt(configuration.realtimeCapacity)
            out.writeInt(configuration.previewFramesPerSecond)
            out.writeLong(configuration.publicationIntervalMillis)
        }
    }.toByteArray()

    fun decode(bytes: ByteArray): MonitoringConfiguration = try {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val version = input.readInt()
            require(version == LEGACY_VERSION || version == VERSION) { "Unsupported configuration version" }
            // Camera A is the deterministic Vision source of a v1 blob.
            val vision = input.vision()
            val legacy = if (version == LEGACY_VERSION) LegacyMonitoringConfiguration(input.vision(),
                FusionConfiguration(input.readDouble(), input.readDouble(), input.readDouble(), input.readLong())) else null
            val configuration = MonitoringConfiguration(vision = vision,
                targetDiameter = input.readDouble(), writeBatchSize = input.readInt(),
                flushIntervalMillis = input.readLong(), realtimeCapacity = input.readInt(),
                previewFramesPerSecond = input.readInt(), publicationIntervalMillis = input.readLong(), legacy = legacy)
            require(input.available() == 0) { "Trailing configuration bytes" }
            configuration.validate()
            configuration
        }
    } catch (error: IllegalArgumentException) {
        throw error
    } catch (error: Exception) {
        throw IllegalArgumentException("Malformed stored configuration", error)
    }

    private fun DataOutputStream.vision(value: VisionConfiguration) {
        with(value.calibration) {
            writeInt(roiLeft); writeInt(roiTop); writeInt(roiRight); writeInt(roiBottom)
            writeUTF(grayscaleMethod.name); writeUTF(thresholdMode.name)
            writeInt(manualThreshold); writeInt(edgeSensitivity); writeInt(minimumPixelWidth); writeInt(maximumPixelWidth)
            writeDouble(mmPerPixel); writeDouble(minimumConfidence)
        }
        with(value.geometry) { writeInt(rotationDegrees); writeBoolean(mirrorHorizontally); writeBoolean(mirrorVertically) }
    }
    private fun DataInputStream.vision() = VisionConfiguration(
        CalibrationProfile(readInt(), readInt(), readInt(), readInt(), GrayscaleMethod.valueOf(readUTF()),
            ThresholdMode.valueOf(readUTF()), readInt(), readInt(), readInt(), readInt(), readDouble(), readDouble()),
        ImageGeometry(readInt(), readBoolean(), readBoolean()),
    )
}
