package com.nuvio.app.features.plugins.bindings

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import platform.Foundation.NSCache
import platform.Foundation.NSURLCache
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData

object PluginRuntimeBindings {
    private var initialized = false
    val logger: Logger = Logger.withTag("PluginRuntime")

    val httpClient: HttpClient by lazy {
        HttpClient(Darwin) {
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 30_000
                socketTimeoutMillis = 30_000
            }

            engine {
                configureRequest {
                    setAllowsCellularAccess(true)
                    setHTTPShouldHandleCookies(true)
                    
                    val cache = NSURLCache(
                        memoryCapacity = 10 * 1024 * 1024, 
                        diskCapacity = 50 * 1024 * 1024,   
                        diskPath = null
                    )
                    setCache(cache)
                    
                    setCachePolicy(NSURLRequestReloadIgnoringLocalCacheData)
                }
            }
        }
    }

    fun initialize() {
        if (!initialized) {
            logger.d("PluginRuntimeBindings initialized.")
            initialized = true
        }
    }

    fun isInitialized(): Boolean = initialized
}