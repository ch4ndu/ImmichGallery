package com.udnahc.immichgallery.ui.screen.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import com.udnahc.immichgallery.domain.model.RatingConstraint
import com.udnahc.immichgallery.domain.model.SearchCapabilities
import com.udnahc.immichgallery.domain.model.SearchCriteria
import com.udnahc.immichgallery.domain.model.SearchLoadState
import com.udnahc.immichgallery.domain.model.SearchMediaType
import com.udnahc.immichgallery.domain.model.SearchOptionalValue
import com.udnahc.immichgallery.domain.model.SearchPreferenceCapabilities
import com.udnahc.immichgallery.domain.model.SearchTextMode
import com.udnahc.immichgallery.domain.model.SearchVisibility
import com.udnahc.immichgallery.domain.model.TagConstraint
import com.udnahc.immichgallery.domain.model.valueOrNull
import com.udnahc.immichgallery.ui.theme.Dimens
import com.udnahc.immichgallery.ui.util.systemBarFadeIn
import com.udnahc.immichgallery.ui.util.systemBarFadeOut
import immichgallery.composeapp.generated.resources.Res
import immichgallery.composeapp.generated.resources.clear_search
import immichgallery.composeapp.generated.resources.ic_arrow_forward
import immichgallery.composeapp.generated.resources.ic_close
import immichgallery.composeapp.generated.resources.ic_next
import immichgallery.composeapp.generated.resources.ic_search
import immichgallery.composeapp.generated.resources.ic_tune
import immichgallery.composeapp.generated.resources.retry
import immichgallery.composeapp.generated.resources.search_albums
import immichgallery.composeapp.generated.resources.search_camera
import immichgallery.composeapp.generated.resources.search_context_unavailable
import immichgallery.composeapp.generated.resources.search_date
import immichgallery.composeapp.generated.resources.search_date_from
import immichgallery.composeapp.generated.resources.search_date_range
import immichgallery.composeapp.generated.resources.search_date_until
import immichgallery.composeapp.generated.resources.search_display
import immichgallery.composeapp.generated.resources.search_filters
import immichgallery.composeapp.generated.resources.search_favorite_only
import immichgallery.composeapp.generated.resources.search_location
import immichgallery.composeapp.generated.resources.search_media
import immichgallery.composeapp.generated.resources.search_media_image
import immichgallery.composeapp.generated.resources.search_media_video
import immichgallery.composeapp.generated.resources.search_motion_only
import immichgallery.composeapp.generated.resources.search_not_in_album
import immichgallery.composeapp.generated.resources.search_ocr_unavailable
import immichgallery.composeapp.generated.resources.search_people
import immichgallery.composeapp.generated.resources.search_rating
import immichgallery.composeapp.generated.resources.search_rating_stars
import immichgallery.composeapp.generated.resources.search_remove_filter
import immichgallery.composeapp.generated.resources.search_selection_more
import immichgallery.composeapp.generated.resources.search_tags
import immichgallery.composeapp.generated.resources.search_tune_description
import immichgallery.composeapp.generated.resources.search_by_mode
import immichgallery.composeapp.generated.resources.search_submit
import immichgallery.composeapp.generated.resources.search_type_context
import immichgallery.composeapp.generated.resources.search_type_context_explanation
import immichgallery.composeapp.generated.resources.search_type_description
import immichgallery.composeapp.generated.resources.search_type_description_explanation
import immichgallery.composeapp.generated.resources.search_type_filename
import immichgallery.composeapp.generated.resources.search_type_filename_explanation
import immichgallery.composeapp.generated.resources.search_type_ocr
import immichgallery.composeapp.generated.resources.search_type_ocr_explanation
import immichgallery.composeapp.generated.resources.search_untagged
import immichgallery.composeapp.generated.resources.search_unknown
import immichgallery.composeapp.generated.resources.search_unrated
import immichgallery.composeapp.generated.resources.search_unavailable_selection
import immichgallery.composeapp.generated.resources.search_visibility_archive
import immichgallery.composeapp.generated.resources.search_visibility_timeline
import immichgallery.composeapp.generated.resources.search_placeholder
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

internal data class SearchSelectionSummary(
    val primaryLabel: String,
    val additionalCount: Int
)

internal fun selectionSummary(
    selectedIds: List<String>,
    options: List<Pair<String, String>>,
    unavailableLabel: String
): SearchSelectionSummary? {
    val ids = selectedIds.distinct()
    val first = ids.firstOrNull() ?: return null
    return SearchSelectionSummary(
        primaryLabel = resolvedEntityLabel(first, options, unavailableLabel),
        additionalCount = ids.size - 1
    )
}

internal fun selectionSummary(
    selectedIds: List<String>,
    unavailableLabel: String,
    labelForId: (String) -> String?
): SearchSelectionSummary? {
    val ids = selectedIds.distinct()
    val first = ids.firstOrNull() ?: return null
    return SearchSelectionSummary(
        primaryLabel = labelForId(first)?.takeIf(String::isNotBlank) ?: unavailableLabel,
        additionalCount = ids.size - 1
    )
}

internal fun activeSearchFilterCount(criteria: SearchCriteria): Int = listOf(
    criteria.personIds.isNotEmpty(),
    criteria.albumIds.isNotEmpty(),
    criteria.tags != TagConstraint.Any,
    criteria.country != SearchOptionalValue.Any ||
            criteria.state != SearchOptionalValue.Any ||
            criteria.city != SearchOptionalValue.Any,
    criteria.make != SearchOptionalValue.Any ||
            criteria.model != SearchOptionalValue.Any ||
            criteria.lensModel != SearchOptionalValue.Any,
    !criteria.takenDateRange.isEmpty,
    criteria.mediaType != SearchMediaType.ALL || criteria.motionOnly,
    criteria.isFavorite || criteria.isNotInAlbum ||
            criteria.visibility != SearchVisibility.ALL_ACCESSIBLE,
    criteria.rating != RatingConstraint.Any
).count { it }

internal fun visibleSearchFilterTargets(
    preferences: SearchPreferenceCapabilities?
): List<SearchFilterTarget> = buildList {
    add(SearchFilterTarget.PEOPLE)
    add(SearchFilterTarget.ALBUMS)
    if (preferences?.tagsEnabled == true) add(SearchFilterTarget.TAGS)
    add(SearchFilterTarget.DATE)
    add(SearchFilterTarget.MEDIA)
    add(SearchFilterTarget.CAMERA)
    add(SearchFilterTarget.LOCATION)
    if (preferences?.ratingsEnabled == true) add(SearchFilterTarget.RATING)
    add(SearchFilterTarget.DISPLAY)
}

@Composable
internal fun SearchControls(
    modifier: Modifier = Modifier,
    visible: Boolean,
    state: SearchState,
    onQueryChange: (String) -> Unit,
    onTextModeChange: (SearchTextMode) -> Unit,
    onSearch: () -> Unit,
    onOpenFilters: (SearchFilterTarget) -> Unit,
    onApplyCriteria: (SearchCriteria) -> Unit,
    onRetryCapabilities: () -> Unit
) {
    var modeChoicesVisible by remember { mutableStateOf(false) }
    AnimatedVisibility(
        modifier = modifier,
        visible = visible,
        enter = systemBarFadeIn,
        exit = systemBarFadeOut
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.mediumSpacing)) {
            Spacer(modifier = Modifier.height(Dimens.mediumSpacing))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dimens.mediumSpacing),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = state.query,
                    onValueChange = onQueryChange,
                    placeholder = { Text(stringResource(Res.string.search_placeholder)) },
                    leadingIcon = {
                        Icon(painterResource(Res.drawable.ic_search), contentDescription = null)
                    },
                    shape = MaterialTheme.shapes.large,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                    trailingIcon = {
                        if (state.query.isNotEmpty()) {
                            IconButton(onClick = { onQueryChange("") }) {
                                Icon(
                                    painterResource(Res.drawable.ic_close),
                                    contentDescription = stringResource(Res.string.clear_search)
                                )
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
                FilledIconButton(
                    onClick = onSearch,
                    modifier = Modifier.size(Dimens.searchBarHeight)
                ) {
                    Icon(
                        painterResource(Res.drawable.ic_arrow_forward),
                        contentDescription = stringResource(Res.string.search_submit)
                    )
                }
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(Dimens.smallSpacing)
            ) {
                TextButton(onClick = { modeChoicesVisible = !modeChoicesVisible }) {
                    Text(
                        stringResource(
                            Res.string.search_by_mode,
                            state.textMode.label()
                        )
                    )
                    Icon(
                        painterResource(Res.drawable.ic_next),
                        contentDescription = null,
                        modifier = Modifier.rotate(if (modeChoicesVisible) -90f else 90f)
                    )
                }
                val filterCount = activeSearchFilterCount(state.appliedCriteria)
                val tuneDescription = stringResource(
                    Res.string.search_tune_description,
                    filterCount
                )
                OutlinedButton(
                    onClick = { onOpenFilters(SearchFilterTarget.ALL) },
                    modifier = Modifier.semantics {
                        contentDescription = tuneDescription
                    }
                ) {
                    BadgedBox(
                        badge = {
                            if (filterCount > 0) {
                                Badge { Text(filterCount.toString()) }
                            }
                        }
                    ) {
                        Icon(painterResource(Res.drawable.ic_tune), contentDescription = null)
                    }
                    Text(stringResource(Res.string.search_filters))
                }
            }
            AnimatedVisibility(
                visible = modeChoicesVisible,
                modifier = Modifier.weight(1f, fill = false)
            ) {
                SearchModeChoiceSurface(
                    mode = state.textMode,
                    capabilities = state.serverCapabilities,
                    onModeChange = {
                        modeChoicesVisible = false
                        onTextModeChange(it)
                    },
                    onRetryCapabilities = onRetryCapabilities
                )
            }
            SearchModeAvailability(
                mode = state.textMode,
                capabilities = state.serverCapabilities,
                onRetry = onRetryCapabilities
            )
            SearchAppliedFilterRow(
                state = state,
                onApplyCriteria = onApplyCriteria
            )
        }
    }
}

@Composable
private fun SearchModeChoiceSurface(
    mode: SearchTextMode,
    capabilities: SearchLoadState<SearchCapabilities>,
    onModeChange: (SearchTextMode) -> Unit,
    onRetryCapabilities: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        BoxWithConstraints {
            val columnCount = if (maxWidth >= Dimens.searchFilterHostBreakpoint) 2 else 1
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(Dimens.mediumSpacing),
                verticalArrangement = Arrangement.spacedBy(Dimens.smallSpacing)
            ) {
                SearchTextMode.entries.chunked(columnCount).forEach { modes ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Dimens.smallSpacing)
                    ) {
                        modes.forEach { candidate ->
                            SearchModeChoice(
                                candidate = candidate,
                                selected = candidate == mode,
                                enabled = candidate.isEnabled(capabilities.valueOrNull()),
                                onClick = { onModeChange(candidate) }
                            )
                        }
                    }
                }
                if (capabilities is SearchLoadState.Error) {
                    TextButton(onClick = onRetryCapabilities) {
                        Text(stringResource(Res.string.retry))
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.SearchModeChoice(
    candidate: SearchTextMode,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    Row(
        modifier = Modifier
            .weight(1f)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick
            )
            .semantics(mergeDescendants = true) {}
            .padding(Dimens.mediumSpacing),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(Dimens.mediumSpacing)
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            enabled = enabled,
            modifier = Modifier.clearAndSetSemantics {}
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(candidate.label(), color = contentColor)
            Text(
                candidate.explanation(),
                style = MaterialTheme.typography.bodySmall,
                color = contentColor
            )
            if (!enabled) {
                Text(
                    candidate.unavailableExplanation(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun SearchModeAvailability(
    mode: SearchTextMode,
    capabilities: SearchLoadState<SearchCapabilities>,
    onRetry: () -> Unit
) {
    if (mode.isEnabled(capabilities.valueOrNull())) return
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            mode.unavailableExplanation(),
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall
        )
        TextButton(onClick = onRetry) { Text(stringResource(Res.string.retry)) }
    }
}

@Composable
private fun SearchAppliedFilterRow(
    state: SearchState,
    onApplyCriteria: (SearchCriteria) -> Unit
) {
    val criteria = state.appliedCriteria
    if (!criteria.hasMetadataFilters) return
    val unavailable = stringResource(Res.string.search_unavailable_selection)
    val unknown = stringResource(Res.string.search_unknown)
    val people = state.peopleOptions.valueOrNull().orEmpty()
    val categoryChips = SearchFilterTarget.entries
        .filter { it != SearchFilterTarget.ALL && it != SearchFilterTarget.PEOPLE }
        .mapNotNull { target -> categorySummary(target, state)?.let { target to it } }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(Dimens.mediumSpacing)) {
        items(criteria.personIds, key = { "person:$it" }) { id ->
            val label = people.firstOrNull { it.id == id }
                ?.name
                ?.ifBlank { unknown }
                ?: unavailable
            SearchRemovalChip(label) {
                onApplyCriteria(criteria.copy(personIds = criteria.personIds.filterNot { it == id }))
            }
        }
        items(categoryChips, key = { it.first.name }) { (target, label) ->
            SearchRemovalChip(label) {
                onApplyCriteria(criteria.clearedFor(target))
            }
        }
    }
}

@Composable
private fun SearchRemovalChip(
    label: String,
    onRemove: () -> Unit
) {
    val description = stringResource(Res.string.search_remove_filter, label)
    InputChip(
        selected = true,
        onClick = onRemove,
        label = { Text(label) },
        trailingIcon = {
            Icon(
                painterResource(Res.drawable.ic_close),
                contentDescription = description
            )
        }
    )
}

@Composable
private fun categorySummary(target: SearchFilterTarget, state: SearchState): String? {
    val criteria = state.appliedCriteria
    val unavailable = stringResource(Res.string.search_unavailable_selection)
    val unknown = stringResource(Res.string.search_unknown)
    val people = state.peopleOptions.valueOrNull().orEmpty()
    val albums = state.albumOptions.valueOrNull().orEmpty()
    val tagOptions = state.tagOptions.valueOrNull().orEmpty()
    val summary = when (target) {
        SearchFilterTarget.PEOPLE -> selectionSummary(
            criteria.personIds,
            unavailable
        ) { id -> people.firstOrNull { it.id == id }?.name?.ifBlank { unknown } }
        SearchFilterTarget.ALBUMS -> selectionSummary(
            criteria.albumIds,
            unavailable
        ) { id -> albums.firstOrNull { it.id == id }?.name }
        SearchFilterTarget.TAGS -> when (val constraint = criteria.tags) {
            TagConstraint.Any -> null
            TagConstraint.Untagged -> SearchSelectionSummary(stringResource(Res.string.search_untagged), 0)
            is TagConstraint.Selected -> selectionSummary(
                constraint.ids,
                unavailable
            ) { id -> tagOptions.firstOrNull { it.id == id }?.value }
        }
        SearchFilterTarget.DATE -> criteria.takenDateRange.takeUnless { it.isEmpty }?.let {
            SearchSelectionSummary(dateSummary(it.start, it.endInclusive), 0)
        }
        SearchFilterTarget.MEDIA -> if (
            criteria.mediaType != SearchMediaType.ALL || criteria.motionOnly
        ) {
            SearchSelectionSummary(
                when {
                    criteria.motionOnly -> stringResource(Res.string.search_motion_only)
                    criteria.mediaType == SearchMediaType.IMAGE -> stringResource(Res.string.search_media_image)
                    else -> stringResource(Res.string.search_media_video)
                },
                0
            )
        } else {
            null
        }
        SearchFilterTarget.CAMERA -> compactSummary(
            listOf(criteria.make, criteria.model, criteria.lensModel),
            unknown
        )
        SearchFilterTarget.LOCATION -> compactSummary(
            listOf(criteria.country, criteria.state, criteria.city),
            unknown
        )
        SearchFilterTarget.DISPLAY -> compactSummary(
            buildList {
                if (criteria.isFavorite) add(stringResource(Res.string.search_favorite_only))
                if (criteria.isNotInAlbum) add(stringResource(Res.string.search_not_in_album))
                when (criteria.visibility) {
                    SearchVisibility.ALL_ACCESSIBLE -> Unit
                    SearchVisibility.TIMELINE -> add(stringResource(Res.string.search_visibility_timeline))
                    SearchVisibility.ARCHIVE -> add(stringResource(Res.string.search_visibility_archive))
                }
            }
        )
        SearchFilterTarget.RATING -> when (val rating = criteria.rating) {
            RatingConstraint.Any -> null
            RatingConstraint.Unrated -> SearchSelectionSummary(stringResource(Res.string.search_unrated), 0)
            is RatingConstraint.Stars -> SearchSelectionSummary(
                stringResource(Res.string.search_rating_stars, rating.value),
                0
            )
        }
        SearchFilterTarget.ALL -> null
    } ?: return null
    return if (summary.additionalCount > 0) {
        stringResource(
            Res.string.search_selection_more,
            summary.primaryLabel,
            summary.additionalCount
        )
    } else {
        summary.primaryLabel
    }
}

@Composable
private fun compactSummary(
    values: List<SearchOptionalValue<String>>,
    unknownLabel: String
): SearchSelectionSummary? = compactSummary(
    values.mapNotNull { value ->
        when (value) {
            SearchOptionalValue.Any -> null
            SearchOptionalValue.Unknown -> unknownLabel
            is SearchOptionalValue.Exact -> value.value
        }
    }
)

private fun compactSummary(labels: List<String>): SearchSelectionSummary? =
    labels.firstOrNull()?.let { SearchSelectionSummary(it, labels.size - 1) }

@Composable
private fun SearchFilterTarget.label(): String = when (this) {
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

@Composable
private fun SearchTextMode.label(): String = when (this) {
    SearchTextMode.CONTEXT -> stringResource(Res.string.search_type_context)
    SearchTextMode.FILENAME -> stringResource(Res.string.search_type_filename)
    SearchTextMode.DESCRIPTION -> stringResource(Res.string.search_type_description)
    SearchTextMode.OCR -> stringResource(Res.string.search_type_ocr)
}

@Composable
private fun SearchTextMode.unavailableExplanation(): String = when (this) {
    SearchTextMode.CONTEXT -> stringResource(Res.string.search_context_unavailable)
    SearchTextMode.OCR -> stringResource(Res.string.search_ocr_unavailable)
    SearchTextMode.FILENAME,
    SearchTextMode.DESCRIPTION -> ""
}

@Composable
private fun SearchTextMode.explanation(): String = when (this) {
    SearchTextMode.CONTEXT -> stringResource(Res.string.search_type_context_explanation)
    SearchTextMode.FILENAME -> stringResource(Res.string.search_type_filename_explanation)
    SearchTextMode.DESCRIPTION -> stringResource(Res.string.search_type_description_explanation)
    SearchTextMode.OCR -> stringResource(Res.string.search_type_ocr_explanation)
}

private fun SearchTextMode.isEnabled(capabilities: SearchCapabilities?): Boolean = when (this) {
    SearchTextMode.CONTEXT -> capabilities?.contextEnabled == true
    SearchTextMode.OCR -> capabilities?.ocrEnabled == true
    SearchTextMode.FILENAME,
    SearchTextMode.DESCRIPTION -> true
}

@Composable
private fun dateSummary(start: LocalDate?, end: LocalDate?): String = when {
    start != null && end != null -> stringResource(Res.string.search_date_range, start, end)
    start != null -> stringResource(Res.string.search_date_from, start)
    end != null -> stringResource(Res.string.search_date_until, end)
    else -> ""
}
