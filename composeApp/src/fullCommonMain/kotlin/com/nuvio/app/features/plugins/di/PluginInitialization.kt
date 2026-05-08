package com.nuvio.app.features.plugins.di

import com.nuvio.app.features.plugins.engine.PluginOrchestratorProvider

fun initializePluginSystem() {
    val executionEngine = getKoinInstance<PluginExecutionEngine>()
    val scope = getKoinInstance<CoroutineScope>()
    PluginOrchestratorProvider.initialize(executionEngine, scope)
}