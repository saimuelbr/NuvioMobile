package com.nuvio.app.features.plugins

expect object PluginCrypto {
    fun sha256(input: String): String
    fun hmacSha256(input: String, key: String): String
}