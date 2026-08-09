package com.udnahc.immichgallery.data.repository

import com.udnahc.immichgallery.data.model.MemoryResponse
import com.udnahc.immichgallery.data.remote.ImmichApiService
import com.udnahc.immichgallery.domain.model.MemoryAccessException
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ResponseException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.LocalDate

class MemoryRepository(
    private val apiService: ImmichApiService
) {
    suspend fun getMemories(forDate: LocalDate): List<MemoryResponse> = try {
        apiService.getMemories(forDate)
    } catch (error: Throwable) {
        throw error.asMemoryAccessException()
    }
}

private fun Throwable.asMemoryAccessException(): MemoryAccessException {
    if (this is CancellationException) throw this
    return when (this) {
        is MemoryAccessException -> this
        is ClientRequestException -> when (response.status) {
            HttpStatusCode.Unauthorized,
            HttpStatusCode.Forbidden -> MemoryAccessException.PermissionDenied(this)
            HttpStatusCode.NotFound,
            HttpStatusCode.MethodNotAllowed,
            HttpStatusCode.NotImplemented -> MemoryAccessException.Unavailable(this)
            else -> MemoryAccessException.Transient(this)
        }
        is ResponseException -> MemoryAccessException.Transient(this)
        else -> MemoryAccessException.Transient(this)
    }
}
