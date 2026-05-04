package com.udnahc.immichgallery.ui.screen.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.udnahc.immichgallery.LocalLoginDefaults
import com.udnahc.immichgallery.domain.action.auth.SaveServerConfigAction
import com.udnahc.immichgallery.domain.usecase.auth.ValidateServerUseCase
import com.udnahc.immichgallery.ui.model.UiMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.lighthousegames.logging.logging

@androidx.compose.runtime.Immutable
data class LoginState(
    val serverUrl: String = LocalLoginDefaults.SERVER_URL,
    val apiKey: String = LocalLoginDefaults.API_KEY,
    val isLoading: Boolean = false,
    val error: UiMessage? = null,
    val isLoggedIn: Boolean = false
)

class LoginViewModel(
    private val validateServerUseCase: ValidateServerUseCase,
    private val saveServerConfigAction: SaveServerConfigAction
) : ViewModel() {

    private val log = logging("LoginViewModel")
    private val _state = MutableStateFlow(LoginState())
    val state: StateFlow<LoginState> = _state.asStateFlow()

    fun updateServerUrl(url: String) {
        _state.update { it.copy(serverUrl = url, error = null) }
    }

    fun updateApiKey(key: String) {
        _state.update { it.copy(apiKey = key, error = null) }
    }

    fun login() {
        viewModelScope.launch(Dispatchers.IO) {
            val currentState = _state.value
            if (currentState.serverUrl.isBlank() || currentState.apiKey.isBlank()) {
                _state.update { it.copy(error = UiMessage.LoginRequiredFields) }
                return@launch
            }

            log.d { "Login attempt to ${currentState.serverUrl}" }
            _state.update { it.copy(isLoading = true, error = null) }
            val result =
                validateServerUseCase(currentState.serverUrl.trimEnd('/'), currentState.apiKey)
            result.fold(
                onSuccess = {
                    log.d { "Login successful" }
                    saveServerConfigAction(currentState.serverUrl.trimEnd('/'), currentState.apiKey)
                    _state.update { it.copy(isLoading = false, isLoggedIn = true) }
                },
                onFailure = { e ->
                    log.e(e) { "Login failed" }
                    _state.update {
                        it.copy(
                            isLoading = false,
                            error = UiMessage.LoginConnectionFailed
                        )
                    }
                }
            )
        }
    }
}
