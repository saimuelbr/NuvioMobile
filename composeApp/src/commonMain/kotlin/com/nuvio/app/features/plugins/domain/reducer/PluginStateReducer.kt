package com.nuvio.app.features.plugins.domain.reducer

import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.features.plugins.domain.model.PluginEvent
import com.nuvio.app.features.plugins.domain.model.PluginState

object PluginStateReducer {
    operator fun invoke(currentState: PluginState, event: PluginEvent): PluginState {
        return when (event) {
            is PluginEvent.EnabledStateChanged -> {
                currentState.copy(pluginsEnabled = AppFeaturePolicy.pluginsEnabled)
            }
            is PluginEvent.RefreshStarted -> {
                currentState.copy(
                    isRefreshing = true,
                    error = null
                )
            }
            is PluginEvent.RefreshCompleted -> {
                currentState.copy(
                    isRefreshing = false,
                    lastRefreshTimestamp = if (event.success) System.currentTimeMillis() else currentState.lastRefreshTimestamp
                )
            }
            is PluginEvent.RefreshError -> {
                currentState.copy(
                    isRefreshing = false,
                    error = event.message
                )
            }
            is PluginEvent.ScraperExecuted -> {
                // execution state can be stored separately or in a temporary cache
                currentState // 
            }
            is PluginEvent.Error -> {
                currentState.copy(error = event.message)
            }
        }
    }
}