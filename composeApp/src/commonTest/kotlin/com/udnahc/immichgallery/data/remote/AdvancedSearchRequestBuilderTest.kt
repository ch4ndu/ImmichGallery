package com.udnahc.immichgallery.data.remote

import com.udnahc.immichgallery.domain.model.RatingConstraint
import com.udnahc.immichgallery.domain.model.SearchCriteria
import com.udnahc.immichgallery.domain.model.SearchMediaType
import com.udnahc.immichgallery.domain.model.SearchOptionalValue
import com.udnahc.immichgallery.domain.model.SearchTextMode
import com.udnahc.immichgallery.domain.model.SearchVisibility
import com.udnahc.immichgallery.domain.model.TagConstraint
import com.udnahc.immichgallery.domain.model.TakenDateRange
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class AdvancedSearchRequestBuilderTest {
    @Test
    fun smartBodyContainsOnlyContextTextAndSupportedActiveFilters() {
        val body = AdvancedSearchRequestBuilder.buildSmart(
            SearchCriteria(
                query = "family picnic",
                personIds = listOf("person-2", "person-1"),
                albumIds = listOf("album-1"),
                tags = TagConstraint.Selected(listOf("tag-2", "tag-1")),
                country = SearchOptionalValue.Exact("US"),
                state = SearchOptionalValue.Unknown,
                city = SearchOptionalValue.Exact("Austin"),
                make = SearchOptionalValue.Exact("Fujifilm"),
                model = SearchOptionalValue.Exact("X-T5"),
                lensModel = SearchOptionalValue.Unknown,
                takenDateRange = TakenDateRange(
                    LocalDate(2026, 3, 8),
                    LocalDate(2026, 11, 1)
                ),
                mediaType = SearchMediaType.IMAGE,
                isFavorite = true,
                visibility = SearchVisibility.ARCHIVE,
                rating = RatingConstraint.Stars(4)
            ).normalized(),
            page = 3,
            size = 50
        )

        assertEquals(JsonPrimitive("family picnic"), body["query"])
        assertFalse("queryAssetId" in body)
        assertFalse("originalFileName" in body)
        assertEquals(listOf("person-2", "person-1"), body.getValue("personIds").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("album-1"), body.getValue("albumIds").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("tag-2", "tag-1"), body.getValue("tagIds").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(JsonPrimitive("US"), body["country"])
        assertEquals(JsonNull, body["state"])
        assertEquals(JsonPrimitive("Austin"), body["city"])
        assertEquals(JsonPrimitive("Fujifilm"), body["make"])
        assertEquals(JsonPrimitive("X-T5"), body["model"])
        assertEquals(JsonNull, body["lensModel"])
        assertEquals(JsonPrimitive("2026-03-08T00:00:00.000Z"), body["takenAfter"])
        assertEquals(JsonPrimitive("2026-11-01T23:59:59.999Z"), body["takenBefore"])
        assertEquals(JsonPrimitive("IMAGE"), body["type"])
        assertFalse("isMotion" in body)
        assertEquals(JsonPrimitive(true), body["isFavorite"])
        assertEquals(JsonPrimitive("archive"), body["visibility"])
        assertEquals(JsonPrimitive(4), body["rating"])
        assertEquals(JsonPrimitive(3), body["page"])
        assertEquals(JsonPrimitive(50), body["size"])
        listOf("withDeleted", "withExif", "withStacked", "libraryId").forEach { assertFalse(it in body) }
    }

    @Test
    fun metadataTextModesUseExactlyOneEndpointSpecificField() {
        val expectations = mapOf(
            SearchTextMode.FILENAME to "originalFileName",
            SearchTextMode.DESCRIPTION to "description",
            SearchTextMode.OCR to "ocr"
        )

        expectations.forEach { (mode, key) ->
            val body = AdvancedSearchRequestBuilder.buildMetadata(
                SearchCriteria(query = "needle", textMode = mode).normalized(),
                page = 1,
                size = 50
            )
            assertEquals(setOf("page", "size", key), body.keys)
            assertEquals(JsonPrimitive("needle"), body[key])
        }
    }

    @Test
    fun filterOnlyContextBodyUsesMetadataWithoutAQueryField() {
        val body = AdvancedSearchRequestBuilder.buildMetadata(
            SearchCriteria(isNotInAlbum = true).normalized(),
            page = 1,
            size = 50
        )

        assertEquals(JsonPrimitive(true), body["isNotInAlbum"])
        assertFalse("query" in body)
        assertFalse("originalFileName" in body)
    }

    @Test
    fun anyValuesAreOmittedWhileExplicitMissingValuesAreNull() {
        val body = AdvancedSearchRequestBuilder.buildMetadata(
            SearchCriteria(
                textMode = SearchTextMode.FILENAME,
                tags = TagConstraint.Untagged,
                country = SearchOptionalValue.Any,
                state = SearchOptionalValue.Unknown,
                city = SearchOptionalValue.Any,
                rating = RatingConstraint.Unrated
            ).normalized(),
            page = 1,
            size = 50
        )

        assertFalse("country" in body)
        assertFalse("city" in body)
        assertEquals(JsonNull, body["state"])
        assertEquals(JsonNull, body["tagIds"])
        assertEquals(JsonNull, body["rating"])
        assertFalse("personIds" in body)
        assertFalse("albumIds" in body)
    }

    @Test
    fun motionRequestAlwaysCarriesImageType() {
        val body = AdvancedSearchRequestBuilder.buildMetadata(
            SearchCriteria(motionOnly = true).normalized(),
            page = 1,
            size = 50
        )

        assertEquals(JsonPrimitive("IMAGE"), body["type"])
        assertEquals(JsonPrimitive(true), body["isMotion"])
    }

    @Test
    fun incompatibleEndpointAndUnnormalizedCriteriaAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            AdvancedSearchRequestBuilder.buildSmart(
                SearchCriteria(query = "file", textMode = SearchTextMode.FILENAME),
                page = 1,
                size = 50
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AdvancedSearchRequestBuilder.buildMetadata(
                SearchCriteria(query = " context "),
                page = 1,
                size = 50
            )
        }
    }
}
