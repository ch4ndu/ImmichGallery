package com.udnahc.immichgallery.ui.model

import androidx.compose.runtime.Composable
import immichgallery.composeapp.generated.resources.Res
import immichgallery.composeapp.generated.resources.error_search_failed
import immichgallery.composeapp.generated.resources.login_connection_failed
import immichgallery.composeapp.generated.resources.login_required_fields
import immichgallery.composeapp.generated.resources.timeline_cannot_connect
import immichgallery.composeapp.generated.resources.timeline_connected
import immichgallery.composeapp.generated.resources.timeline_no_connection
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun UiMessage.asText(): String = stringResource(resource)

@Composable
fun UiMessage?.asTextOrNull(): String? = this?.asText()

val UiMessage.resource: StringResource
    get() = when (this) {
        LoginUiMessage.RequiredFields -> Res.string.login_required_fields
        LoginUiMessage.ConnectionFailed -> Res.string.login_connection_failed
        SearchUiMessage.SearchFailed -> Res.string.error_search_failed
        ConnectionUiMessage.NoConnectionToServer -> Res.string.timeline_no_connection
        ConnectionUiMessage.CannotConnectToServer -> Res.string.timeline_cannot_connect
        ConnectionUiMessage.ConnectedToServer -> Res.string.timeline_connected
    }
