package com.nuvio.app.features.plugins.engine

import com.nuvio.app.features.plugins.domain.model.PluginRuntimeResult

interface PluginExecutionEngine {
    suspend fun executeScraper(
        code: String,
        scraperName: String,
        params: Map<String, Any>,
        timeoutMs: Long
    ): Result<List<PluginRuntimeResult>>

    fun cancelActiveJobs()
}