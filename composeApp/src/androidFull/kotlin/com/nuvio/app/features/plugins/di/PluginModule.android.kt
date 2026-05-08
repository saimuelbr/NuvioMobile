package com.nuvio.app.features.plugins.di

import com.nuvio.app.features.plugins.engine.AndroidQuickJsEngine
import com.nuvio.app.features.plugins.engine.PluginExecutionEngine
import com.nuvio.app.features.plugins.engine.PluginOrchestrator
import com.nuvio.app.features.plugins.engine.PluginOrchestratorProvider
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.koin.dsl.module

val androidPluginModule = module {
    single<PluginExecutionEngine> { 
        AndroidQuickJsEngine(httpClient = get()) 
    }
    
    single { 
        PluginOrchestrator(
            executionEngine = get(),
            appScope = get<CoroutineScope>()
        ) 
    }
    
    single<CoroutineScope> { CoroutineScope(Dispatchers.Default) }
}