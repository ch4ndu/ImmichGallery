package com.udnahc.immichgallery.ui.screen.people

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.tooling.preview.Preview
import coil3.compose.AsyncImage
import com.udnahc.immichgallery.LocalAppActive
import com.udnahc.immichgallery.domain.model.Person
import com.udnahc.immichgallery.ui.component.ScrollbarOverlay
import com.udnahc.immichgallery.ui.theme.Dimens
import immichgallery.composeapp.generated.resources.Res
import immichgallery.composeapp.generated.resources.ic_close
import immichgallery.composeapp.generated.resources.people_search_placeholder
import immichgallery.composeapp.generated.resources.retry
import immichgallery.composeapp.generated.resources.unknown
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun PeopleScreen(
    onPersonClick: (personId: String, personName: String) -> Unit,
    viewModel: PeopleViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsState()

    PeopleContent(
        state = state,
        onPersonClick = onPersonClick,
        onQueryChange = viewModel::updateQuery,
        onRetry = viewModel::loadPeople
    )
}

@Composable
fun PeopleContent(
    state: PeopleState,
    onPersonClick: (personId: String, personName: String) -> Unit,
    onQueryChange: (String) -> Unit,
    onRetry: () -> Unit
) {
    when {
        state.isLoading && state.people.isEmpty() -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        state.error != null && state.people.isEmpty() -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(state.error, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onRetry) { Text(stringResource(Res.string.retry)) }
                }
            }
        }

        else -> {
            val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            val navBarPadding =
                WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            val gridState = rememberLazyGridState()
            val topContentPadding = statusBarPadding + Dimens.topBarHeight + Dimens.screenPadding

            val focusManager = LocalFocusManager.current
            LaunchedEffect(gridState.isScrollInProgress) {
                if (gridState.isScrollInProgress) focusManager.clearFocus()
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } }
            ) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = onQueryChange,
                    placeholder = { Text(stringResource(Res.string.people_search_placeholder)) },
                    singleLine = true,
                    trailingIcon = {
                        if (state.query.isNotEmpty()) {
                            IconButton(onClick = { onQueryChange("") }) {
                                Icon(painterResource(Res.drawable.ic_close), contentDescription = null)
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = Dimens.screenPadding,
                            end = Dimens.screenPadding,
                            top = topContentPadding,
                            bottom = Dimens.mediumSpacing
                        )
                )

                LaunchedEffect(state.filteredPeople) {
                    gridState.scrollToItem(0)
                }
                ScrollbarOverlay(
                    gridState = gridState,
                    topPadding = 0.dp,
                    bottomPadding = Dimens.bottomBarHeight + navBarPadding
                ) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        state = gridState,
                        contentPadding = PaddingValues(
                            start = Dimens.screenPadding,
                            end = Dimens.screenPadding,
                            top = Dimens.mediumSpacing,
                            bottom = Dimens.bottomBarHeight + navBarPadding
                        ),
                        horizontalArrangement = Arrangement.spacedBy(Dimens.mediumSpacing),
                        verticalArrangement = Arrangement.spacedBy(Dimens.largeSpacing),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(state.filteredPeople, key = { it.id }) { person ->
                            PersonItem(
                                person = person,
                                onClick = { onPersonClick(person.id, person.name) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PersonItem(
    person: Person,
    onClick: () -> Unit
) {
    val unknownLabel = stringResource(Res.string.unknown)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        if (LocalAppActive.current) {
            AsyncImage(
                model = person.thumbnailUrl,
                contentDescription = person.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(Dimens.personAvatarSize)
                    .clip(CircleShape)
            )
        } else {
            Box(
                Modifier
                    .size(Dimens.personAvatarSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
        }
        Text(
            text = person.name.ifBlank { unknownLabel },
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1
        )
    }
}

@Preview
@Composable
private fun PeopleContentPreview() {
    PeopleContent(
        state = PeopleState(
            people = listOf(
                Person("1", "John", ""),
                Person("2", "Jane", "")
            )
        ),
        onPersonClick = { _, _ -> },
        onQueryChange = {},
        onRetry = {}
    )
}
