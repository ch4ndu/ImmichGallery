package com.udnahc.immichgallery.data.remote

import com.udnahc.immichgallery.domain.model.SearchSuggestionRequest
import com.udnahc.immichgallery.domain.model.SearchSuggestionType
import kotlin.test.Test
import kotlin.test.assertEquals

class SearchSuggestionQueryTest {
    @Test
    fun locationRequestsKeepOnlyTheirSupportedNormalizedDependencies() {
        val parameters = SearchSuggestionRequest(
            type = SearchSuggestionType.CITY,
            country = "  US ",
            state = " Texas ",
            make = "ignored",
            model = "ignored"
        ).toQueryParameters()

        assertEquals(
            listOf(
                "type" to "city",
                "includeNull" to "true",
                "country" to "US",
                "state" to "Texas"
            ),
            parameters
        )
    }

    @Test
    fun cameraRequestsUseStableWireTypesAndDependencies() {
        val expectedTypes = mapOf(
            SearchSuggestionType.COUNTRY to "country",
            SearchSuggestionType.STATE to "state",
            SearchSuggestionType.CITY to "city",
            SearchSuggestionType.CAMERA_MAKE to "camera-make",
            SearchSuggestionType.CAMERA_MODEL to "camera-model",
            SearchSuggestionType.LENS_MODEL to "lens-model"
        )

        expectedTypes.forEach { (type, wireValue) ->
            assertEquals(
                wireValue,
                SearchSuggestionRequest(type = type).toQueryParameters().first().second
            )
        }
        assertEquals(
            listOf(
                "type" to "lens-model",
                "includeNull" to "true",
                "make" to "Fujifilm",
                "model" to "X-T5"
            ),
            SearchSuggestionRequest(
                type = SearchSuggestionType.LENS_MODEL,
                make = " Fujifilm ",
                model = " X-T5 "
            ).toQueryParameters()
        )
    }
}
