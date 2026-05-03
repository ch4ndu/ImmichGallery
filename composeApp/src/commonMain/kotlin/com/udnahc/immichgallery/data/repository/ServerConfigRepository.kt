package com.udnahc.immichgallery.data.repository

import com.russhwolf.settings.Settings
import com.udnahc.immichgallery.domain.model.DEFAULT_TARGET_ROW_HEIGHT
import com.udnahc.immichgallery.domain.model.RowHeightScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class ServerConfigRepository(private val settings: Settings) {
    private val _timelineGroupSize = MutableStateFlow(settings.getString("timeline_group_size", "MONTH"))

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
    fun observeTimelineGroupSize(): StateFlow<String> = _timelineGroupSize.asStateFlow()
    fun setTimelineGroupSize(size: String) {
        settings.putString("timeline_group_size", size)
        _timelineGroupSize.value = size
    }

    fun getTargetRowHeight(scope: RowHeightScope): Float {
        val fallback = if (scope == RowHeightScope.TIMELINE) {
            settings.getFloat("target_row_height", DEFAULT_TARGET_ROW_HEIGHT)
        } else {
            DEFAULT_TARGET_ROW_HEIGHT
        }
        return settings.getFloat(targetRowHeightKey(scope), fallback)
    }

    fun setTargetRowHeight(scope: RowHeightScope, height: Float) {
        settings.putFloat(targetRowHeightKey(scope), height)
    }

    fun clear() {
        settings.clear()
        _timelineGroupSize.value = "MONTH"
    }

    private fun targetRowHeightKey(scope: RowHeightScope): String =
        when (scope) {
            RowHeightScope.TIMELINE -> "timeline_target_row_height"
            RowHeightScope.ALBUM_DETAIL -> "album_detail_target_row_height"
            RowHeightScope.PERSON_DETAIL -> "person_detail_target_row_height"
            RowHeightScope.SEARCH -> "search_target_row_height"
        }
}
