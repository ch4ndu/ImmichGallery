package com.udnahc.immichgallery.data.repository

import androidx.paging.PagingSource
import com.udnahc.immichgallery.data.local.dao.PersonAssetDao
import com.udnahc.immichgallery.data.local.entity.PersonAssetEntity
import com.udnahc.immichgallery.data.model.AssetResponse
import com.udnahc.immichgallery.data.model.PeopleResponse
import com.udnahc.immichgallery.data.remote.ImmichApiService
import com.udnahc.immichgallery.domain.model.toPersonEntity

class PeopleRepository(
    private val apiService: ImmichApiService,
    private val dao: PersonAssetDao
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
        dao.replacePerson(id, allItems.mapIndexed { i, a -> a.toPersonEntity(id, i) })
        return allItems
    }

    fun getPersonAssetsPaging(personId: String): PagingSource<Int, PersonAssetEntity> =
        dao.getAssetsPaging(personId)

    suspend fun getAssetPosition(
        personId: String,
        sortOrder: Int
    ): Int =
        dao.getAssetPosition(personId, sortOrder)

    fun personThumbnailUrl(personId: String): String =
        apiService.personThumbnailUrl(personId)
}
