package com.filamentvision.backend.migration.fixtures

import com.filamentvision.backend.api.dto.MeasurementDto

/** Historical characterization vocabulary, kept outside the bundled JSON contract. */
val MeasurementDto.fusedDiameter: Double? get() = diameterMm
val MeasurementDto.diameterA: Double? get() = evidence.singleOrNull { it.cameraId == "A" }?.diameterMm
val MeasurementDto.diameterB: Double? get() = evidence.singleOrNull { it.cameraId == "B" }?.diameterMm
