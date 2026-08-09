package com.udnahc.immichgallery.ui.model

import kotlin.test.Test
import kotlin.test.assertEquals

class UiMessageTest {
    @Test
    fun resourcesCoverEveryUiMessage() {
        val messages: List<UiMessage> = listOf(
            LoginUiMessage.RequiredFields,
            LoginUiMessage.ConnectionFailed,
            SearchUiMessage.SearchFailed,
            FavoritesUiMessage.PermissionDenied,
            FavoritesUiMessage.LoadFailed,
            MemoriesUiMessage.PermissionDenied,
            MemoriesUiMessage.Unavailable,
            MemoriesUiMessage.LoadFailed,
            StackUiMessage.NoLongerStacked,
            StackUiMessage.PermissionDenied,
            StackUiMessage.Unavailable,
            StackUiMessage.RequestFailed,
            StackUiMessage.NoSupportedMembers,
            ConnectionUiMessage.NoConnectionToServer,
            ConnectionUiMessage.CannotConnectToServer,
            ConnectionUiMessage.ConnectedToServer
        )

        assertEquals(messages.size, messages.map { it.resource }.distinct().size)
    }
}
