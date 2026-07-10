package com.udnahc.immichgallery.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.udnahc.immichgallery.domain.action.auth.MonitorServerStatusAction
import com.udnahc.immichgallery.domain.action.auth.ClearServerConfigAction
import com.udnahc.immichgallery.domain.action.settings.SetTimelineGroupSizeAction
import com.udnahc.immichgallery.domain.action.settings.SetViewConfigAction
import com.udnahc.immichgallery.domain.model.TimelineGroupSize
import com.udnahc.immichgallery.domain.model.ViewConfig
import com.udnahc.immichgallery.domain.usecase.auth.GetServerStatusUseCase
import com.udnahc.immichgallery.domain.usecase.settings.GetTimelineGroupSizeUseCase
import com.udnahc.immichgallery.domain.usecase.settings.GetViewConfigUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.udnahc.immichgallery.ui.model.MainUiMessage

@androidx.compose.runtime.Immutable
data class MainScreenState(
    val isLoggingOut: Boolean = false,
    val message: MainUiMessage? = null
)

class MainScreenViewModel(
    getServerStatusUseCase: GetServerStatusUseCase,
    monitorServerStatusAction: MonitorServerStatusAction,
    getTimelineGroupSizeUseCase: GetTimelineGroupSizeUseCase,
    private val setTimelineGroupSizeAction: SetTimelineGroupSizeAction,
    getViewConfigUseCase: GetViewConfigUseCase,
    private val setViewConfigAction: SetViewConfigAction,
    private val clearServerConfigAction: ClearServerConfigAction
) : ViewModel() {

    private val _screenState = MutableStateFlow(MainScreenState())
    val screenState: StateFlow<MainScreenState> = _screenState.asStateFlow()
    private val _logoutCompleted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val logoutCompleted: SharedFlow<Unit> = _logoutCompleted.asSharedFlow()

    val isServerOnline: StateFlow<Boolean> = getServerStatusUseCase()
    val timelineGroupSize: StateFlow<TimelineGroupSize> =
        getTimelineGroupSizeUseCase.observe()
            .map { saved -> saved.toTimelineGroupSize() }
            .flowOn(Dispatchers.Default)
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

    fun logout() {
        if (_screenState.value.isLoggingOut) return
        _screenState.update { it.copy(isLoggingOut = true, message = null) }
        viewModelScope.launch(Dispatchers.IO) {
            clearServerConfigAction().fold(
                onSuccess = {
                    _screenState.update { it.copy(isLoggingOut = false) }
                    _logoutCompleted.emit(Unit)
                },
                onFailure = {
                    _screenState.update {
                        it.copy(isLoggingOut = false, message = MainUiMessage.LogoutFailed)
                    }
                }
            )
        }
    }

    fun consumeMessage() {
        _screenState.update { it.copy(message = null) }
    }

    private fun String.toTimelineGroupSize(): TimelineGroupSize =
        TimelineGroupSize.entries.find { it.apiValue == this } ?: TimelineGroupSize.MONTH
}
