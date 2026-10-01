package com.filamentvision.backend.persistence.sqlite

import com.filamentvision.core.config.*
import com.filamentvision.core.model.FusionConfiguration
import java.io.*

/** Versioned adapter representation. Core never sees bytes or serialization concerns. */
internal object ConfigurationCodec {
    fun encode(configuration: MonitoringConfiguration): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeInt(1)
            out.vision(configuration.cameraA)
            out.vision(configuration.cameraB)
            with(configuration.fusion) {
                out.writeDouble(cameraAWeight); out.writeDouble(cameraBWeight)
                out.writeDouble(minimumConfidence); out.writeLong(maximumTimestampDifferenceMillis)
            }
            out.writeDouble(configuration.targetDiameter)
            out.writeInt(configuration.writeBatchSize)
            out.writeLong(configuration.flushIntervalMillis)
            out.writeInt(configuration.realtimeCapacity)
            out.writeInt(configuration.previewFramesPerSecond)
            out.writeLong(configuration.publicationIntervalMillis)
        }
    }.toByteArray()

    fun decode(bytes: ByteArray): MonitoringConfiguration = DataInputStream(ByteArrayInputStream(bytes)).use {
        require(it.readInt() == 1) { "Unsupported configuration version" }
        MonitoringConfiguration(
            it.vision(), it.vision(),
            FusionConfiguration(it.readDouble(), it.readDouble(), it.readDouble(), it.readLong()),
            it.readDouble(), it.readInt(), it.readLong(), it.readInt(), it.readInt(), it.readLong(),
        )
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
