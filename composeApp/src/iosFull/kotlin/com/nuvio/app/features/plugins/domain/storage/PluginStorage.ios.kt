package com.nuvio.app.features.plugins.domain.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSUserDefaults

actual class PluginStorage {
    private fun defaults(profileId: Int) = NSUserDefaults(suiteName = "nuvio.plugins.p$profileId")

    actual suspend fun loadState(profileId: Int): String? = withContext(Dispatchers.Default) {
        defaults(profileId).stringForKey("state_json")
    }

    actual suspend fun saveState(profileId: Int, json: String) = withContext(Dispatchers.Default) {
        defaults(profileId).setObject(json, "state_json")
        defaults(profileId).synchronize() 
    }

    actual suspend fun clearProfileData(profileId: Int) = withContext(Dispatchers.Default) {
        defaults(profileId).removeObjectForKey("state_json")
        defaults(profileId).synchronize()
    }
}