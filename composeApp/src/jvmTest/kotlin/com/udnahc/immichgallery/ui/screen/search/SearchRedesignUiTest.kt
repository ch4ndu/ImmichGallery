package com.udnahc.immichgallery.ui.screen.search

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.udnahc.immichgallery.domain.model.*
import com.udnahc.immichgallery.ui.model.SearchUiMessage
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalTestApi::class)
class SearchRedesignUiTest {
    @get:Rule val compose = createComposeRule()
    private val enabledCapabilities = SearchCapabilities(true, true, true)
    private val capabilities = SearchLoadState.Content(enabledCapabilities)

    @Test fun descriptiveModesAndExplicitSubmitWorkAtCompactWidth() {
        var state by mutableStateOf(SearchState(serverCapabilities = capabilities))
        var submissions = 0
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(360.dp, 640.dp)) {
                    SearchContent(state = state,
                        onQueryChange = { state = state.copy(query = it) },
                        onTextModeChange = { state = state.copy(textMode = it) },
                        onSearch = { submissions++ }, onPhotoClick = {},
                        onSetAvailableWidth = {}, onLoadMore = {})
                }
            }
        }
        compose.onNodeWithText("Search by Meaning").performClick()
        compose.onNodeWithText("Find words visible in an image").assertIsDisplayed()
        compose.onNodeWithText("Filename", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Search by Filename").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextInput("holiday")
        compose.onNodeWithContentDescription("Run search").performClick()
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.runOnIdle { assertEquals(2, submissions) }
    }

    @Test fun lastModeAndDiscoveryStayReachableInShortEnlargedTextViewport() {
        var state by mutableStateOf(
            SearchState(serverCapabilities = SearchLoadState.Error(enabledCapabilities))
        )
        var capabilityRetries = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 1.6f)) {
                MaterialTheme {
                    Box(Modifier.size(360.dp, 440.dp)) {
                        SearchContent(state = state,
                            onQueryChange = { state = state.copy(query = it) },
                            onTextModeChange = { state = state.copy(textMode = it) },
                            onSearch = {}, onRetryCapabilities = { capabilityRetries++ },
                            onPhotoClick = {},
                            onSetAvailableWidth = {}, onLoadMore = {})
                    }
                }
            }
        }

        compose.onNodeWithText("Search by Meaning").performClick()
        compose.onNodeWithText("Retry").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, capabilityRetries) }
        val lastMode = compose.onNodeWithText("Text in photos", useUnmergedTree = true)
        lastMode.performScrollTo()
        lastMode.assertIsDisplayed()
        compose.onNodeWithText("Filters").assertIsDisplayed()
        compose.onNode(hasText("People") and hasClickAction()).assertIsDisplayed()
        lastMode.performClick()
        compose.onNodeWithText("Search by Text in photos").assertIsDisplayed()
        compose.onNodeWithText("Filters").performClick()
        compose.onNodeWithText("Apply filters").assertIsDisplayed()
    }

    @Test fun categorySwitchKeepsSearchAndDraftAndCancelDiscards() {
        val state = SearchState(serverCapabilities = capabilities,
            peopleOptions = SearchLoadState.Content(listOf(Person("p1", "Alex", ""), Person("p2", "Sam", ""))),
            albumOptions = SearchLoadState.Content(listOf(Album("a1", "Summer", 12, ""))))
        val submitted = mutableListOf<SearchCriteria>()
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(900.dp, 800.dp)) {
                    SearchContent(state = state, onQueryChange = {}, onTextModeChange = {}, onSearch = {},
                        onApplyCriteria = { submitted.add(it) }, onPhotoClick = {},
                        onSetAvailableWidth = {}, onLoadMore = {})
                }
            }
        }
        compose.onNodeWithText("Filters").performClick()
        compose.onNodeWithText("Search people…").performTextInput("Alex")
        compose.onNode(hasText("Alex") and isToggleable()).performClick()
        compose.onNode(hasText("Albums") and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithText("Search albums…").performTextInput("Summer")
        compose.onNode(hasText("Summer") and isToggleable()).performClick()
        compose.onNode(hasText("People") and hasAnyAncestor(isDialog())).performClick()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).assertTextContains("Alex")
        compose.onNodeWithText("Apply filters").performClick()
        compose.runOnIdle {
            assertEquals(listOf("p1"), submitted.single().personIds)
            assertEquals(listOf("a1"), submitted.single().albumIds)
        }
        compose.onNodeWithText("Filters").performClick()
        compose.onNodeWithText("Sam").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(1, submitted.size) }
    }

    @Test fun sparsePagesAdvanceAutomaticallyAndFailureNeedsRetry() {
        val criteria = SearchCriteria(query = "mountains")
        var state by mutableStateOf(SearchState(query = "mountains", hasSearched = true,
            serverCapabilities = capabilities,
            submittedSession = SearchSession(criteria, SearchEndpoint.SMART, 1, nextPage = 2)))
        val requested = mutableListOf<Int?>()
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(360.dp, 640.dp)) {
                    SearchContent(state = state, onQueryChange = {}, onTextModeChange = {}, onSearch = {},
                        onPhotoClick = {}, onSetAvailableWidth = {}, onLoadMore = {
                            requested.add(state.nextPage)
                            state = if (state.nextPage == 2) state.copy(
                                submittedSession = state.submittedSession?.copy(currentPage = 2, nextPage = 3)
                            ) else state.copy(loadMoreError = SearchUiMessage.SearchFailed)
                        })
                }
            }
        }
        compose.waitUntil { requested.size == 2 }
        compose.onNodeWithText("Load more").assertDoesNotExist()
        compose.onNodeWithText("Retry").assertIsDisplayed()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf<Int?>(2, 3), requested) }
        compose.onNodeWithText("Retry").performClick()
        compose.runOnIdle { assertEquals(listOf<Int?>(2, 3, 3), requested) }
    }
}
