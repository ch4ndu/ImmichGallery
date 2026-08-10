package com.udnahc.immichgallery.domain.usecase.search

import com.udnahc.immichgallery.data.model.SearchTagResponse
import com.udnahc.immichgallery.data.repository.SearchRepository
import com.udnahc.immichgallery.domain.model.SearchTag
import com.udnahc.immichgallery.runSuspendCatching

class GetSearchTagsUseCase internal constructor(
    private val getTags: suspend () -> List<SearchTagResponse>
) {
    constructor(repository: SearchRepository) : this(repository::getSearchTags)

    suspend operator fun invoke(): Result<List<SearchTag>> = runSuspendCatching {
        getTags().map { response ->
            SearchTag(
                id = response.id,
                parentId = response.parentId,
                name = response.name,
                value = response.value,
                color = response.color
            )
        }.distinctBy(SearchTag::id)
    }
}
