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
        _state.update { it.copy(serverUrl = url, error = null, canOpenLocalNetworkSettings = false) }
    }

    fun updateApiKey(key: String) {
        _state.update { it.copy(apiKey = key, error = null, canOpenLocalNetworkSettings = false) }
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
        viewModelScope.launch(workerDispatcher) {
            val currentState = _state.value
            if (currentState.serverUrl.isBlank() || currentState.apiKey.isBlank()) {
                _state.update { it.copy(error = LoginUiMessage.RequiredFields) }
                return@launch
            }

            val normalizedServerUrl = currentState.serverUrl.trimEnd('/')
            _state.update { it.copy(isLoading = true, error = null) }
            when (ensureLocalNetworkAccessAction(normalizedServerUrl)) {
                LocalNetworkAccessResult.Denied -> {
                    _state.update {
                        it.copy(
                            isLoading = false,
                            error = LoginUiMessage.LocalNetworkPermissionDenied,
                            canOpenLocalNetworkSettings = true
                        )
                    }
                    return@launch
                }

                LocalNetworkAccessResult.Unavailable -> {
                    _state.update {
                        it.copy(isLoading = false, error = LoginUiMessage.ConnectionFailed)
                    }
                    return@launch
                }

                LocalNetworkAccessResult.Granted,
                LocalNetworkAccessResult.NotRequired -> Unit
            }

            val result = validateServerUseCase(normalizedServerUrl, currentState.apiKey)
            result.fold(
                onSuccess = {
                    replaceServerSessionAction(normalizedServerUrl, currentState.apiKey).fold(
                        onSuccess = {
                            _state.update { it.copy(isLoading = false, isLoggedIn = true) }
                        },
                        onFailure = {
                            _state.update {
                                it.copy(
                                    isLoading = false,
                                    error = LoginUiMessage.ConnectionFailed
                                )
                            }
                        }
                    )
                },
                onFailure = {
                    _state.update {
                        it.copy(
                            isLoading = false,
                            error = LoginUiMessage.ConnectionFailed
                        )
                    }
                }
            )
        }
    }
}
