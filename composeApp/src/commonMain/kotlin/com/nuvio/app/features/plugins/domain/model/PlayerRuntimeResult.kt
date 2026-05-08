package com.nuvio.app.features.plugins.domain.model

data class PluginRuntimeResult(
    val url: String,
    val title: String? = null,
    val name: String? = null,
    val quality: String? = null,
    val size: String? = null, 
    val language: String? = null, 
    val provider: String? = null, 
    val type: String? = null, 
    val seeders: Int? = null, // can be used when torrent has been implemented
    val peers: Int? = null, // can be used when torrent has been implemented
    val infoHash: String? = null, // can be used when torrent has been implemented
    val headers: Map<String, String> = emptyMap()
)