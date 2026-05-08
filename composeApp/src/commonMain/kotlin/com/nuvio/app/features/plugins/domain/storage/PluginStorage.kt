package com.nuvio.app.features.plugins.domain.storage

expect class PluginStorage {
    suspend fun loadState(profileId: Int): String?
    suspend fun saveState(profileId: Int, json: String)
    suspend fun clearProfileData(profileId: Int)
}