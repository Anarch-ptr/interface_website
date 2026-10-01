package com.filamentvision.core.runtime

data class StartMonitoringCommand(val commandId: String) { init { require(commandId.isNotBlank()) } }
data class StopMonitoringCommand(val commandId: String) { init { require(commandId.isNotBlank()) } }
enum class StartOutcome { STARTED, ALREADY_RUNNING, INTERRUPTED, FAILED, CLOSED }
data class StartResult(val outcome: StartOutcome, val session: RuntimeSession? = null)
data class StopResult(val session: RuntimeSession? = null, val error: String? = null)
