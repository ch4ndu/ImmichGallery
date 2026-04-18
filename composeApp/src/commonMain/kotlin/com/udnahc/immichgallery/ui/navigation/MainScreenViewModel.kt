package com.udnahc.immichgallery.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.udnahc.immichgallery.domain.action.auth.MonitorServerStatusAction
import com.udnahc.immichgallery.domain.usecase.auth.GetServerStatusUseCase
import kotlinx.coroutines.flow.StateFlow

class MainScreenViewModel(
    getServerStatusUseCase: GetServerStatusUseCase,
    monitorServerStatusAction: MonitorServerStatusAction
) : ViewModel() {

    val isServerOnline: StateFlow<Boolean> = getServerStatusUseCase()

    init {
        monitorServerStatusAction(viewModelScope)
    }
}
