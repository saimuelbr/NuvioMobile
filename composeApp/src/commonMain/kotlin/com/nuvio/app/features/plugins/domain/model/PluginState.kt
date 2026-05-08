package com.nuvio.app.features.plugins.domain.model

data class PluginState(
    val pluginsEnabled: Boolean = false,
    val isRefreshing: Boolean = false,
    val repositories: List<PluginRepository> = emptyList(),
    val activeScrapers: List<PluginScraper> = emptyList(),
    val lastRefreshTimestamp: Long = 0L,
    val error: String? = null
)

data class PluginRepository(
    val url: String,
    val name: String,
    val version: String,
    val author: String,
    val supportedPlatforms: List<String>, // test
    val disabledPlatforms: List<String> = emptyList(), // test
    val scrapers: List<PluginScraper>
)

data class PluginScraper(
    val id: String,
    val name: String,
    val description: String,
    val iconUrl: String?,
    val code: String, 
    val timeoutMs: Long? = null 
)

data class PluginRuntimeResult(
    val streamUrl: String,
    val qualityLabel: String? = null,
    val languageCode: String? = null,
    val isDubbed: Boolean = false,
    val isSubtitled: Boolean = false,
    val headers: Map<String, String> = emptyMap()
)