package com.filamentvision.backend.api

import com.filamentvision.backend.api.dto.toDto
import com.filamentvision.core.event.*
import com.filamentvision.core.repository.ConfigurationRevisions
import com.filamentvision.core.runtime.*
import org.junit.Assert.*
import org.junit.Test

class CompositionEventContractTest {
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
