package com.udnahc.immichgallery.data.repository

import com.russhwolf.settings.Settings
import com.udnahc.immichgallery.domain.model.DEFAULT_GRID_COLUMN_COUNT
import com.udnahc.immichgallery.domain.model.DEFAULT_TARGET_ROW_HEIGHT
import com.udnahc.immichgallery.domain.model.MosaicTemplateFamily
import com.udnahc.immichgallery.domain.model.RowHeightScope
import com.udnahc.immichgallery.domain.model.ViewConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ServerConfigRepository(private val settings: Settings) {
    private val _timelineGroupSize = MutableStateFlow(settings.getString("timeline_group_size", "MONTH"))
    private val _viewConfig = MutableStateFlow(readViewConfig())

    fun getServerUrl(): String = settings.getString("server_url", "")
    fun setServerUrl(url: String) {
        settings.putString("server_url", url)
    }

    fun getApiKey(): String = settings.getString("api_key", "")
    fun setApiKey(key: String) {
        settings.putString("api_key", key)
    }

    fun isLoggedIn(): Boolean = getServerUrl().isNotBlank() && getApiKey().isNotBlank()
    fun getGridColumns(): Int = settings.getInt("grid_columns", DEFAULT_GRID_COLUMN_COUNT)
    fun setGridColumns(columns: Int) {
        settings.putInt("grid_columns", columns)
    }

    fun getTimelineGroupSize(): String = settings.getString("timeline_group_size", "MONTH")
    fun observeTimelineGroupSize(): StateFlow<String> = _timelineGroupSize.asStateFlow()
    fun setTimelineGroupSize(size: String) {
        settings.putString("timeline_group_size", size)
        _timelineGroupSize.value = size
    }

    fun getViewConfig(): ViewConfig = _viewConfig.value
    fun observeViewConfig(): StateFlow<ViewConfig> = _viewConfig.asStateFlow()
    fun setViewConfig(config: ViewConfig) {
        val normalized = config.normalized
        settings.putBoolean(VIEW_CONFIG_MOSAIC_ENABLED_KEY, normalized.mosaicEnabled)
        settings.putString(
            VIEW_CONFIG_MOSAIC_FAMILIES_KEY,
            normalized.mosaicFamilies.joinToString(",") { it.persistedId }
        )
        _viewConfig.value = normalized
    }

    fun getTargetRowHeight(scope: RowHeightScope): Float {
        val fallback = if (scope == RowHeightScope.TIMELINE) {
            settings.getFloat(LEGACY_TIMELINE_TARGET_ROW_HEIGHT_KEY, DEFAULT_TARGET_ROW_HEIGHT)
        } else {
            DEFAULT_TARGET_ROW_HEIGHT
        }
        return settings.getFloat(targetRowHeightKey(scope), fallback)
    }

    fun hasTargetRowHeight(scope: RowHeightScope): Boolean =
        settings.hasKey(targetRowHeightKey(scope)) ||
            (scope == RowHeightScope.TIMELINE && settings.hasKey(LEGACY_TIMELINE_TARGET_ROW_HEIGHT_KEY))

    fun setTargetRowHeight(scope: RowHeightScope, height: Float) {
        settings.putFloat(targetRowHeightKey(scope), height)
    }

    fun clear() {
        settings.clear()
        _timelineGroupSize.value = "MONTH"
        _viewConfig.value = readViewConfig()
    }

    private fun readViewConfig(): ViewConfig {
        val savedFamilies = settings
            .getString(VIEW_CONFIG_MOSAIC_FAMILIES_KEY, "")
            .split(",")
            .mapNotNull { id -> MosaicTemplateFamily.fromPersistedId(id.trim()) }
            .toSet()
        return ViewConfig(
            mosaicEnabled = settings.getBoolean(
                VIEW_CONFIG_MOSAIC_ENABLED_KEY,
                ViewConfig().mosaicEnabled
            ),
            mosaicFamilies = savedFamilies.ifEmpty { MosaicTemplateFamily.defaultSet() }
        )
    }

    private fun targetRowHeightKey(scope: RowHeightScope): String =
        when (scope) {
            RowHeightScope.TIMELINE -> "timeline_target_row_height"
            RowHeightScope.ALBUM_DETAIL -> "album_detail_target_row_height"
            RowHeightScope.PERSON_DETAIL -> "person_detail_target_row_height"
            RowHeightScope.SEARCH -> "search_target_row_height"
        }

    private companion object {
        const val LEGACY_TIMELINE_TARGET_ROW_HEIGHT_KEY = "target_row_height"
        const val VIEW_CONFIG_MOSAIC_ENABLED_KEY = "view_config_mosaic_enabled"
        const val VIEW_CONFIG_MOSAIC_FAMILIES_KEY = "view_config_mosaic_families"
    }
}
