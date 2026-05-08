package com.nuvio.app.features.plugins.engine

import co.touchlab.kermit.Logger
import com.nuvio.app.features.plugins.domain.model.PluginRuntimeResult
import com.nuvio.app.features.plugins.domain.model.PluginScraper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "PluginOrchestrator"
private const val SCRAPER_EXECUTION_TIMEOUT_MS = 60_000L

/**
 * Orquestra a execução de scrapers, gerenciando concorrência e delegando à engine JS.
 * Substitui a chamada direta a PluginRuntime.executePlugin no PluginRepository original.
 */
class PluginOrchestrator(
    private val executionEngine: PluginExecutionEngine,
    private val logger: Logger = Logger.withTag(TAG),
    private val appScope: CoroutineScope
) {
    private val activeJobs = mutableMapOf<String, Job>()
    private val executionMutex = Mutex()
    
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 16) // Exemplo de evento
    val events: SharedFlow<String> = _events.asSharedFlow()

    /**
     * Executa um scraper com contexto isolado e timeout.
     */
    suspend fun executeScraper(
        scraper: PluginScraper,
        params: Map<String, Any>
    ): Result<List<PluginRuntimeResult>> = executionMutex.withLock {
        val cacheKey = "${scraper.id}_${params.hashCode()}"
        // Cancela execuções anteriores do mesmo scraper com os mesmos params
        activeJobs[cacheKey]?.cancel()
        
        val job = appScope.launch {
            try {
                val results = withTimeoutOrNull(scraper.timeoutMs ?: SCRAPER_EXECUTION_TIMEOUT_MS) {
                    executionEngine.executeScraper(
                        code = scraper.code,
                        scraperName = scraper.name,
                        params = params,
                        timeoutMs = scraper.timeoutMs ?: SCRAPER_EXECUTION_TIMEOUT_MS
                    ).onFailure { error ->
                        logger.e("Scraper '${scraper.name}' failed", error)
                        _events.emit("SCRAPER_ERROR: ${scraper.name}: ${error.message}")
                    }.getOrNull()
                }
                
                if (results == null) {
                    logger.w("Scraper '${scraper.name}' timed out after ${SCRAPER_EXECUTION_TIMEOUT_MS}ms")
                    Result.failure(TimeoutException("Scraper execution timed out"))
                } else {
                    _events.emit("SCRAPER_SUCCESS: ${scraper.name}: ${results.size} results")
                    Result.success(results)
                }
            } catch (e: Exception) {
                if (e is CancellationException) {
                    Result.failure(e) // Propaga cancelamento
                } else {
                    logger.e("Unexpected error executing scraper '${scraper.name}'", e)
                    Result.failure(e)
                }
            } finally {
                activeJobs.remove(cacheKey)
            }
        }
        
        activeJobs[cacheKey] = job
        
        try {
            job.join()
            // O resultado real é retornado pelo job via Result
            job.getCompletionExceptionOrNull()?.let { throw it }
            // Neste modelo, o resultado é retornado diretamente pelo bloco do job
            // Na prática, o job lança a exception ou o resultado é obtido de outra forma
            // Vamos corrigir para retornar o resultado do engine de forma síncrona
            // O código acima é mais adequado para fire-and-forget
            // Para obter o resultado, usamos a chamada direta com timeout
            withTimeoutOrNull(scraper.timeoutMs ?: SCRAPER_EXECUTION_TIMEOUT_MS) {
                executionEngine.executeScraper(
                    code = scraper.code,
                    scraperName = scraper.name,
                    params = params,
                    timeoutMs = scraper.timeoutMs ?: SCRAPER_EXECUTION_TIMEOUT_MS
                )
            } ?: Result.failure(TimeoutException("Scraper execution timed out"))
            
        } catch (e: CancellationException) {
            Result.failure(e)
        }
    }

    /**
     * Cancela todas as execuções ativas.
     */
    fun cancelAllJobs() {
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
        executionEngine.cancelActiveJobs()
    }
}