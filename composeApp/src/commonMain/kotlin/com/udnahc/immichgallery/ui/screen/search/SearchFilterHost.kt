package com.udnahc.immichgallery.ui.screen.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import com.udnahc.immichgallery.domain.model.SearchCriteria
import com.udnahc.immichgallery.domain.model.SearchSuggestionRequest
import com.udnahc.immichgallery.domain.model.SearchSuggestionType
import com.udnahc.immichgallery.domain.model.TagConstraint
import com.udnahc.immichgallery.domain.model.valueOrNull
import com.udnahc.immichgallery.ui.theme.Dimens
import immichgallery.composeapp.generated.resources.Res
import immichgallery.composeapp.generated.resources.apply
import immichgallery.composeapp.generated.resources.cancel
import immichgallery.composeapp.generated.resources.search_albums
import immichgallery.composeapp.generated.resources.search_apply_people
import immichgallery.composeapp.generated.resources.search_camera
import immichgallery.composeapp.generated.resources.search_clear
import immichgallery.composeapp.generated.resources.search_date
import immichgallery.composeapp.generated.resources.search_display
import immichgallery.composeapp.generated.resources.search_filters
import immichgallery.composeapp.generated.resources.search_location
import immichgallery.composeapp.generated.resources.search_media
import immichgallery.composeapp.generated.resources.search_people
import immichgallery.composeapp.generated.resources.search_rating
import immichgallery.composeapp.generated.resources.search_tags
import org.jetbrains.compose.resources.stringResource

internal enum class SearchFilterTarget {
    ALL,
    PEOPLE,
    ALBUMS,
    TAGS,
    CAMERA,
    LOCATION,
    DATE,
    MEDIA,
    DISPLAY,
    RATING
}

internal data class SearchFilterDraftState(
    val appliedCriteria: SearchCriteria,
    val draftCriteria: SearchCriteria = appliedCriteria
) {
    fun edit(criteria: SearchCriteria): SearchFilterDraftState = copy(draftCriteria = criteria)

    fun cancel(): SearchCriteria = appliedCriteria

    val canApply: Boolean
        get() = validateSearchDateRange(draftCriteria.takenDateRange) == SearchDateRangeValidation.VALID

    fun apply(): SearchCriteria? = draftCriteria
        .takeIf { canApply }
        ?.metadataOnly()
        ?.normalized()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchFilterHost(
    target: SearchFilterTarget,
    state: SearchState,
    onDismiss: () -> Unit,
    onApply: (SearchCriteria) -> Unit,
    onOpenPeople: () -> Unit,
    onRetryPeople: () -> Unit,
    onOpenAlbums: () -> Unit,
    onRetryAlbums: () -> Unit,
    onOpenTags: () -> Unit,
    onRetryTags: () -> Unit,
    onLoadSuggestions: (SearchSuggestionRequest) -> Unit,
    onRetrySuggestions: (SearchSuggestionType) -> Unit,
    onRetryPreferences: () -> Unit
) {
    var draftState by remember(target, state.appliedCriteria) {
        mutableStateOf(SearchFilterDraftState(state.appliedCriteria))
    }
    var peopleQuery by remember(target) { mutableStateOf("") }
    var albumQuery by remember(target) { mutableStateOf("") }
    var tagQuery by remember(target) { mutableStateOf("") }

    LaunchedEffect(target) {
        if (target == SearchFilterTarget.ALL || target == SearchFilterTarget.PEOPLE) onOpenPeople()
        if (target == SearchFilterTarget.ALL || target == SearchFilterTarget.ALBUMS) onOpenAlbums()
        if (target == SearchFilterTarget.ALL || target == SearchFilterTarget.TAGS) onOpenTags()
    }

    val content: @Composable ColumnScope.() -> Unit = {
        SearchFilterEditorContent(
            target = target,
            state = state,
            draft = draftState.draftCriteria,
            peopleQuery = peopleQuery,
            albumQuery = albumQuery,
            tagQuery = tagQuery,
            onPeopleQueryChange = { peopleQuery = it },
            onAlbumQueryChange = { albumQuery = it },
            onTagQueryChange = { tagQuery = it },
            onDraftChange = { draftState = draftState.edit(it) },
            onRetryPeople = onRetryPeople,
            onRetryAlbums = onRetryAlbums,
            onRetryTags = onRetryTags,
            onLoadSuggestions = onLoadSuggestions,
            onRetrySuggestions = onRetrySuggestions,
            onRetryPreferences = onRetryPreferences,
            onClear = { draftState = draftState.edit(draftState.draftCriteria.clearedFor(target)) },
            onCancel = onDismiss,
            applyEnabled = draftState.canApply,
            onApply = {
                draftState.apply()?.let { criteria ->
                    onApply(criteria)
                    onDismiss()
                }
            }
        )
    }

    BoxWithConstraints {
        if (maxWidth < Dimens.searchFilterHostBreakpoint) {
            ModalBottomSheet(
                onDismissRequest = onDismiss,
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = Dimens.searchFilterDialogHeight)
                        .padding(horizontal = Dimens.screenPadding)
                ) {
                    content()
                }
            }
        } else {
            Dialog(onDismissRequest = onDismiss) {
                Surface(
                    modifier = Modifier.sizeIn(
                        maxWidth = Dimens.searchFilterDialogWidth,
                        maxHeight = Dimens.searchFilterDialogHeight
                    ),
                    shape = AlertDialogDefaults.shape,
                    color = AlertDialogDefaults.containerColor,
                    tonalElevation = AlertDialogDefaults.TonalElevation
                ) {
                    Column(modifier = Modifier.padding(Dimens.largeSpacing)) {
                        content()
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.SearchFilterEditorContent(
    target: SearchFilterTarget,
    state: SearchState,
    draft: SearchCriteria,
    peopleQuery: String,
    albumQuery: String,
    tagQuery: String,
    onPeopleQueryChange: (String) -> Unit,
    onAlbumQueryChange: (String) -> Unit,
    onTagQueryChange: (String) -> Unit,
    onDraftChange: (SearchCriteria) -> Unit,
    onRetryPeople: () -> Unit,
    onRetryAlbums: () -> Unit,
    onRetryTags: () -> Unit,
    onLoadSuggestions: (SearchSuggestionRequest) -> Unit,
    onRetrySuggestions: (SearchSuggestionType) -> Unit,
    onRetryPreferences: () -> Unit,
    onClear: () -> Unit,
    onCancel: () -> Unit,
    applyEnabled: Boolean,
    onApply: () -> Unit
) {
    Text(target.title(), style = MaterialTheme.typography.headlineSmall)
    Column(
        modifier = Modifier
            .weight(1f)
            .verticalScroll(rememberScrollState())
    ) {
        if (target == SearchFilterTarget.ALL || target == SearchFilterTarget.PEOPLE) {
            if (target == SearchFilterTarget.ALL) SearchSectionTitle(stringResource(Res.string.search_people))
            SearchPeoplePicker(
                state = state.peopleOptions,
                selectedIds = draft.personIds,
                query = peopleQuery,
                onQueryChange = onPeopleQueryChange,
                onSelectionChange = { onDraftChange(draft.copy(personIds = it)) },
                onRetry = onRetryPeople
            )
        }
        if (target == SearchFilterTarget.ALL || target == SearchFilterTarget.ALBUMS) {
            if (target == SearchFilterTarget.ALL) SearchSectionTitle(stringResource(Res.string.search_albums))
            SearchAlbumPicker(
                state = state.albumOptions,
                selectedIds = draft.albumIds,
                query = albumQuery,
                onQueryChange = onAlbumQueryChange,
                onSelectionChange = {
                    onDraftChange(draft.copy(albumIds = it, isNotInAlbum = false))
                },
                onRetry = onRetryAlbums
            )
        }
        if (
            (target == SearchFilterTarget.ALL || target == SearchFilterTarget.TAGS) &&
            state.preferenceCapabilities.valueOrNull()?.tagsEnabled == true
        ) {
            if (target == SearchFilterTarget.ALL) SearchSectionTitle(stringResource(Res.string.search_tags))
            SearchTagPicker(
                state = state.tagOptions,
                constraint = draft.tags,
                query = tagQuery,
                onQueryChange = onTagQueryChange,
                onConstraintChange = { onDraftChange(draft.copy(tags = it)) },
                onRetry = onRetryTags
            )
        }
        SearchMetadataFilterSections(
            target = target,
            criteria = draft,
            preferenceCapabilities = state.preferenceCapabilities,
            suggestionOptions = state.suggestionOptions,
            onCriteriaChange = onDraftChange,
            onLoadSuggestions = onLoadSuggestions,
            onRetrySuggestions = onRetrySuggestions,
            onRetryPreferences = onRetryPreferences
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = Dimens.mediumSpacing),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        TextButton(onClick = onClear) { Text(stringResource(Res.string.search_clear)) }
        Row {
            TextButton(onClick = onCancel) { Text(stringResource(Res.string.cancel)) }
            TextButton(onClick = onApply, enabled = applyEnabled) {
                Text(
                    if (target == SearchFilterTarget.PEOPLE) {
                        stringResource(Res.string.search_apply_people, draft.personIds.size)
                    } else {
                        stringResource(Res.string.apply)
                    }
                )
            }
        }
    }
}

@Composable
private fun SearchFilterTarget.title(): String = when (this) {
    SearchFilterTarget.ALL -> stringResource(Res.string.search_filters)
    SearchFilterTarget.PEOPLE -> stringResource(Res.string.search_people)
    SearchFilterTarget.ALBUMS -> stringResource(Res.string.search_albums)
    SearchFilterTarget.TAGS -> stringResource(Res.string.search_tags)
    SearchFilterTarget.CAMERA -> stringResource(Res.string.search_camera)
    SearchFilterTarget.LOCATION -> stringResource(Res.string.search_location)
    SearchFilterTarget.DATE -> stringResource(Res.string.search_date)
    SearchFilterTarget.MEDIA -> stringResource(Res.string.search_media)
    SearchFilterTarget.DISPLAY -> stringResource(Res.string.search_display)
    SearchFilterTarget.RATING -> stringResource(Res.string.search_rating)
}

internal fun SearchCriteria.clearedFor(target: SearchFilterTarget): SearchCriteria = when (target) {
    SearchFilterTarget.ALL -> SearchCriteria()
    SearchFilterTarget.PEOPLE -> copy(personIds = emptyList())
    SearchFilterTarget.ALBUMS -> copy(albumIds = emptyList())
    SearchFilterTarget.TAGS -> copy(tags = TagConstraint.Any)
    SearchFilterTarget.CAMERA -> copy(
        make = com.udnahc.immichgallery.domain.model.SearchOptionalValue.Any,
        model = com.udnahc.immichgallery.domain.model.SearchOptionalValue.Any,
        lensModel = com.udnahc.immichgallery.domain.model.SearchOptionalValue.Any
    )
    SearchFilterTarget.LOCATION -> copy(
        country = com.udnahc.immichgallery.domain.model.SearchOptionalValue.Any,
        state = com.udnahc.immichgallery.domain.model.SearchOptionalValue.Any,
        city = com.udnahc.immichgallery.domain.model.SearchOptionalValue.Any
    )
    SearchFilterTarget.DATE -> copy(takenDateRange = com.udnahc.immichgallery.domain.model.TakenDateRange())
    SearchFilterTarget.MEDIA -> copy(
        mediaType = com.udnahc.immichgallery.domain.model.SearchMediaType.ALL,
        motionOnly = false
    )
    SearchFilterTarget.DISPLAY -> copy(
        isFavorite = false,
        isNotInAlbum = false,
        visibility = com.udnahc.immichgallery.domain.model.SearchVisibility.ALL_ACCESSIBLE
    )
    SearchFilterTarget.RATING -> copy(rating = com.udnahc.immichgallery.domain.model.RatingConstraint.Any)
}
