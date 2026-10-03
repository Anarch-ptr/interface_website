package com.filamentvision.core.measurement

import com.filamentvision.core.model.OfficialMeasurement
import com.filamentvision.core.vision.CameraMeasurement

interface MeasurementAssembler {
    fun reset()
    fun accept(evidence: CameraMeasurement): List<OfficialMeasurement>
}
