package com.udnahc.immichgallery.domain.model

import androidx.compose.runtime.Immutable
import kotlinx.datetime.LocalDate

enum class SearchTextMode {
    CONTEXT,
    FILENAME,
    DESCRIPTION,
    OCR
}

enum class SearchEndpoint {
    SMART,
    METADATA
}

@Immutable
sealed interface SearchOptionalValue<out T> {
    data object Any : SearchOptionalValue<Nothing>

    data object Unknown : SearchOptionalValue<Nothing>

    @Immutable
    data class Exact<T>(val value: T) : SearchOptionalValue<T>
}

@Immutable
sealed interface TagConstraint {
    data object Any : TagConstraint

    data object Untagged : TagConstraint

    @Immutable
    data class Selected(val ids: List<String>) : TagConstraint
}

@Immutable
sealed interface RatingConstraint {
    data object Any : RatingConstraint

    data object Unrated : RatingConstraint

    @Immutable
    data class Stars(val value: Int) : RatingConstraint {
        init {
            require(value in 1..5) { "Search rating must be between one and five stars" }
        }
    }
}

enum class SearchMediaType {
    ALL,
    IMAGE,
    VIDEO
}

enum class SearchVisibility {
    ALL_ACCESSIBLE,
    TIMELINE,
    ARCHIVE
}

@Immutable
data class TakenDateRange(
    val start: LocalDate? = null,
    val endInclusive: LocalDate? = null
) {
    fun normalized(): TakenDateRange =
        if (start != null && endInclusive != null && start > endInclusive) {
            TakenDateRange(start = endInclusive, endInclusive = start)
        } else {
            this
        }

    val isEmpty: Boolean get() = start == null && endInclusive == null
}

@Immutable
data class SearchCriteria(
    val query: String = "",
    val textMode: SearchTextMode = SearchTextMode.CONTEXT,
    val personIds: List<String> = emptyList(),
    val albumIds: List<String> = emptyList(),
    val tags: TagConstraint = TagConstraint.Any,
    val country: SearchOptionalValue<String> = SearchOptionalValue.Any,
    val state: SearchOptionalValue<String> = SearchOptionalValue.Any,
    val city: SearchOptionalValue<String> = SearchOptionalValue.Any,
    val make: SearchOptionalValue<String> = SearchOptionalValue.Any,
    val model: SearchOptionalValue<String> = SearchOptionalValue.Any,
    val lensModel: SearchOptionalValue<String> = SearchOptionalValue.Any,
    val takenDateRange: TakenDateRange = TakenDateRange(),
    val mediaType: SearchMediaType = SearchMediaType.ALL,
    val motionOnly: Boolean = false,
    val isFavorite: Boolean = false,
    val isNotInAlbum: Boolean = false,
    val visibility: SearchVisibility = SearchVisibility.ALL_ACCESSIBLE,
    val rating: RatingConstraint = RatingConstraint.Any
) {
    fun normalized(): SearchCriteria {
        val normalizedAlbums = normalizeIds(albumIds)
        return copy(
            query = query.trim(),
            personIds = normalizeIds(personIds),
            albumIds = if (isNotInAlbum) emptyList() else normalizedAlbums,
            tags = tags.normalized(),
            country = country.normalized(),
            state = state.normalized(),
            city = city.normalized(),
            make = make.normalized(),
            model = model.normalized(),
            lensModel = lensModel.normalized(),
            takenDateRange = takenDateRange.normalized(),
            mediaType = if (motionOnly) SearchMediaType.IMAGE else mediaType
        )
    }

    fun withAlbumIds(ids: List<String>): SearchCriteria =
        copy(albumIds = ids, isNotInAlbum = false).normalized()

    fun withNotInAlbum(enabled: Boolean): SearchCriteria =
        copy(
            albumIds = if (enabled) emptyList() else albumIds,
            isNotInAlbum = enabled
        ).normalized()

    fun metadataOnly(): SearchCriteria = copy(query = "", textMode = SearchTextMode.CONTEXT)

    val isEmpty: Boolean
        get() = query.isBlank() && !hasMetadataFilters

    val hasMetadataFilters: Boolean
        get() = personIds.isNotEmpty() ||
                albumIds.isNotEmpty() ||
                tags != TagConstraint.Any ||
                country != SearchOptionalValue.Any ||
                state != SearchOptionalValue.Any ||
                city != SearchOptionalValue.Any ||
                make != SearchOptionalValue.Any ||
                model != SearchOptionalValue.Any ||
                lensModel != SearchOptionalValue.Any ||
                !takenDateRange.isEmpty ||
                mediaType != SearchMediaType.ALL ||
                motionOnly ||
                isFavorite ||
                isNotInAlbum ||
                visibility != SearchVisibility.ALL_ACCESSIBLE ||
                rating != RatingConstraint.Any
}

@Immutable
data class SearchSession(
    val criteria: SearchCriteria,
    val endpoint: SearchEndpoint,
    val generation: Long,
    val currentPage: Int = 1,
    val nextPage: Int? = null,
    val consumedPages: Set<Int> = emptySet()
) {
    init {
        require(criteria == criteria.normalized()) { "Search session criteria must be normalized" }
        require(!criteria.isEmpty) { "Search session criteria must not be empty" }
        require(endpoint == selectSearchEndpoint(criteria)) {
            "Search session endpoint does not match its criteria"
        }
        require(generation > 0L) { "Search session generation must be positive" }
        require(currentPage > 0) { "Search session page must be positive" }
        require(nextPage == null || nextPage > 0) { "Search session next page must be positive" }
        require(consumedPages.all { it > 0 }) { "Consumed Search pages must be positive" }
    }
}

fun selectSearchEndpoint(normalizedCriteria: SearchCriteria): SearchEndpoint {
    require(normalizedCriteria == normalizedCriteria.normalized()) {
        "Search endpoint selection requires normalized criteria"
    }
    require(!normalizedCriteria.isEmpty) { "An empty Search criteria cannot select an endpoint" }
    return if (
        normalizedCriteria.query.isNotBlank() &&
        normalizedCriteria.textMode == SearchTextMode.CONTEXT
    ) {
        SearchEndpoint.SMART
    } else {
        SearchEndpoint.METADATA
    }
}

private fun normalizeIds(ids: List<String>): List<String> =
    ids.map(String::trim).filter(String::isNotEmpty).distinct()

private fun TagConstraint.normalized(): TagConstraint = when (this) {
    TagConstraint.Any,
    TagConstraint.Untagged -> this

    is TagConstraint.Selected -> normalizeIds(ids)
        .takeIf(List<String>::isNotEmpty)
        ?.let(TagConstraint::Selected)
        ?: TagConstraint.Any
}

private fun SearchOptionalValue<String>.normalized(): SearchOptionalValue<String> = when (this) {
    SearchOptionalValue.Any,
    SearchOptionalValue.Unknown -> this

    is SearchOptionalValue.Exact<String> -> value.trim()
        .takeIf(String::isNotEmpty)
        ?.let { SearchOptionalValue.Exact(it) }
        ?: SearchOptionalValue.Any
}
