package com.nuvio.app.features.plugins.bindings

import android.content.Context
import co.touchlab.kermit.Logger
import com.nuvio.app.core.network.IPv4FirstDns
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

object PluginRuntimeBindings {
    private var initialized = false
    lateinit var appContext: Context

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(IPv4FirstDns())
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .proxy(java.net.Proxy.NO_PROXY)
            .cache(Cache(File(appContext.cacheDir, "plugin_http_cache"), 50L * 1024L * 1024L))
            .build()
    }

    val logger: Logger = Logger.withTag("PluginRuntime")

    fun initialize(context: Context) {
        if (!initialized) {
            appContext = context.applicationContext
            initialized = true
            logger.d("PluginRuntimeBindings initialized.")
        }
    }

    fun isInitialized(): Boolean = initialized
}