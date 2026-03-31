package com.udnahc.immichgallery.data.repository

import com.russhwolf.settings.Settings

class ServerConfigRepository(private val settings: Settings) {
    fun getServerUrl(): String = settings.getString("server_url", "")
    fun setServerUrl(url: String) {
        settings.putString("server_url", url)
    }

    fun getApiKey(): String = settings.getString("api_key", "")
    fun setApiKey(key: String) {
        settings.putString("api_key", key)
    }

    fun isLoggedIn(): Boolean = getServerUrl().isNotBlank() && getApiKey().isNotBlank()
    fun getGridColumns(): Int = settings.getInt("grid_columns", 3)
    fun setGridColumns(columns: Int) {
        settings.putInt("grid_columns", columns)
    }

    fun getTimelineGroupSize(): String = settings.getString("timeline_group_size", "MONTH")
    fun setTimelineGroupSize(size: String) {
        settings.putString("timeline_group_size", size)
    }

    fun getTargetRowHeight(): Float = settings.getFloat("target_row_height", 150f)
    fun setTargetRowHeight(height: Float) {
        settings.putFloat("target_row_height", height)
    }

    fun clear() {
        settings.clear()
    }
}
