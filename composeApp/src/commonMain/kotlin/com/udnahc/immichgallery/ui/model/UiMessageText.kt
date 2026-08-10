package com.udnahc.immichgallery.ui.model

import androidx.compose.runtime.Composable
import immichgallery.composeapp.generated.resources.Res
import immichgallery.composeapp.generated.resources.error_search_failed
import immichgallery.composeapp.generated.resources.search_album_options_failed
import immichgallery.composeapp.generated.resources.search_context_unavailable
import immichgallery.composeapp.generated.resources.search_ocr_unavailable
import immichgallery.composeapp.generated.resources.search_people_options_failed
import immichgallery.composeapp.generated.resources.search_preferences_unavailable
import immichgallery.composeapp.generated.resources.search_suggestions_failed
import immichgallery.composeapp.generated.resources.search_tag_options_failed
import immichgallery.composeapp.generated.resources.search_unavailable
import immichgallery.composeapp.generated.resources.favorites_load_failed
import immichgallery.composeapp.generated.resources.favorites_permission_denied
import immichgallery.composeapp.generated.resources.memories_load_failed
import immichgallery.composeapp.generated.resources.memories_permission_denied
import immichgallery.composeapp.generated.resources.memories_unavailable
import immichgallery.composeapp.generated.resources.stack_no_longer_stacked
import immichgallery.composeapp.generated.resources.stack_no_supported_members
import immichgallery.composeapp.generated.resources.stack_permission_denied
import immichgallery.composeapp.generated.resources.stack_request_failed
import immichgallery.composeapp.generated.resources.stack_unavailable
import immichgallery.composeapp.generated.resources.login_connection_failed
import immichgallery.composeapp.generated.resources.login_local_network_permission_denied
import immichgallery.composeapp.generated.resources.login_required_fields
import immichgallery.composeapp.generated.resources.person_sync_in_progress
import immichgallery.composeapp.generated.resources.logout_failed
import immichgallery.composeapp.generated.resources.timeline_cannot_connect
import immichgallery.composeapp.generated.resources.timeline_connected
import immichgallery.composeapp.generated.resources.timeline_incompatible_api
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
        LoginUiMessage.LocalNetworkPermissionDenied -> Res.string.login_local_network_permission_denied
        SearchUiMessage.SearchFailed -> Res.string.error_search_failed
        SearchUiMessage.SearchUnavailable -> Res.string.search_unavailable
        SearchUiMessage.ContextUnavailable -> Res.string.search_context_unavailable
        SearchUiMessage.OcrUnavailable -> Res.string.search_ocr_unavailable
        SearchUiMessage.PreferencesUnavailable -> Res.string.search_preferences_unavailable
        SearchUiMessage.PeopleOptionsFailed -> Res.string.search_people_options_failed
        SearchUiMessage.AlbumOptionsFailed -> Res.string.search_album_options_failed
        SearchUiMessage.TagOptionsFailed -> Res.string.search_tag_options_failed
        SearchUiMessage.SuggestionsFailed -> Res.string.search_suggestions_failed
        FavoritesUiMessage.PermissionDenied -> Res.string.favorites_permission_denied
        FavoritesUiMessage.LoadFailed -> Res.string.favorites_load_failed
        MemoriesUiMessage.PermissionDenied -> Res.string.memories_permission_denied
        MemoriesUiMessage.Unavailable -> Res.string.memories_unavailable
        MemoriesUiMessage.LoadFailed -> Res.string.memories_load_failed
        StackUiMessage.NoLongerStacked -> Res.string.stack_no_longer_stacked
        StackUiMessage.PermissionDenied -> Res.string.stack_permission_denied
        StackUiMessage.Unavailable -> Res.string.stack_unavailable
        StackUiMessage.RequestFailed -> Res.string.stack_request_failed
        StackUiMessage.NoSupportedMembers -> Res.string.stack_no_supported_members
        PersonDetailUiMessage.SyncInProgress -> Res.string.person_sync_in_progress
        MainUiMessage.LogoutFailed -> Res.string.logout_failed
        ConnectionUiMessage.NoConnectionToServer -> Res.string.timeline_no_connection
        ConnectionUiMessage.CannotConnectToServer -> Res.string.timeline_cannot_connect
        ConnectionUiMessage.ConnectedToServer -> Res.string.timeline_connected
        ConnectionUiMessage.IncompatibleTimelineApi -> Res.string.timeline_incompatible_api
    }
