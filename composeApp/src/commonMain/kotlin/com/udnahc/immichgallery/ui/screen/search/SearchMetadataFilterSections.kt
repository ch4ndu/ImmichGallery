package com.udnahc.immichgallery.ui.screen.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.udnahc.immichgallery.domain.model.RatingConstraint
import com.udnahc.immichgallery.domain.model.SearchCriteria
import com.udnahc.immichgallery.domain.model.SearchLoadState
import com.udnahc.immichgallery.domain.model.SearchMediaType
import com.udnahc.immichgallery.domain.model.SearchOptionalValue
import com.udnahc.immichgallery.domain.model.SearchPreferenceCapabilities
import com.udnahc.immichgallery.domain.model.SearchSuggestion
import com.udnahc.immichgallery.domain.model.SearchSuggestionRequest
import com.udnahc.immichgallery.domain.model.SearchSuggestionType
import com.udnahc.immichgallery.domain.model.SearchVisibility
import com.udnahc.immichgallery.domain.model.TakenDateRange
import com.udnahc.immichgallery.domain.model.valueOrNull
import com.udnahc.immichgallery.ui.theme.Dimens
import immichgallery.composeapp.generated.resources.Res
import immichgallery.composeapp.generated.resources.cancel
import immichgallery.composeapp.generated.resources.done
import immichgallery.composeapp.generated.resources.retry
import immichgallery.composeapp.generated.resources.search_any
import immichgallery.composeapp.generated.resources.search_camera
import immichgallery.composeapp.generated.resources.search_camera_lens
import immichgallery.composeapp.generated.resources.search_camera_make
import immichgallery.composeapp.generated.resources.search_camera_model
import immichgallery.composeapp.generated.resources.search_date
import immichgallery.composeapp.generated.resources.search_date_end
import immichgallery.composeapp.generated.resources.search_date_invalid
import immichgallery.composeapp.generated.resources.search_date_clear
import immichgallery.composeapp.generated.resources.search_date_not_set
import immichgallery.composeapp.generated.resources.search_date_start
import immichgallery.composeapp.generated.resources.search_date_value
import immichgallery.composeapp.generated.resources.search_display
import immichgallery.composeapp.generated.resources.search_favorite_only
import immichgallery.composeapp.generated.resources.search_location
import immichgallery.composeapp.generated.resources.search_location_city
import immichgallery.composeapp.generated.resources.search_location_country
import immichgallery.composeapp.generated.resources.search_location_state
import immichgallery.composeapp.generated.resources.search_media
import immichgallery.composeapp.generated.resources.search_media_all
import immichgallery.composeapp.generated.resources.search_media_image
import immichgallery.composeapp.generated.resources.search_media_video
import immichgallery.composeapp.generated.resources.search_motion_only
import immichgallery.composeapp.generated.resources.search_not_in_album
import immichgallery.composeapp.generated.resources.search_preferences_unavailable
import immichgallery.composeapp.generated.resources.search_rating
import immichgallery.composeapp.generated.resources.search_rating_stars
import immichgallery.composeapp.generated.resources.search_suggestions_failed
import immichgallery.composeapp.generated.resources.search_unknown
import immichgallery.composeapp.generated.resources.search_unrated
import immichgallery.composeapp.generated.resources.search_visibility
import immichgallery.composeapp.generated.resources.search_visibility_all
import immichgallery.composeapp.generated.resources.search_visibility_archive
import immichgallery.composeapp.generated.resources.search_visibility_timeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

internal fun SearchCriteria.withCountry(value: SearchOptionalValue<String>): SearchCriteria =
    copy(country = value, state = SearchOptionalValue.Any, city = SearchOptionalValue.Any)

internal fun SearchCriteria.withState(value: SearchOptionalValue<String>): SearchCriteria =
    copy(state = value, city = SearchOptionalValue.Any)

internal fun SearchCriteria.withCameraMake(value: SearchOptionalValue<String>): SearchCriteria =
    copy(make = value, model = SearchOptionalValue.Any, lensModel = SearchOptionalValue.Any)

internal fun SearchCriteria.withCameraModel(value: SearchOptionalValue<String>): SearchCriteria =
    copy(model = value, lensModel = SearchOptionalValue.Any)

internal fun SearchCriteria.withMediaType(value: SearchMediaType): SearchCriteria =
    copy(mediaType = value, motionOnly = if (value == SearchMediaType.VIDEO) false else motionOnly)

internal fun SearchCriteria.withMotionOnly(enabled: Boolean): SearchCriteria =
    copy(mediaType = if (enabled) SearchMediaType.IMAGE else mediaType, motionOnly = enabled)

internal enum class SearchDateRangeValidation {
    VALID,
    END_BEFORE_START
}

internal fun validateSearchDateRange(range: TakenDateRange): SearchDateRangeValidation =
    if (range.start != null && range.endInclusive != null && range.start > range.endInclusive) {
        SearchDateRangeValidation.END_BEFORE_START
    } else {
        SearchDateRangeValidation.VALID
    }

internal fun searchDateToPickerMillis(date: LocalDate): Long =
    date.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()

internal fun searchDateFromPickerMillis(epochMillis: Long): LocalDate =
    Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.UTC).date

internal fun shouldShowUnknownSuggestion(
    values: List<SearchSuggestion>,
    selected: SearchOptionalValue<String>
): Boolean = selected == SearchOptionalValue.Unknown || SearchSuggestion.Unknown in values

@Composable
internal fun SearchMetadataFilterSections(
    target: SearchFilterTarget,
    criteria: SearchCriteria,
    preferenceCapabilities: SearchLoadState<SearchPreferenceCapabilities>,
    suggestionOptions: Map<SearchSuggestionType, SearchSuggestionOptionState>,
    onCriteriaChange: (SearchCriteria) -> Unit,
    onLoadSuggestions: (SearchSuggestionRequest) -> Unit,
    onRetrySuggestions: (SearchSuggestionType) -> Unit,
    onRetryPreferences: () -> Unit
) {
    if (target == SearchFilterTarget.ALL || target == SearchFilterTarget.CAMERA) {
        SearchSectionTitle(stringResource(Res.string.search_camera))
        SearchCameraEditor(
            criteria = criteria,
            suggestionOptions = suggestionOptions,
            onCriteriaChange = onCriteriaChange,
            onLoadSuggestions = onLoadSuggestions,
            onRetrySuggestions = onRetrySuggestions
        )
    }
    if (target == SearchFilterTarget.ALL || target == SearchFilterTarget.LOCATION) {
        SearchSectionTitle(stringResource(Res.string.search_location))
        SearchLocationEditor(
            criteria = criteria,
            suggestionOptions = suggestionOptions,
            onCriteriaChange = onCriteriaChange,
            onLoadSuggestions = onLoadSuggestions,
            onRetrySuggestions = onRetrySuggestions
        )
    }
    if (target == SearchFilterTarget.ALL || target == SearchFilterTarget.DATE) {
        SearchSectionTitle(stringResource(Res.string.search_date))
        SearchDateEditor(criteria, onCriteriaChange)
    }
    if (target == SearchFilterTarget.ALL || target == SearchFilterTarget.MEDIA) {
        SearchSectionTitle(stringResource(Res.string.search_media))
        SearchMediaEditor(criteria, onCriteriaChange)
    }
    if (target == SearchFilterTarget.ALL || target == SearchFilterTarget.DISPLAY) {
        SearchSectionTitle(stringResource(Res.string.search_display))
        SearchDisplayEditor(criteria, onCriteriaChange)
    }
    if (target == SearchFilterTarget.ALL || target == SearchFilterTarget.RATING) {
        if (preferenceCapabilities.valueOrNull()?.ratingsEnabled == true) {
            SearchSectionTitle(stringResource(Res.string.search_rating))
            SearchRatingEditor(criteria, onCriteriaChange)
        }
        when (preferenceCapabilities) {
            is SearchLoadState.Error -> SearchUnavailablePreference(onRetryPreferences)
            is SearchLoadState.Loading -> if (preferenceCapabilities.previous == null) {
                CircularProgressIndicator()
            }
            SearchLoadState.Idle -> CircularProgressIndicator()
            is SearchLoadState.Content -> Unit
        }
    }
}

@Composable
private fun SearchCameraEditor(
    criteria: SearchCriteria,
    suggestionOptions: Map<SearchSuggestionType, SearchSuggestionOptionState>,
    onCriteriaChange: (SearchCriteria) -> Unit,
    onLoadSuggestions: (SearchSuggestionRequest) -> Unit,
    onRetrySuggestions: (SearchSuggestionType) -> Unit
) {
    val make = criteria.make.exactValueOrNull()
    val model = criteria.model.exactValueOrNull()
    LaunchedEffect(Unit) {
        onLoadSuggestions(SearchSuggestionRequest(SearchSuggestionType.CAMERA_MAKE))
    }
    LaunchedEffect(make) {
        if (make != null) {
            onLoadSuggestions(SearchSuggestionRequest(SearchSuggestionType.CAMERA_MODEL, make = make))
            onLoadSuggestions(SearchSuggestionRequest(SearchSuggestionType.LENS_MODEL, make = make))
        }
    }
    LaunchedEffect(make, model) {
        if (make != null && model != null) {
            onLoadSuggestions(
                SearchSuggestionRequest(SearchSuggestionType.LENS_MODEL, make = make, model = model)
            )
        }
    }
    SearchSuggestionPicker(
        label = stringResource(Res.string.search_camera_make),
        selected = criteria.make,
        optionState = suggestionOptions[SearchSuggestionType.CAMERA_MAKE],
        onSelected = { onCriteriaChange(criteria.withCameraMake(it)) },
        onRetry = { onRetrySuggestions(SearchSuggestionType.CAMERA_MAKE) }
    )
    if (make != null) {
        SearchSuggestionPicker(
            label = stringResource(Res.string.search_camera_model),
            selected = criteria.model,
            optionState = suggestionOptions[SearchSuggestionType.CAMERA_MODEL],
            onSelected = { onCriteriaChange(criteria.withCameraModel(it)) },
            onRetry = { onRetrySuggestions(SearchSuggestionType.CAMERA_MODEL) }
        )
        SearchSuggestionPicker(
            label = stringResource(Res.string.search_camera_lens),
            selected = criteria.lensModel,
            optionState = suggestionOptions[SearchSuggestionType.LENS_MODEL],
            onSelected = { onCriteriaChange(criteria.copy(lensModel = it)) },
            onRetry = { onRetrySuggestions(SearchSuggestionType.LENS_MODEL) }
        )
    }
}

@Composable
private fun SearchLocationEditor(
    criteria: SearchCriteria,
    suggestionOptions: Map<SearchSuggestionType, SearchSuggestionOptionState>,
    onCriteriaChange: (SearchCriteria) -> Unit,
    onLoadSuggestions: (SearchSuggestionRequest) -> Unit,
    onRetrySuggestions: (SearchSuggestionType) -> Unit
) {
    val country = criteria.country.exactValueOrNull()
    val state = criteria.state.exactValueOrNull()
    LaunchedEffect(Unit) {
        onLoadSuggestions(SearchSuggestionRequest(SearchSuggestionType.COUNTRY))
    }
    LaunchedEffect(country) {
        if (country != null) {
            onLoadSuggestions(SearchSuggestionRequest(SearchSuggestionType.STATE, country = country))
            onLoadSuggestions(SearchSuggestionRequest(SearchSuggestionType.CITY, country = country))
        }
    }
    LaunchedEffect(country, state) {
        if (country != null && state != null) {
            onLoadSuggestions(
                SearchSuggestionRequest(SearchSuggestionType.CITY, country = country, state = state)
            )
        }
    }
    SearchSuggestionPicker(
        label = stringResource(Res.string.search_location_country),
        selected = criteria.country,
        optionState = suggestionOptions[SearchSuggestionType.COUNTRY],
        onSelected = { onCriteriaChange(criteria.withCountry(it)) },
        onRetry = { onRetrySuggestions(SearchSuggestionType.COUNTRY) }
    )
    if (country != null) {
        SearchSuggestionPicker(
            label = stringResource(Res.string.search_location_state),
            selected = criteria.state,
            optionState = suggestionOptions[SearchSuggestionType.STATE],
            onSelected = { onCriteriaChange(criteria.withState(it)) },
            onRetry = { onRetrySuggestions(SearchSuggestionType.STATE) }
        )
        SearchSuggestionPicker(
            label = stringResource(Res.string.search_location_city),
            selected = criteria.city,
            optionState = suggestionOptions[SearchSuggestionType.CITY],
            onSelected = { onCriteriaChange(criteria.copy(city = it)) },
            onRetry = { onRetrySuggestions(SearchSuggestionType.CITY) }
        )
    }
}

@Composable
private fun SearchSuggestionPicker(
    label: String,
    selected: SearchOptionalValue<String>,
    optionState: SearchSuggestionOptionState?,
    onSelected: (SearchOptionalValue<String>) -> Unit,
    onRetry: () -> Unit
) {
    var query by remember(label) { mutableStateOf("") }
    val values = optionState?.state?.valueOrNull().orEmpty()
    val showUnknown = shouldShowUnknownSuggestion(values, selected)
    val filtered by produceState(emptyList<SearchSuggestion>(), values, query) {
        value = withContext(Dispatchers.Default) {
            val normalized = query.trim()
            if (normalized.isEmpty()) values else values.filter {
                (it as? SearchSuggestion.Value)?.value?.contains(normalized, ignoreCase = true) == true
            }
        }
    }
    Text(label, style = MaterialTheme.typography.titleSmall)
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Dimens.mediumSpacing),
        verticalArrangement = Arrangement.spacedBy(Dimens.smallSpacing),
        modifier = Modifier.padding(vertical = Dimens.smallSpacing)
    ) {
        FilterChip(
            selected = selected == SearchOptionalValue.Any,
            onClick = { onSelected(SearchOptionalValue.Any) },
            label = { Text(stringResource(Res.string.search_any)) }
        )
        if (showUnknown) {
            FilterChip(
                selected = selected == SearchOptionalValue.Unknown,
                onClick = { onSelected(SearchOptionalValue.Unknown) },
                label = { Text(stringResource(Res.string.search_unknown)) }
            )
        }
    }
    when (val loadState = optionState?.state) {
        is SearchLoadState.Loading -> if (loadState.previous == null) {
            CircularProgressIndicator()
        }
        is SearchLoadState.Error -> SearchSuggestionError(onRetry)
        else -> Unit
    }
    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = Dimens.albumCoverHeight)) {
        items(filtered, key = { it.suggestionKey }) { suggestion ->
            val value = (suggestion as? SearchSuggestion.Value)?.value ?: return@items
            SearchOptionRow(
                label = value,
                selected = selected == SearchOptionalValue.Exact(value),
                onClick = { onSelected(SearchOptionalValue.Exact(value)) }
            )
        }
    }
}

@Composable
private fun SearchSuggestionError(onRetry: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            stringResource(Res.string.search_suggestions_failed),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onRetry) { Text(stringResource(Res.string.retry)) }
    }
}

private enum class SearchDateBoundary {
    START,
    END_INCLUSIVE
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchDateEditor(
    criteria: SearchCriteria,
    onCriteriaChange: (SearchCriteria) -> Unit
) {
    val range = criteria.takenDateRange
    var openBoundary by remember { mutableStateOf<SearchDateBoundary?>(null) }
    SearchDateControl(
        label = stringResource(Res.string.search_date_start),
        date = range.start,
        onOpen = { openBoundary = SearchDateBoundary.START },
        onClear = {
            onCriteriaChange(criteria.copy(takenDateRange = range.copy(start = null)))
        }
    )
    SearchDateControl(
        label = stringResource(Res.string.search_date_end),
        date = range.endInclusive,
        onOpen = { openBoundary = SearchDateBoundary.END_INCLUSIVE },
        onClear = {
            onCriteriaChange(criteria.copy(takenDateRange = range.copy(endInclusive = null)))
        }
    )
    if (validateSearchDateRange(range) == SearchDateRangeValidation.END_BEFORE_START) {
        Text(
            stringResource(Res.string.search_date_invalid),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall
        )
    }
    openBoundary?.let { boundary ->
        key(boundary) {
            val currentDate = when (boundary) {
                SearchDateBoundary.START -> range.start
                SearchDateBoundary.END_INCLUSIVE -> range.endInclusive
            }
            val pickerState = rememberDatePickerState(
                initialSelectedDateMillis = currentDate?.let(::searchDateToPickerMillis)
            )
            DatePickerDialog(
                onDismissRequest = { openBoundary = null },
                confirmButton = {
                    TextButton(
                        enabled = pickerState.selectedDateMillis != null,
                        onClick = {
                            pickerState.selectedDateMillis
                                ?.let(::searchDateFromPickerMillis)
                                ?.let { selectedDate ->
                                    val selectedRange = when (boundary) {
                                        SearchDateBoundary.START -> range.copy(start = selectedDate)
                                        SearchDateBoundary.END_INCLUSIVE -> {
                                            range.copy(endInclusive = selectedDate)
                                        }
                                    }
                                    onCriteriaChange(criteria.copy(takenDateRange = selectedRange))
                                    openBoundary = null
                                }
                        }
                    ) {
                        Text(stringResource(Res.string.done))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { openBoundary = null }) {
                        Text(stringResource(Res.string.cancel))
                    }
                }
            ) {
                DatePicker(state = pickerState, showModeToggle = false)
            }
        }
    }
}

@Composable
private fun SearchDateControl(
    label: String,
    date: LocalDate?,
    onOpen: () -> Unit,
    onClear: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedButton(onClick = onOpen, modifier = Modifier.weight(1f)) {
            Text(
                stringResource(
                    Res.string.search_date_value,
                    label,
                    date?.toString() ?: stringResource(Res.string.search_date_not_set)
                )
            )
        }
        if (date != null) {
            TextButton(onClick = onClear) {
                Text(stringResource(Res.string.search_date_clear, label))
            }
        }
    }
}

@Composable
private fun SearchMediaEditor(
    criteria: SearchCriteria,
    onCriteriaChange: (SearchCriteria) -> Unit
) {
    val labels = listOf(
        SearchMediaType.ALL to stringResource(Res.string.search_media_all),
        SearchMediaType.IMAGE to stringResource(Res.string.search_media_image),
        SearchMediaType.VIDEO to stringResource(Res.string.search_media_video)
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.mediumSpacing)) {
        labels.forEach { (value, label) ->
            FilterChip(
                selected = criteria.mediaType == value,
                onClick = { onCriteriaChange(criteria.withMediaType(value)) },
                label = { Text(label) }
            )
        }
    }
    SearchCheckboxRow(
        label = stringResource(Res.string.search_motion_only),
        checked = criteria.motionOnly,
        enabled = criteria.mediaType != SearchMediaType.VIDEO,
        onChange = { onCriteriaChange(criteria.withMotionOnly(it)) }
    )
}

@Composable
private fun SearchDisplayEditor(
    criteria: SearchCriteria,
    onCriteriaChange: (SearchCriteria) -> Unit
) {
    SearchCheckboxRow(
        label = stringResource(Res.string.search_favorite_only),
        checked = criteria.isFavorite,
        onChange = { onCriteriaChange(criteria.copy(isFavorite = it)) }
    )
    SearchCheckboxRow(
        label = stringResource(Res.string.search_not_in_album),
        checked = criteria.isNotInAlbum,
        onChange = { enabled ->
            onCriteriaChange(
                criteria.copy(
                    albumIds = if (enabled) emptyList() else criteria.albumIds,
                    isNotInAlbum = enabled
                )
            )
        }
    )
    Text(stringResource(Res.string.search_visibility), style = MaterialTheme.typography.titleSmall)
    listOf(
        SearchVisibility.ALL_ACCESSIBLE to stringResource(Res.string.search_visibility_all),
        SearchVisibility.TIMELINE to stringResource(Res.string.search_visibility_timeline),
        SearchVisibility.ARCHIVE to stringResource(Res.string.search_visibility_archive)
    ).forEach { (visibility, label) ->
        SearchRadioRow(
            label = label,
            selected = criteria.visibility == visibility,
            onClick = { onCriteriaChange(criteria.copy(visibility = visibility)) }
        )
    }
}

@Composable
private fun SearchRatingEditor(
    criteria: SearchCriteria,
    onCriteriaChange: (SearchCriteria) -> Unit
) {
    SearchRadioRow(
        label = stringResource(Res.string.search_any),
        selected = criteria.rating == RatingConstraint.Any,
        onClick = { onCriteriaChange(criteria.copy(rating = RatingConstraint.Any)) }
    )
    SearchRadioRow(
        label = stringResource(Res.string.search_unrated),
        selected = criteria.rating == RatingConstraint.Unrated,
        onClick = { onCriteriaChange(criteria.copy(rating = RatingConstraint.Unrated)) }
    )
    (1..5).forEach { stars ->
        SearchRadioRow(
            label = stringResource(Res.string.search_rating_stars, stars),
            selected = criteria.rating == RatingConstraint.Stars(stars),
            onClick = { onCriteriaChange(criteria.copy(rating = RatingConstraint.Stars(stars))) }
        )
    }
}

@Composable
private fun SearchCheckboxRow(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange, enabled = enabled)
        Text(label)
    }
}

@Composable
private fun SearchRadioRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label)
    }
}

@Composable
internal fun SearchSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = Dimens.largeSpacing, bottom = Dimens.smallSpacing)
    )
}

@Composable
private fun SearchUnavailablePreference(
    onRetry: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(Res.string.search_preferences_unavailable),
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(onClick = onRetry) { Text(stringResource(Res.string.retry)) }
    }
}

private fun SearchOptionalValue<String>.exactValueOrNull(): String? =
    (this as? SearchOptionalValue.Exact<String>)?.value

private val SearchSuggestion.suggestionKey: String
    get() = when (this) {
        SearchSuggestion.Unknown -> "unknown"
        is SearchSuggestion.Value -> value
    }
