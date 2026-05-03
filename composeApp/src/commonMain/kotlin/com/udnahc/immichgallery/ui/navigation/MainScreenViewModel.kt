package com.udnahc.immichgallery.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.udnahc.immichgallery.domain.action.auth.MonitorServerStatusAction
import com.udnahc.immichgallery.domain.action.settings.SetTimelineGroupSizeAction
import com.udnahc.immichgallery.domain.model.TimelineGroupSize
import com.udnahc.immichgallery.domain.usecase.auth.GetServerStatusUseCase
import com.udnahc.immichgallery.domain.usecase.settings.GetTimelineGroupSizeUseCase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class MainScreenViewModel(
    getServerStatusUseCase: GetServerStatusUseCase,
    monitorServerStatusAction: MonitorServerStatusAction,
    getTimelineGroupSizeUseCase: GetTimelineGroupSizeUseCase,
    private val setTimelineGroupSizeAction: SetTimelineGroupSizeAction
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

    init {
        monitorServerStatusAction(viewModelScope)
    }

    fun setTimelineGroupSize(size: TimelineGroupSize) {
        if (size == timelineGroupSize.value) return
        setTimelineGroupSizeAction(size.apiValue)
    }

    private fun String.toTimelineGroupSize(): TimelineGroupSize =
        TimelineGroupSize.entries.find { it.apiValue == this } ?: TimelineGroupSize.MONTH
}
