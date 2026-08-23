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
    private val _session = MutableStateFlow(
        ServerSessionConfiguration(
            serverUrl = settings.getString(SERVER_URL_KEY, ""),
            apiKey = settings.getString(API_KEY_KEY, "")
        )
    )
    private val _timelineGroupSize =
        MutableStateFlow(settings.getString(TIMELINE_GROUP_SIZE_KEY, "MONTH"))
    private val _viewConfig = MutableStateFlow(readViewConfig())

    fun getServerUrl(): String = _session.value.serverUrl
    private fun setServerUrl(url: String) {
        settings.putString(SERVER_URL_KEY, url)
    }

    fun getApiKey(): String = _session.value.apiKey
    private fun setApiKey(key: String) {
        settings.putString(API_KEY_KEY, key)
    }

    internal fun currentSession(): ServerSessionConfiguration = _session.value

    internal fun replaceSession(serverUrl: String, apiKey: String) {
        val previous = _session.value
        val replacement = ServerSessionConfiguration(serverUrl, apiKey)
        try {
            setServerUrl(replacement.serverUrl)
            setApiKey(replacement.apiKey)
        } catch (error: Throwable) {
            runCatching {
                setServerUrl(previous.serverUrl)
                setApiKey(previous.apiKey)
            }
            throw error
        }
        _session.value = replacement
    }

    fun isLoggedIn(): Boolean = getServerUrl().isNotBlank() && getApiKey().isNotBlank()
    fun getGridColumns(): Int = settings.getInt(GRID_COLUMNS_KEY, DEFAULT_GRID_COLUMN_COUNT)
    fun setGridColumns(columns: Int) {
        settings.putInt(GRID_COLUMNS_KEY, columns)
    }

    fun getTimelineGroupSize(): String = settings.getString(TIMELINE_GROUP_SIZE_KEY, "MONTH")
    fun observeTimelineGroupSize(): StateFlow<String> = _timelineGroupSize.asStateFlow()
    fun setTimelineGroupSize(size: String) {
        settings.putString(TIMELINE_GROUP_SIZE_KEY, size)
        _timelineGroupSize.value = size
    }

    fun getViewConfig(): ViewConfig = _viewConfig.value
    fun observeViewConfig(): StateFlow<ViewConfig> = _viewConfig.asStateFlow()
    fun setViewConfig(config: ViewConfig) {
        val normalized = config.normalized
        settings.putBoolean(VIEW_CONFIG_MOSAIC_ENABLED_KEY, normalized.mosaicEnabled)
        settings.putBoolean(VIEW_CONFIG_CACHE_MOSAIC_RESULTS_KEY, normalized.cacheMosaicResults)
        settings.putBoolean(
            VIEW_CONFIG_DISABLE_MOSAIC_ZOOM_KEY,
            normalized.disableZoomWhenMosaicEnabled
        )
        settings.putInt(VIEW_CONFIG_MOSAIC_COLUMN_COUNT_KEY, normalized.mosaicColumnCount)
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
                (scope == RowHeightScope.TIMELINE && settings.hasKey(
                    LEGACY_TIMELINE_TARGET_ROW_HEIGHT_KEY
                ))

    fun setTargetRowHeight(
        scope: RowHeightScope,
        height: Float
    ) {
        settings.putFloat(targetRowHeightKey(scope), height)
    }

    fun clear() {
        settings.clear()
        _session.value = ServerSessionConfiguration()
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
            mosaicFamilies = savedFamilies.ifEmpty { MosaicTemplateFamily.defaultSet() },
            cacheMosaicResults = settings.getBoolean(
                VIEW_CONFIG_CACHE_MOSAIC_RESULTS_KEY,
                ViewConfig().cacheMosaicResults
            ),
            disableZoomWhenMosaicEnabled = settings.getBoolean(
                VIEW_CONFIG_DISABLE_MOSAIC_ZOOM_KEY,
                ViewConfig().disableZoomWhenMosaicEnabled
            ),
            mosaicColumnCount = settings.getInt(
                VIEW_CONFIG_MOSAIC_COLUMN_COUNT_KEY,
                ViewConfig().mosaicColumnCount
            )
        )
    }

    private fun targetRowHeightKey(scope: RowHeightScope): String =
        when (scope) {
            RowHeightScope.TIMELINE -> TIMELINE_TARGET_ROW_HEIGHT_KEY
            RowHeightScope.ALBUM_DETAIL -> ALBUM_TARGET_ROW_HEIGHT_KEY
            RowHeightScope.PERSON_DETAIL -> PERSON_TARGET_ROW_HEIGHT_KEY
            RowHeightScope.SEARCH -> SEARCH_TARGET_ROW_HEIGHT_KEY
            RowHeightScope.FAVORITES -> FAVORITES_TARGET_ROW_HEIGHT_KEY
        }

}

internal data class ServerSessionConfiguration(
    val serverUrl: String = "",
    val apiKey: String = ""
)

internal enum class ServerSettingValueType {
    STRING,
    INT,
    FLOAT,
    BOOLEAN
}

internal data class ServerSettingDescriptor(
    val key: String,
    val type: ServerSettingValueType
)

internal const val SERVER_URL_KEY = "server_url"
internal const val API_KEY_KEY = "api_key"
internal const val GRID_COLUMNS_KEY = "grid_columns"
internal const val TIMELINE_GROUP_SIZE_KEY = "timeline_group_size"
internal const val LEGACY_TIMELINE_TARGET_ROW_HEIGHT_KEY = "target_row_height"
internal const val TIMELINE_TARGET_ROW_HEIGHT_KEY = "timeline_target_row_height"
internal const val ALBUM_TARGET_ROW_HEIGHT_KEY = "album_detail_target_row_height"
internal const val PERSON_TARGET_ROW_HEIGHT_KEY = "person_detail_target_row_height"
internal const val SEARCH_TARGET_ROW_HEIGHT_KEY = "search_target_row_height"
internal const val FAVORITES_TARGET_ROW_HEIGHT_KEY = "favorites_target_row_height"
internal const val VIEW_CONFIG_MOSAIC_ENABLED_KEY = "view_config_mosaic_enabled"
internal const val VIEW_CONFIG_MOSAIC_FAMILIES_KEY = "view_config_mosaic_families"
internal const val VIEW_CONFIG_CACHE_MOSAIC_RESULTS_KEY = "view_config_cache_mosaic_results"
internal const val VIEW_CONFIG_DISABLE_MOSAIC_ZOOM_KEY = "view_config_disable_mosaic_zoom"
internal const val VIEW_CONFIG_MOSAIC_COLUMN_COUNT_KEY = "view_config_mosaic_column_count"

internal val SERVER_SETTING_DESCRIPTORS = listOf(
    ServerSettingDescriptor(SERVER_URL_KEY, ServerSettingValueType.STRING),
    ServerSettingDescriptor(API_KEY_KEY, ServerSettingValueType.STRING),
    ServerSettingDescriptor(GRID_COLUMNS_KEY, ServerSettingValueType.INT),
    ServerSettingDescriptor(TIMELINE_GROUP_SIZE_KEY, ServerSettingValueType.STRING),
    ServerSettingDescriptor(LEGACY_TIMELINE_TARGET_ROW_HEIGHT_KEY, ServerSettingValueType.FLOAT),
    ServerSettingDescriptor(TIMELINE_TARGET_ROW_HEIGHT_KEY, ServerSettingValueType.FLOAT),
    ServerSettingDescriptor(ALBUM_TARGET_ROW_HEIGHT_KEY, ServerSettingValueType.FLOAT),
    ServerSettingDescriptor(PERSON_TARGET_ROW_HEIGHT_KEY, ServerSettingValueType.FLOAT),
    ServerSettingDescriptor(SEARCH_TARGET_ROW_HEIGHT_KEY, ServerSettingValueType.FLOAT),
    ServerSettingDescriptor(FAVORITES_TARGET_ROW_HEIGHT_KEY, ServerSettingValueType.FLOAT),
    ServerSettingDescriptor(VIEW_CONFIG_MOSAIC_ENABLED_KEY, ServerSettingValueType.BOOLEAN),
    ServerSettingDescriptor(VIEW_CONFIG_MOSAIC_FAMILIES_KEY, ServerSettingValueType.STRING),
    ServerSettingDescriptor(VIEW_CONFIG_CACHE_MOSAIC_RESULTS_KEY, ServerSettingValueType.BOOLEAN),
    ServerSettingDescriptor(VIEW_CONFIG_DISABLE_MOSAIC_ZOOM_KEY, ServerSettingValueType.BOOLEAN),
    ServerSettingDescriptor(VIEW_CONFIG_MOSAIC_COLUMN_COUNT_KEY, ServerSettingValueType.INT)
)
