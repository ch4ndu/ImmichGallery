package com.udnahc.immichgallery.data.repository

import com.russhwolf.settings.Settings

class ServerConfigRepository(private val settings: Settings) {
    fun getServerUrl(): String = settings.getString("server_url", "")
    fun setServerUrl(url: String) { settings.putString("server_url", url) }
    fun getApiKey(): String = settings.getString("api_key", "")
    fun setApiKey(key: String) { settings.putString("api_key", key) }
    fun isLoggedIn(): Boolean = getServerUrl().isNotBlank() && getApiKey().isNotBlank()
    fun clear() { settings.clear() }
}
