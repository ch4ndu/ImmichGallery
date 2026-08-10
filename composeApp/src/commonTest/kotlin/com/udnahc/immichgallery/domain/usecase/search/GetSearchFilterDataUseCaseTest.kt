package com.udnahc.immichgallery.domain.usecase.search

import com.udnahc.immichgallery.data.model.SearchPreferenceEnabledResponse
import com.udnahc.immichgallery.data.model.SearchServerFeaturesResponse
import com.udnahc.immichgallery.data.model.SearchTagResponse
import com.udnahc.immichgallery.data.model.SearchUserPreferencesResponse
import com.udnahc.immichgallery.domain.model.SearchSuggestion
import com.udnahc.immichgallery.domain.model.SearchSuggestionRequest
import com.udnahc.immichgallery.domain.model.SearchSuggestionType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GetSearchFilterDataUseCaseTest {
    @Test
    fun serverAndPreferenceCapabilitiesRemainIndependent() = runTest {
        val useCase = GetSearchCapabilitiesUseCase(
            getServerFeatures = {
                SearchServerFeaturesResponse(search = true, smartSearch = true, ocr = null)
            },
            getUserPreferences = { error("preference permission denied") }
        )

        val server = useCase.getServerCapabilities().getOrThrow()
        val preferences = useCase.getPreferenceCapabilities()

        assertTrue(server.searchEnabled)
        assertTrue(server.contextEnabled)
        assertEquals(false, server.ocrEnabled)
        assertTrue(preferences.isFailure)
    }

    @Test
    fun preferencesMapOnlyPositivelyEnabledOptionalSections() = runTest {
        val useCase = GetSearchCapabilitiesUseCase(
            getServerFeatures = { SearchServerFeaturesResponse() },
            getUserPreferences = {
                SearchUserPreferencesResponse(
                    tags = SearchPreferenceEnabledResponse(enabled = true),
                    ratings = SearchPreferenceEnabledResponse(enabled = null)
                )
            }
        )

        val preferences = useCase.getPreferenceCapabilities().getOrThrow()

        assertTrue(preferences.tagsEnabled)
        assertEquals(false, preferences.ratingsEnabled)
    }

    @Test
    fun tagsRetainHierarchyAndStableFirstIdOccurrence() = runTest {
        val useCase = GetSearchTagsUseCase {
            listOf(
                SearchTagResponse("tag-2", "tag-1", "Beach", "Travel/Beach", "#abc"),
                SearchTagResponse("tag-1", null, "Travel", "Travel", null),
                SearchTagResponse("tag-2", null, "Duplicate", "Duplicate", null)
            )
        }

        val tags = useCase().getOrThrow()

        assertEquals(listOf("tag-2", "tag-1"), tags.map { it.id })
        assertEquals("Travel/Beach", tags.first().value)
        assertEquals("tag-1", tags.first().parentId)
        assertEquals("#abc", tags.first().color)
    }

    @Test
    fun suggestionsNormalizeRequestValuesAndPreserveDistinctServerOrder() = runTest {
        var submitted: SearchSuggestionRequest? = null
        val useCase = GetSearchSuggestionsUseCase { request ->
            submitted = request
            listOf(" Austin ", null, "Austin", "", null, "Dallas")
        }

        val suggestions = useCase(
            SearchSuggestionRequest(
                type = SearchSuggestionType.CITY,
                country = " US ",
                state = " Texas ",
                make = "ignored"
            )
        ).getOrThrow()

        assertEquals(
            SearchSuggestionRequest(
                type = SearchSuggestionType.CITY,
                country = "US",
                state = "Texas"
            ),
            submitted
        )
        assertEquals(
            listOf(
                SearchSuggestion.Value("Austin"),
                SearchSuggestion.Unknown,
                SearchSuggestion.Value("Dallas")
            ),
            suggestions
        )
    }

    @Test
    fun optionalReadsPreserveCancellation() = runTest {
        val useCase = GetSearchSuggestionsUseCase {
            throw CancellationException("cancelled")
        }

        var cancelled = false
        try {
            useCase(SearchSuggestionRequest(SearchSuggestionType.COUNTRY))
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
    }
}
