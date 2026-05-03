package com.udnahc.immichgallery.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.udnahc.immichgallery.domain.action.auth.MonitorServerStatusAction
import com.udnahc.immichgallery.domain.action.settings.SetTimelineGroupSizeAction
import com.udnahc.immichgallery.domain.action.settings.SetViewConfigAction
import com.udnahc.immichgallery.domain.model.TimelineGroupSize
import com.udnahc.immichgallery.domain.model.ViewConfig
import com.udnahc.immichgallery.domain.usecase.auth.GetServerStatusUseCase
import com.udnahc.immichgallery.domain.usecase.settings.GetTimelineGroupSizeUseCase
import com.udnahc.immichgallery.domain.usecase.settings.GetViewConfigUseCase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class MainScreenViewModel(
    getServerStatusUseCase: GetServerStatusUseCase,
    monitorServerStatusAction: MonitorServerStatusAction,
    getTimelineGroupSizeUseCase: GetTimelineGroupSizeUseCase,
    private val setTimelineGroupSizeAction: SetTimelineGroupSizeAction,
    getViewConfigUseCase: GetViewConfigUseCase,
    private val setViewConfigAction: SetViewConfigAction
) : ViewModel() {

    val isServerOnline: StateFlow<Boolean> = getServerStatusUseCase()
    val timelineGroupSize: StateFlow<TimelineGroupSize> =
        getTimelineGroupSizeUseCase.observe()
            .map { saved -> saved.toTimelineGroupSize() }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                getTimelineGroupSizeUseCase().toTimelineGroupSize()
            )
    val viewConfig: StateFlow<ViewConfig> =
        getViewConfigUseCase.observe()
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                getViewConfigUseCase()
            )

    init {
        monitorServerStatusAction(viewModelScope)
    }

    fun setTimelineGroupSize(size: TimelineGroupSize) {
        if (size == timelineGroupSize.value) return
        setTimelineGroupSizeAction(size.apiValue)
    }

    fun setViewConfig(config: ViewConfig) {
        if (config.normalized == viewConfig.value) return
        setViewConfigAction(config)
    }

    private fun String.toTimelineGroupSize(): TimelineGroupSize =
        TimelineGroupSize.entries.find { it.apiValue == this } ?: TimelineGroupSize.MONTH
}
