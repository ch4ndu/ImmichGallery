package com.udnahc.immichgallery.data.repository

import com.udnahc.immichgallery.data.model.SearchResponse
import com.udnahc.immichgallery.data.model.SearchServerFeaturesResponse
import com.udnahc.immichgallery.data.model.SearchTagResponse
import com.udnahc.immichgallery.data.model.SearchUserPreferencesResponse
import com.udnahc.immichgallery.data.remote.ImmichApiService
import com.udnahc.immichgallery.domain.model.SearchCriteria
import com.udnahc.immichgallery.domain.model.SearchSuggestionRequest

class SearchRepository(private val apiService: ImmichApiService) {
    suspend fun searchFavorites(page: Int = 1): SearchResponse =
        apiService.searchFavorites(page)

    suspend fun searchSmart(
        criteria: SearchCriteria,
        page: Int = 1,
        size: Int = 50
    ): SearchResponse =
        apiService.searchSmart(criteria, page, size)

    suspend fun searchMetadata(
        criteria: SearchCriteria,
        page: Int = 1,
        size: Int = 50
    ): SearchResponse =
        apiService.searchMetadata(criteria, page, size)

    suspend fun getSearchServerFeatures(): SearchServerFeaturesResponse =
        apiService.getSearchServerFeatures()

    suspend fun getSearchUserPreferences(): SearchUserPreferencesResponse =
        apiService.getSearchUserPreferences()

    suspend fun getSearchTags(): List<SearchTagResponse> =
        apiService.getSearchTags()

    suspend fun getSearchSuggestions(request: SearchSuggestionRequest): List<String?> =
        apiService.getSearchSuggestions(request)
}
