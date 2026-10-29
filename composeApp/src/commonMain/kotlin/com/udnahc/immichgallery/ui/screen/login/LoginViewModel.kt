package com.udnahc.immichgallery.ui.screen.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.udnahc.immichgallery.LocalLoginDefaults
import com.udnahc.immichgallery.domain.action.auth.EnsureLocalNetworkAccessAction
import com.udnahc.immichgallery.domain.action.auth.LocalNetworkAccessResult
import com.udnahc.immichgallery.domain.action.auth.OpenLocalNetworkSettingsAction
import com.udnahc.immichgallery.domain.action.auth.ReplaceServerSessionAction
import com.udnahc.immichgallery.domain.usecase.auth.GetApiKeyUseCase
import com.udnahc.immichgallery.domain.usecase.auth.GetServerUrlUseCase
import com.udnahc.immichgallery.domain.usecase.auth.ValidateServerUseCase
import com.udnahc.immichgallery.ui.model.LoginUiMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@androidx.compose.runtime.Immutable
data class LoginState(
    val serverUrl: String = LocalLoginDefaults.SERVER_URL,
    val apiKey: String = LocalLoginDefaults.API_KEY,
    val isLoading: Boolean = false,
    val error: LoginUiMessage? = null,
    val isLoggedIn: Boolean = false,
    val canOpenLocalNetworkSettings: Boolean = false
)

class LoginViewModel(
    private val validateServerUseCase: ValidateServerUseCase,
    private val replaceServerSessionAction: ReplaceServerSessionAction,
    getServerUrlUseCase: GetServerUrlUseCase,
    getApiKeyUseCase: GetApiKeyUseCase,
    private val ensureLocalNetworkAccessAction: EnsureLocalNetworkAccessAction,
    private val openLocalNetworkSettingsAction: OpenLocalNetworkSettingsAction,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val _state = MutableStateFlow(
        LoginState(
            serverUrl = getServerUrlUseCase().ifBlank { LocalLoginDefaults.SERVER_URL },
            apiKey = getApiKeyUseCase().ifBlank { LocalLoginDefaults.API_KEY }
        )
    )
    val state: StateFlow<LoginState> = _state.asStateFlow()

    fun updateServerUrl(url: String) {
        _state.update {
            if (it.isLoading || it.isLoggedIn) it
            else it.copy(serverUrl = url, error = null, canOpenLocalNetworkSettings = false)
        }
    }

    fun updateApiKey(key: String) {
        _state.update {
            if (it.isLoading || it.isLoggedIn) it
            else it.copy(apiKey = key, error = null, canOpenLocalNetworkSettings = false)
        }
    }

    fun showLocalNetworkPermissionMessage() {
        _state.update {
            it.copy(
                error = LoginUiMessage.LocalNetworkPermissionDenied,
                canOpenLocalNetworkSettings = true
            )
        }
    }

    fun openLocalNetworkSettings() {
        openLocalNetworkSettingsAction()
    }

    fun login() {
        // Claim the submitted pair before dispatch, so fields and repeated
        // activation cannot change the operation while it waits to start.
        val submitted = claimSubmission() ?: return
        viewModelScope.launch(workerDispatcher) {
            try {
                submit(submitted)
            } finally {
                _state.update { it.copy(isLoading = false) }
            }
        }
    }

    private fun claimSubmission(): LoginState? {
        while (true) {
            val currentState = _state.value
            if (currentState.isLoading || currentState.isLoggedIn) return null
            if (currentState.serverUrl.isBlank() || currentState.apiKey.isBlank()) {
                if (_state.compareAndSet(currentState, currentState.copy(error = LoginUiMessage.RequiredFields))) {
                    return null
                }
            } else if (_state.compareAndSet(currentState, currentState.copy(isLoading = true, error = null))) {
                return currentState
            }
        }
    }

    private suspend fun submit(currentState: LoginState) {
        val normalizedServerUrl = currentState.serverUrl.trimEnd('/')
        when (ensureLocalNetworkAccessAction(normalizedServerUrl)) {
            LocalNetworkAccessResult.Denied -> {
                _state.update {
                    it.copy(
                        error = LoginUiMessage.LocalNetworkPermissionDenied,
                        canOpenLocalNetworkSettings = true
                    )
                }
                return
            }

            LocalNetworkAccessResult.Unavailable -> {
                _state.update {
                    it.copy(error = LoginUiMessage.ConnectionFailed)
                }
                return
            }

            LocalNetworkAccessResult.Granted,
            LocalNetworkAccessResult.NotRequired -> Unit
        }

        val result = validateServerUseCase(normalizedServerUrl, currentState.apiKey)
        result.fold(
            onSuccess = {
                replaceServerSessionAction(normalizedServerUrl, currentState.apiKey).fold(
                    onSuccess = {
                        _state.update { it.copy(isLoggedIn = true) }
                    },
                    onFailure = {
                        _state.update {
                            it.copy(
                                error = LoginUiMessage.ConnectionFailed
                            )
                        }
                    }
                )
            },
            onFailure = {
                _state.update {
                    it.copy(
                        error = LoginUiMessage.ConnectionFailed
                    )
                }
            }
        )
    }
}
