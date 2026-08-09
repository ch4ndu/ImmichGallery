package com.udnahc.immichgallery.ui.model

sealed interface UiMessage

sealed interface LoginUiMessage : UiMessage {
    data object RequiredFields : LoginUiMessage
    data object ConnectionFailed : LoginUiMessage
    data object LocalNetworkPermissionDenied : LoginUiMessage
}

sealed interface SearchUiMessage : UiMessage {
    data object SearchFailed : SearchUiMessage
}

sealed interface FavoritesUiMessage : UiMessage {
    data object PermissionDenied : FavoritesUiMessage
    data object LoadFailed : FavoritesUiMessage
}

sealed interface MemoriesUiMessage : UiMessage {
    data object PermissionDenied : MemoriesUiMessage
    data object Unavailable : MemoriesUiMessage
    data object LoadFailed : MemoriesUiMessage
}

sealed interface StackUiMessage : UiMessage {
    data object NoLongerStacked : StackUiMessage
    data object PermissionDenied : StackUiMessage
    data object Unavailable : StackUiMessage
    data object RequestFailed : StackUiMessage
    data object NoSupportedMembers : StackUiMessage
}

sealed interface PersonDetailUiMessage : UiMessage {
    data object SyncInProgress : PersonDetailUiMessage
}

sealed interface MainUiMessage : UiMessage {
    data object LogoutFailed : MainUiMessage
}

sealed interface ConnectionUiMessage : UiMessage {
    data object NoConnectionToServer : ConnectionUiMessage
    data object CannotConnectToServer : ConnectionUiMessage
    data object ConnectedToServer : ConnectionUiMessage
    data object IncompatibleTimelineApi : ConnectionUiMessage
}
