package com.udnahc.immichgallery.ui.screen.search

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.size.Precision
import com.udnahc.immichgallery.LocalAppActive
import com.udnahc.immichgallery.domain.model.Album
import com.udnahc.immichgallery.domain.model.Person
import com.udnahc.immichgallery.domain.model.SearchLoadState
import com.udnahc.immichgallery.domain.model.SearchTag
import com.udnahc.immichgallery.domain.model.TagConstraint
import com.udnahc.immichgallery.domain.model.valueOrNull
import com.udnahc.immichgallery.ui.theme.Dimens
import immichgallery.composeapp.generated.resources.Res
import immichgallery.composeapp.generated.resources.clear_search
import immichgallery.composeapp.generated.resources.ic_close
import immichgallery.composeapp.generated.resources.items_count
import immichgallery.composeapp.generated.resources.retry
import immichgallery.composeapp.generated.resources.search_album_options_failed
import immichgallery.composeapp.generated.resources.search_all_albums_explanation
import immichgallery.composeapp.generated.resources.search_all_people_explanation
import immichgallery.composeapp.generated.resources.search_all_tags_explanation
import immichgallery.composeapp.generated.resources.search_albums_placeholder
import immichgallery.composeapp.generated.resources.search_people_placeholder
import immichgallery.composeapp.generated.resources.search_people_options_failed
import immichgallery.composeapp.generated.resources.search_tag_options_failed
import immichgallery.composeapp.generated.resources.search_tags_placeholder
import immichgallery.composeapp.generated.resources.search_untagged
import immichgallery.composeapp.generated.resources.unknown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

private const val SEARCH_THUMBNAIL_DECODE_SIZE = 256

internal fun projectPeopleOptions(
    options: List<Person>,
    selectedIds: List<String>,
    query: String
): List<Person> = projectSelectedFirst(
    options = options,
    selectedIds = selectedIds,
    query = query,
    id = Person::id,
    searchableText = Person::name
)

internal fun projectAlbumOptions(
    options: List<Album>,
    selectedIds: List<String>,
    query: String
): List<Album> = projectSelectedFirst(
    options = options,
    selectedIds = selectedIds,
    query = query,
    id = Album::id,
    searchableText = Album::name
)

internal fun projectTagOptions(
    options: List<SearchTag>,
    selectedIds: List<String>,
    query: String
): List<SearchTag> = projectSelectedFirst(
    options = options,
    selectedIds = selectedIds,
    query = query,
    id = SearchTag::id,
    searchableText = { "${it.name} ${it.value}" }
)

private fun <T> projectSelectedFirst(
    options: List<T>,
    selectedIds: List<String>,
    query: String,
    id: (T) -> String,
    searchableText: (T) -> String
): List<T> {
    val normalizedQuery = query.trim()
    val filtered = if (normalizedQuery.isEmpty()) {
        options
    } else {
        options.filter { searchableText(it).contains(normalizedQuery, ignoreCase = true) }
    }
    if (normalizedQuery.isNotEmpty()) return filtered
    val selectedOrder = selectedIds.withIndex().associate { it.value to it.index }
    return filtered.withIndex()
        .sortedWith(
            compareBy<IndexedValue<T>>(
                { selectedOrder[id(it.value)] ?: Int.MAX_VALUE },
                IndexedValue<T>::index
            )
        )
        .map(IndexedValue<T>::value)
}

internal fun resolvedEntityLabel(
    id: String,
    options: List<Pair<String, String>>,
    unavailableLabel: String
): String = options.firstOrNull { it.first == id }
    ?.second
    ?.takeIf(String::isNotBlank)
    ?: unavailableLabel

@Composable
internal fun SearchPeoplePicker(
    state: SearchLoadState<List<Person>>,
    selectedIds: List<String>,
    query: String,
    onQueryChange: (String) -> Unit,
    onSelectionChange: (List<String>) -> Unit,
    onRetry: () -> Unit
) {
    val options by projectedOptions(state.valueOrNull().orEmpty(), selectedIds, query, ::projectPeopleOptions)
    SearchPickerHeader(
        query = query,
        placeholder = stringResource(Res.string.search_people_placeholder),
        onQueryChange = onQueryChange,
        explanation = stringResource(Res.string.search_all_people_explanation)
    )
    SearchOptionLoadContent(
        state = state,
        errorText = stringResource(Res.string.search_people_options_failed),
        onRetry = onRetry
    ) {
        items(options, key = Person::id) { person ->
            PersonOptionRow(
                person = person,
                selected = person.id in selectedIds,
                onClick = { onSelectionChange(selectedIds.toggled(person.id)) }
            )
        }
    }
}

@Composable
internal fun SearchAlbumPicker(
    state: SearchLoadState<List<Album>>,
    selectedIds: List<String>,
    query: String,
    onQueryChange: (String) -> Unit,
    onSelectionChange: (List<String>) -> Unit,
    onRetry: () -> Unit
) {
    val options by projectedOptions(state.valueOrNull().orEmpty(), selectedIds, query, ::projectAlbumOptions)
    SearchPickerHeader(
        query = query,
        placeholder = stringResource(Res.string.search_albums_placeholder),
        onQueryChange = onQueryChange,
        explanation = stringResource(Res.string.search_all_albums_explanation)
    )
    SearchOptionLoadContent(
        state = state,
        errorText = stringResource(Res.string.search_album_options_failed),
        onRetry = onRetry
    ) {
        items(options, key = Album::id) { album ->
            AlbumOptionRow(
                album = album,
                selected = album.id in selectedIds,
                onClick = { onSelectionChange(selectedIds.toggled(album.id)) }
            )
        }
    }
}

@Composable
internal fun SearchTagPicker(
    state: SearchLoadState<List<SearchTag>>,
    constraint: TagConstraint,
    query: String,
    onQueryChange: (String) -> Unit,
    onConstraintChange: (TagConstraint) -> Unit,
    onRetry: () -> Unit
) {
    val selectedIds = (constraint as? TagConstraint.Selected)?.ids.orEmpty()
    val options by projectedOptions(state.valueOrNull().orEmpty(), selectedIds, query, ::projectTagOptions)
    SearchPickerHeader(
        query = query,
        placeholder = stringResource(Res.string.search_tags_placeholder),
        onQueryChange = onQueryChange,
        explanation = stringResource(Res.string.search_all_tags_explanation)
    )
    SearchOptionRow(
        label = stringResource(Res.string.search_untagged),
        selected = constraint == TagConstraint.Untagged,
        onClick = {
            onConstraintChange(
                if (constraint == TagConstraint.Untagged) TagConstraint.Any else TagConstraint.Untagged
            )
        }
    )
    SearchOptionLoadContent(
        state = state,
        errorText = stringResource(Res.string.search_tag_options_failed),
        onRetry = onRetry
    ) {
        items(options, key = SearchTag::id) { tag ->
            SearchOptionRow(
                label = tag.value,
                selected = tag.id in selectedIds,
                leading = {
                    val color = tag.color?.let(::parseSearchTagColor)
                        ?: MaterialTheme.colorScheme.outline
                    Box(
                        Modifier
                            .size(Dimens.iconSize)
                            .clip(CircleShape)
                            .background(color)
                    )
                },
                onClick = {
                    val ids = selectedIds.toggled(tag.id)
                    onConstraintChange(
                        ids.takeIf(List<String>::isNotEmpty)
                            ?.let(TagConstraint::Selected)
                            ?: TagConstraint.Any
                    )
                }
            )
        }
    }
}

@Composable
private fun SearchPickerHeader(
    query: String,
    placeholder: String,
    onQueryChange: (String) -> Unit,
    explanation: String
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(placeholder) },
        singleLine = true,
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_close),
                        contentDescription = stringResource(Res.string.clear_search)
                    )
                }
            }
        },
        modifier = Modifier.fillMaxWidth()
    )
    Text(
        text = explanation,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = Dimens.mediumSpacing)
    )
}

@Composable
private fun <T> SearchOptionLoadContent(
    state: SearchLoadState<List<T>>,
    errorText: String,
    onRetry: () -> Unit,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit
) {
    val hasContent = !state.valueOrNull().isNullOrEmpty()
    when {
        state is SearchLoadState.Loading && !hasContent -> {
            Box(Modifier.fillMaxWidth().heightIn(min = Dimens.searchOptionRowMinHeight), Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(Dimens.iconSize))
            }
        }

        state is SearchLoadState.Error && !hasContent -> SearchPickerError(errorText, onRetry)
        else -> {
            if (state is SearchLoadState.Error) SearchPickerError(errorText, onRetry)
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = Dimens.albumCoverHeight)) {
                content()
            }
        }
    }
}

@Composable
private fun SearchPickerError(
    text: String,
    onRetry: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
        TextButton(onClick = onRetry) { Text(stringResource(Res.string.retry)) }
    }
}

@Composable
private fun PersonOptionRow(
    person: Person,
    selected: Boolean,
    onClick: () -> Unit
) {
    val label = person.name.ifBlank { stringResource(Res.string.unknown) }
    SearchOptionRow(
        label = label,
        selected = selected,
        leading = { AuthenticatedPickerImage(person.thumbnailUrl, circular = true) },
        onClick = onClick
    )
}

@Composable
private fun AlbumOptionRow(
    album: Album,
    selected: Boolean,
    onClick: () -> Unit
) {
    SearchOptionRow(
        label = album.name,
        supporting = stringResource(Res.string.items_count, album.assetCount),
        selected = selected,
        leading = { AuthenticatedPickerImage(album.thumbnailUrl, circular = false) },
        onClick = onClick
    )
}

@Composable
internal fun SearchOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    supporting: String? = null,
    leading: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.searchOptionRowMinHeight)
            .toggleable(
                value = selected,
                role = Role.Checkbox,
                onValueChange = { onClick() }
            )
            .semantics(mergeDescendants = true) {}
            .padding(vertical = Dimens.smallSpacing),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.mediumSpacing)
    ) {
        leading?.invoke()
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            supporting?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Checkbox(
            checked = selected,
            onCheckedChange = null,
            modifier = Modifier.clearAndSetSemantics {}
        )
    }
}

@Composable
private fun AuthenticatedPickerImage(
    url: String?,
    circular: Boolean
) {
    val modifier = Modifier
        .size(Dimens.searchPickerThumbnailSize)
        .clip(if (circular) CircleShape else MaterialTheme.shapes.small)
    if (LocalAppActive.current && !url.isNullOrBlank()) {
        val painter = rememberAsyncImagePainter(
            ImageRequest.Builder(LocalPlatformContext.current)
                .data(url)
                .precision(Precision.EXACT)
                .size(SEARCH_THUMBNAIL_DECODE_SIZE, SEARCH_THUMBNAIL_DECODE_SIZE)
                .build()
        )
        Image(
            painter = painter,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier
        )
    } else {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
    }
}

@Composable
private fun <T> projectedOptions(
    options: List<T>,
    selectedIds: List<String>,
    query: String,
    projector: (List<T>, List<String>, String) -> List<T>
): State<List<T>> = produceState(emptyList(), options, selectedIds, query) {
    value = withContext(Dispatchers.Default) { projector(options, selectedIds, query) }
}

private fun List<String>.toggled(id: String): List<String> =
    if (id in this) filterNot { it == id } else this + id

private fun parseSearchTagColor(value: String): Color? {
    val raw = value.removePrefix("#")
    if (raw.length != 6 && raw.length != 8) return null
    val parsed = raw.toLongOrNull(16) ?: return null
    return Color(if (raw.length == 6) parsed or 0xFF000000 else parsed)
}
