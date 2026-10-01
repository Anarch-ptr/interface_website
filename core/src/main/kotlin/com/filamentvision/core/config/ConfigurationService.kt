package com.filamentvision.core.config

import com.filamentvision.core.repository.*

enum class ConfigurationApplyOutcome { APPLIED, REQUIRES_STOP }
data class ConfigurationApplyResult(val saved: ConfigurationRevision, val outcome: ConfigurationApplyOutcome)
/** Implementations serialize this guard with lifecycle transitions and all other applies.
 * The guard must remain held for the entire callback; the state cannot escape it.
 */
interface ConfigurationRuntime {
    fun <T> withConfigurationGuard(action: (ConfigurationRuntimeState) -> T): T
}
interface ConfigurationRuntimeState {
    /** Includes STARTING/STOPPING and failed runtimes retaining an active reservation. */
    val monitoringActive: Boolean
    val configuration: MonitoringConfiguration?
    /** In-memory publication only: must not fail or perform external work. Called after commit. */
    fun publishConfiguration(configuration: MonitoringConfiguration)
}
class ConfigurationService(private val repository: ConfigurationRepository, private val runtime: ConfigurationRuntime) {
    @Synchronized fun save(configuration: MonitoringConfiguration): ConfigurationRevision {
        configuration.validate()
        return repository.saveConfiguration(configuration)
    }
    @Synchronized fun apply(revision: Long, expectedSavedRevision: Long): ConfigurationApplyResult {
        val saved = repository.configuration(revision)
            ?: throw RepositoryException(RepositoryFailure.NOT_FOUND, "Configuration revision not found")
        if (repository.revisions().savedRevision != expectedSavedRevision)
            throw RepositoryException(RepositoryFailure.CONFLICT, "Saved configuration changed")
        val outcome = runtime.withConfigurationGuard { state ->
            val current = state.configuration
            if (state.monitoringActive && (current == null || saved.configuration.requiresRestartFrom(current))) {
                ConfigurationApplyOutcome.REQUIRES_STOP
            } else {
                repository.markApplied(saved.revision, expectedSavedRevision)
                state.publishConfiguration(saved.configuration)
                ConfigurationApplyOutcome.APPLIED
            }
        }
        return ConfigurationApplyResult(saved, outcome)
    }
    @Synchronized fun saveAndApply(configuration: MonitoringConfiguration): ConfigurationApplyResult {
        val saved = save(configuration)
        return apply(saved.revision, saved.revision)
    }
}
