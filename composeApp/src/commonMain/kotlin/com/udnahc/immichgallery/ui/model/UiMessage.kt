package com.udnahc.immichgallery.ui.model

sealed interface UiMessage

sealed interface LoginUiMessage : UiMessage {
    data object RequiredFields : LoginUiMessage
    data object ConnectionFailed : LoginUiMessage
}

sealed interface SearchUiMessage : UiMessage {
    data object SearchFailed : SearchUiMessage
}

sealed interface ConnectionUiMessage : UiMessage {
    data object NoConnectionToServer : ConnectionUiMessage
    data object CannotConnectToServer : ConnectionUiMessage
    data object ConnectedToServer : ConnectionUiMessage
}
