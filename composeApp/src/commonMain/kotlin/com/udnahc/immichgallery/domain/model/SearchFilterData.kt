package com.udnahc.immichgallery.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class SearchCapabilities(
    val searchEnabled: Boolean,
    val contextEnabled: Boolean,
    val ocrEnabled: Boolean
)

@Immutable
data class SearchPreferenceCapabilities(
    val tagsEnabled: Boolean,
    val ratingsEnabled: Boolean
)

@Immutable
data class SearchTag(
    val id: String,
    val parentId: String?,
    val name: String,
    val value: String,
    val color: String?
)

enum class SearchSuggestionType {
    COUNTRY,
    STATE,
    CITY,
    CAMERA_MAKE,
    CAMERA_MODEL,
    LENS_MODEL
}

@Immutable
data class SearchSuggestionRequest(
    val type: SearchSuggestionType,
    val country: String? = null,
    val state: String? = null,
    val make: String? = null,
    val model: String? = null
) {
    fun normalized(): SearchSuggestionRequest {
        val normalizedCountry = country.normalizedDependency()
        val normalizedState = state.normalizedDependency()
        val normalizedMake = make.normalizedDependency()
        val normalizedModel = model.normalizedDependency()
        return when (type) {
            SearchSuggestionType.COUNTRY,
            SearchSuggestionType.CAMERA_MAKE -> copy(
                country = null,
                state = null,
                make = null,
                model = null
            )

            SearchSuggestionType.STATE -> copy(
                country = normalizedCountry,
                state = null,
                make = null,
                model = null
            )

            SearchSuggestionType.CITY -> copy(
                country = normalizedCountry,
                state = normalizedState,
                make = null,
                model = null
            )

            SearchSuggestionType.CAMERA_MODEL -> copy(
                country = null,
                state = null,
                make = normalizedMake,
                model = null
            )

            SearchSuggestionType.LENS_MODEL -> copy(
                country = null,
                state = null,
                make = normalizedMake,
                model = normalizedModel
            )
        }
    }
}

@Immutable
sealed interface SearchSuggestion {
    data object Unknown : SearchSuggestion

    @Immutable
    data class Value(val value: String) : SearchSuggestion
}

@Immutable
sealed interface SearchLoadState<out T> {
    data object Idle : SearchLoadState<Nothing>

    @Immutable
    data class Loading<T>(val previous: T? = null) : SearchLoadState<T>

    @Immutable
    data class Content<T>(val value: T) : SearchLoadState<T>

    @Immutable
    data class Error<T>(val previous: T? = null) : SearchLoadState<T>
}

fun <T> SearchLoadState<T>.valueOrNull(): T? = when (this) {
    SearchLoadState.Idle -> null
    is SearchLoadState.Loading -> previous
    is SearchLoadState.Content -> value
    is SearchLoadState.Error -> previous
}

private fun String?.normalizedDependency(): String? =
    this?.trim()?.takeIf(String::isNotEmpty)
