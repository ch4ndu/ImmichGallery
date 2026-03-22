package com.udnahc.immichgallery.data.repository

import com.udnahc.immichgallery.data.model.AssetResponse
import com.udnahc.immichgallery.data.model.PeopleResponse
import com.udnahc.immichgallery.data.remote.ImmichApiService

class PeopleRepository(
    private val apiService: ImmichApiService
) {
    suspend fun getPeople(): PeopleResponse =
        apiService.getPeople()

    suspend fun getPersonAssets(id: String): List<AssetResponse> {
        val allItems = mutableListOf<AssetResponse>()
        var page = 1
        while (true) {
            val response = apiService.getPersonAssets(id, page)
            allItems.addAll(response.assets.items)
            if (response.assets.nextPage == null) break
            page++
        }
        return allItems
    }

    fun personThumbnailUrl(personId: String): String =
        apiService.personThumbnailUrl(personId)
}
