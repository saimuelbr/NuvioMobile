package com.nuvio.app.features.plugins.engine

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

object PluginEngineProvider {
    private var _engine: PluginExecutionEngine? = null
    private val log = Logger.withTag("PluginEngineProvider")

    /**
     * Deve ser chamado UMA VEZ no início do app (ex: App.kt init ou MainActivity onCreate).
     * Em Android: PluginEngineProvider.initialize(AndroidQuickJsEngine())
     * Em iOS: PluginEngineProvider.initialize(IosQuickJsEngine())
     */
    fun initialize(engine: PluginExecutionEngine) {
        if (_engine != null) {
            log.w("PluginEngineProvider already initialized")
            return
        }
        _engine = engine
        log.i("PluginEngineProvider initialized with ${engine::class.simpleName}")
    }

    fun get(): PluginExecutionEngine {
        return _engine ?: throw IllegalStateException(
            "PluginExecutionEngine not initialized. Call PluginEngineProvider.initialize() first."
        )
    }
    
    fun isInitialized(): Boolean = _engine != null
}