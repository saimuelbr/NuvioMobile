package com.nuvio.app.features.plugins.domain.model

sealed interface PluginEvent {
    data class RefreshStarted(val repositoryUrl: String? = null) : PluginEvent
    data class RefreshCompleted(val success: Boolean, val repositoryUrl: String? = null) : PluginEvent
    data class RefreshError(val message: String, val repositoryUrl: String? = null) : PluginEvent
    data class ScraperExecuted(val scraperId: String, val results: List<PluginRuntimeResult>) : PluginEvent
    data class Error(val message: String) : PluginEvent
    object EnabledStateChanged : PluginEvent
}