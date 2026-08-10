package com.udnahc.immichgallery.domain.usecase.search

import com.udnahc.immichgallery.data.model.SearchServerFeaturesResponse
import com.udnahc.immichgallery.data.model.SearchUserPreferencesResponse
import com.udnahc.immichgallery.data.repository.SearchRepository
import com.udnahc.immichgallery.domain.model.SearchCapabilities
import com.udnahc.immichgallery.domain.model.SearchPreferenceCapabilities
import com.udnahc.immichgallery.runSuspendCatching

class GetSearchCapabilitiesUseCase internal constructor(
    private val getServerFeatures: suspend () -> SearchServerFeaturesResponse,
    private val getUserPreferences: suspend () -> SearchUserPreferencesResponse
) {
    constructor(repository: SearchRepository) : this(
        getServerFeatures = repository::getSearchServerFeatures,
        getUserPreferences = repository::getSearchUserPreferences
    )

    suspend fun getServerCapabilities(): Result<SearchCapabilities> = runSuspendCatching {
        val response = getServerFeatures()
        SearchCapabilities(
            searchEnabled = response.search == true,
            contextEnabled = response.smartSearch == true,
            ocrEnabled = response.ocr == true
        )
    }

    suspend fun getPreferenceCapabilities(): Result<SearchPreferenceCapabilities> = runSuspendCatching {
        val response = getUserPreferences()
        SearchPreferenceCapabilities(
            tagsEnabled = response.tags?.enabled == true,
            ratingsEnabled = response.ratings?.enabled == true
        )
    }
}
