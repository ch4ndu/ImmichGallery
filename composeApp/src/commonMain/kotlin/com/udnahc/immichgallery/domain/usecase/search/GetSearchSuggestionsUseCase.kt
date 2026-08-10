package com.udnahc.immichgallery.domain.usecase.search

import com.udnahc.immichgallery.data.repository.SearchRepository
import com.udnahc.immichgallery.domain.model.SearchSuggestion
import com.udnahc.immichgallery.domain.model.SearchSuggestionRequest
import com.udnahc.immichgallery.runSuspendCatching

class GetSearchSuggestionsUseCase internal constructor(
    private val getSuggestions: suspend (SearchSuggestionRequest) -> List<String?>
) {
    constructor(repository: SearchRepository) : this(repository::getSearchSuggestions)

    suspend operator fun invoke(request: SearchSuggestionRequest): Result<List<SearchSuggestion>> =
        runSuspendCatching {
            getSuggestions(request.normalized())
                .mapNotNull { value ->
                    value?.trim()
                        ?.takeIf(String::isNotEmpty)
                        ?.let { SearchSuggestion.Value(it) }
                        ?: if (value == null) SearchSuggestion.Unknown else null
                }
                .distinct()
        }
}
