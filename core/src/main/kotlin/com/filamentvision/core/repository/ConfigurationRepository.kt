package com.filamentvision.core.repository

import com.filamentvision.core.config.MonitoringConfiguration

data class ConfigurationRevision(val revision: Long, val configuration: MonitoringConfiguration)
data class ConfigurationRevisions(val savedRevision: Long? = null, val appliedRevision: Long? = null)
interface ConfigurationRepository {
    fun saveConfiguration(configuration: MonitoringConfiguration): ConfigurationRevision
    fun configuration(revision: Long): ConfigurationRevision?
    fun revisions(): ConfigurationRevisions
    /** Compare-and-set against latest saved revision; selected revision must exist. */
    fun markApplied(revision: Long, expectedSavedRevision: Long)
}
