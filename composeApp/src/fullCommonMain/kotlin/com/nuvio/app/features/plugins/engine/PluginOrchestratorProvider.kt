package com.nuvio.app.features.plugins.engine

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

object PluginOrchestratorProvider {
    private var instance: PluginOrchestrator? = null
    private val logger = Logger.withTag("PluginOrchestratorProvider")

    fun initialize(
        executionEngine: PluginExecutionEngine,
        httpClient: HttpClient, // can be used in the future
        scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
    ) {
        if (instance == null) {
            instance = PluginOrchestrator(
                executionEngine = executionEngine,
                logger = logger,
                appScope = scope
            )
        }
    }

    fun get(): PluginOrchestrator {
        return instance ?: throw IllegalStateException(
            "PluginOrchestrator not initialized. Call PluginOrchestratorProvider.initialize() first."
        )
    }

    fun reset() {
        instance?.cancelAllJobs()
        instance = null
    }
}