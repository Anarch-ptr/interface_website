package com.filamentvision.core.config

enum class GrayscaleMethod { LUMA_BT601, AVERAGE }
enum class ThresholdMode { MANUAL, AUTOMATIC }

data class CalibrationProfile(
    val roiLeft: Int = 0,
    val roiTop: Int = 0,
    val roiRight: Int = 0,
    val roiBottom: Int = 0,
    val grayscaleMethod: GrayscaleMethod = GrayscaleMethod.LUMA_BT601,
    val thresholdMode: ThresholdMode = ThresholdMode.MANUAL,
    val manualThreshold: Int = 128,
    val edgeSensitivity: Int = 24,
    val minimumPixelWidth: Int = 1,
    val maximumPixelWidth: Int = 1,
    val mmPerPixel: Double = 0.0,
    val minimumConfidence: Double = 0.7,
)

data class ImageGeometry(
    val rotationDegrees: Int = 0,
    val mirrorHorizontally: Boolean = false,
    val mirrorVertically: Boolean = false,
)

/** Only image processing settings belong here; transport and decoding belong to adapters. */
data class VisionConfiguration(
    val calibration: CalibrationProfile = CalibrationProfile(),
    val geometry: ImageGeometry = ImageGeometry(),
)
