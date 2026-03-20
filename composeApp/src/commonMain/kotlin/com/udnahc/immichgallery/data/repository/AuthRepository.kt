package com.udnahc.immichgallery.data.repository

import com.udnahc.immichgallery.data.model.UserResponse
import com.udnahc.immichgallery.data.remote.ImmichApiService

class AuthRepository(private val apiService: ImmichApiService) {
    suspend fun validateConnection(serverUrl: String, apiKey: String): UserResponse {
        return apiService.validateConnection(serverUrl, apiKey)
    }
}
