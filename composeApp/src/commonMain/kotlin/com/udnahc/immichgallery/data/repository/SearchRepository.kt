package com.udnahc.immichgallery.data.repository

import com.udnahc.immichgallery.data.model.SearchResponse
import com.udnahc.immichgallery.data.remote.ImmichApiService

class SearchRepository(private val apiService: ImmichApiService) {
    suspend fun searchSmart(
        query: String,
        page: Int = 1
    ): SearchResponse =
        apiService.searchSmart(query, page)

    suspend fun searchMetadata(
        query: String,
        page: Int = 1
    ): SearchResponse =
        apiService.searchMetadata(query, page)
}
