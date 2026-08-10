package com.udnahc.immichgallery.data.remote

import com.udnahc.immichgallery.domain.model.RatingConstraint
import com.udnahc.immichgallery.domain.model.SearchCriteria
import com.udnahc.immichgallery.domain.model.SearchEndpoint
import com.udnahc.immichgallery.domain.model.SearchMediaType
import com.udnahc.immichgallery.domain.model.SearchOptionalValue
import com.udnahc.immichgallery.domain.model.SearchTextMode
import com.udnahc.immichgallery.domain.model.SearchVisibility
import com.udnahc.immichgallery.domain.model.TagConstraint
import com.udnahc.immichgallery.domain.model.selectSearchEndpoint
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object AdvancedSearchRequestBuilder {
    fun buildSmart(
        criteria: SearchCriteria,
        page: Int,
        size: Int
    ): JsonObject = build(SearchEndpoint.SMART, criteria, page, size)

    fun buildMetadata(
        criteria: SearchCriteria,
        page: Int,
        size: Int
    ): JsonObject = build(SearchEndpoint.METADATA, criteria, page, size)

    private fun build(
        endpoint: SearchEndpoint,
        criteria: SearchCriteria,
        page: Int,
        size: Int
    ): JsonObject {
        require(page > 0) { "Search page must be positive" }
        require(size > 0) { "Search page size must be positive" }
        require(criteria == criteria.normalized()) { "Search criteria must be normalized" }
        require(!criteria.isEmpty) { "An empty Search request is not allowed" }
        require(endpoint == selectSearchEndpoint(criteria)) {
            "Search endpoint does not match the submitted criteria"
        }

        return buildJsonObject {
            put("page", page)
            put("size", size)

            if (criteria.query.isNotBlank()) {
                when (endpoint) {
                    SearchEndpoint.SMART -> put("query", criteria.query)
                    SearchEndpoint.METADATA -> when (criteria.textMode) {
                        SearchTextMode.FILENAME -> put("originalFileName", criteria.query)
                        SearchTextMode.DESCRIPTION -> put("description", criteria.query)
                        SearchTextMode.OCR -> put("ocr", criteria.query)
                        SearchTextMode.CONTEXT -> error("Context text cannot use Metadata Search")
                    }
                }
            }

            putIds("personIds", criteria.personIds)
            putIds("albumIds", criteria.albumIds)
            when (val tags = criteria.tags) {
                TagConstraint.Any -> Unit
                TagConstraint.Untagged -> put("tagIds", JsonNull)
                is TagConstraint.Selected -> putIds("tagIds", tags.ids)
            }

            putOptional("country", criteria.country)
            putOptional("state", criteria.state)
            putOptional("city", criteria.city)
            putOptional("make", criteria.make)
            putOptional("model", criteria.model)
            putOptional("lensModel", criteria.lensModel)

            criteria.takenDateRange.start?.let { put("takenAfter", it.startBoundary()) }
            criteria.takenDateRange.endInclusive?.let { put("takenBefore", it.endBoundary()) }

            when (criteria.mediaType) {
                SearchMediaType.ALL -> Unit
                SearchMediaType.IMAGE -> put("type", "IMAGE")
                SearchMediaType.VIDEO -> put("type", "VIDEO")
            }
            if (criteria.motionOnly) put("isMotion", true)
            if (criteria.isFavorite) put("isFavorite", true)
            if (criteria.isNotInAlbum) put("isNotInAlbum", true)
            when (criteria.visibility) {
                SearchVisibility.ALL_ACCESSIBLE -> Unit
                SearchVisibility.TIMELINE -> put("visibility", "timeline")
                SearchVisibility.ARCHIVE -> put("visibility", "archive")
            }
            when (val rating = criteria.rating) {
                RatingConstraint.Any -> Unit
                RatingConstraint.Unrated -> put("rating", JsonNull)
                is RatingConstraint.Stars -> put("rating", rating.value)
            }
        }
    }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putIds(
    key: String,
    ids: List<String>
) {
    if (ids.isEmpty()) return
    put(key, buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } })
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putOptional(
    key: String,
    value: SearchOptionalValue<String>
) {
    when (value) {
        SearchOptionalValue.Any -> Unit
        SearchOptionalValue.Unknown -> put(key, JsonNull)
        is SearchOptionalValue.Exact<String> -> put(key, value.value)
    }
}

private fun LocalDate.startBoundary(): String = "${this}T00:00:00.000Z"

private fun LocalDate.endBoundary(): String = "${this}T23:59:59.999Z"
