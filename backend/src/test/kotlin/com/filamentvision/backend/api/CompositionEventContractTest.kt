package com.filamentvision.backend.api

import com.filamentvision.backend.api.dto.toDto
import com.filamentvision.core.event.*
import com.filamentvision.core.repository.ConfigurationRevisions
import com.filamentvision.core.runtime.*
import org.junit.Assert.*
import org.junit.Test

class CompositionEventContractTest {
    @Test fun neutralMeasurementSerializationNeverInventsLegacyFusionEvidence() {
        val neutral = com.filamentvision.core.model.OfficialMeasurement(
            evidence = listOf(sample.evidence.single()),
            diameterMm = 1.75, confidence = 0.9, status = com.filamentvision.core.model.MeasurementStatus.NORMAL,
            timestamp = 1001)
        val record = com.filamentvision.core.repository.MeasurementRecord("neutral", "s1", neutral, 2)
        val hub = SequencedEventHub(setOf("device"), 4)
        val json = apiJson.readTree(apiJson.writeValueAsString(hub.publish(DomainEvent.Measurement("device", record)).toDto()))
        val measurement = json["payload"]["measurement"]
        assertEquals(1.75, measurement.path("diameterMm").asDouble(), 0.0)
        assertEquals(1, measurement.path("evidence").size())
        assertEquals("camera", measurement.path("evidence").path(0).path("cameraId").asText())
        for (field in listOf("cameraA", "cameraB", "diameterA", "diameterB", "fusedDiameter", "shapeDifference", "fusionReason"))
            assertFalse(field, measurement.has(field))
        assertEquals("NORMAL", measurement["status"].asText())
        assertEquals(1001L, measurement["timestamp"].asLong())
    }

    @Test fun configurationSerializationHasOneVisionAndNoFusionConfiguration() {
        val tree = apiJson.readTree(apiJson.writeValueAsString(com.filamentvision.core.config.MonitoringConfiguration().toDto()))
        assertTrue(tree.has("vision"))
        assertFalse(tree.has("cameraA")); assertFalse(tree.has("cameraB")); assertFalse(tree.has("fusion"))
    }

    @Test fun stateAndConfigurationChangesRetainSequenceAndSafeTypedPayload() {
        val hub = SequencedEventHub(setOf("simulator-1"), 4)
        val state = hub.publish(DomainEvent.State("simulator-1", MonitoringState(lifecycle = MonitoringLifecycle.ERROR, error = "secret"), 10)).toDto()
        val config = hub.publish(DomainEvent.Configuration("simulator-1", ConfigurationRevisions(3, 2), 11)).toDto()
        assertEquals("state.changed", state.type)
        assertEquals(1L, state.sequence)
        assertFalse(apiJson.writeValueAsString(state).contains("secret"))
        assertEquals("configuration.changed", config.type)
        assertEquals(2L, config.sequence)
        assertEquals(11L, config.occurredAt)
        assertEquals(2L, apiJson.readTree(apiJson.writeValueAsString(config))["payload"]["appliedRevision"].asLong())
    }
}
