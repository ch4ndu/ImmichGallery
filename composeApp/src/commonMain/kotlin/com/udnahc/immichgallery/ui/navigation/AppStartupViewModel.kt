package com.udnahc.immichgallery.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.udnahc.immichgallery.domain.action.auth.EnsureLocalNetworkAccessAction
import com.udnahc.immichgallery.domain.action.auth.LocalNetworkAccessResult
import com.udnahc.immichgallery.domain.usecase.auth.GetLoginStatusUseCase
import com.udnahc.immichgallery.domain.usecase.auth.GetServerUrlUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@androidx.compose.runtime.Immutable
data class AppStartupState(
    val destination: AppStartupDestination = AppStartupDestination.Loading,
    val showLocalNetworkPermissionMessage: Boolean = false
)

enum class AppStartupDestination {
    Loading,
    Login,
    Main
}

class AppStartupViewModel(
    private val getLoginStatusUseCase: GetLoginStatusUseCase,
    private val getServerUrlUseCase: GetServerUrlUseCase,
    private val ensureLocalNetworkAccessAction: EnsureLocalNetworkAccessAction,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {
    private val _state = MutableStateFlow(AppStartupState())
    val state: StateFlow<AppStartupState> = _state.asStateFlow()

    init {
        resolveStartDestination()
    }

    private fun resolveStartDestination() {
        viewModelScope.launch(workerDispatcher) {
            if (!getLoginStatusUseCase()) {
                _state.value = AppStartupState(destination = AppStartupDestination.Login)
                return@launch
            }

            when (ensureLocalNetworkAccessAction(getServerUrlUseCase())) {
                LocalNetworkAccessResult.Granted,
                LocalNetworkAccessResult.NotRequired -> {
                    _state.value = AppStartupState(destination = AppStartupDestination.Main)
                }

                LocalNetworkAccessResult.Denied,
                LocalNetworkAccessResult.Unavailable -> {
                    _state.value = AppStartupState(
                        destination = AppStartupDestination.Login,
                        showLocalNetworkPermissionMessage = true
                    )
                }
            }
        }
    }
}
