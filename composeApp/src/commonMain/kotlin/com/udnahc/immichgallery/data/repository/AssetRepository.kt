package com.udnahc.immichgallery.data.repository

import com.udnahc.immichgallery.data.local.dao.AssetDao
import com.udnahc.immichgallery.data.local.dao.SyncMetadataDao
import com.udnahc.immichgallery.data.remote.ImmichApiService
import com.udnahc.immichgallery.domain.model.AssetDetail
import com.udnahc.immichgallery.domain.model.AssetStackAccessException
import com.udnahc.immichgallery.domain.model.AssetStackLookup
import com.udnahc.immichgallery.domain.model.toDetail
import com.udnahc.immichgallery.domain.model.toDomain
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ResponseException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

class AssetRepository(
    private val apiService: ImmichApiService,
    private val assetDao: AssetDao,
    private val syncMetadataDao: SyncMetadataDao,
    private val serverConfigRepository: ServerConfigRepository,
    private val sessionStateRepository: SessionStateRepository
) {
    suspend fun getAssetDetail(assetId: String): AssetDetail =
        sessionStateRepository.withOperation {
            val baseUrl = serverConfigRepository.currentSession().serverUrl.trimEnd('/')
            val response = apiService.getAssetInfo(assetId)
            response.toDetail(baseUrl)
        }

    suspend fun getAssetStack(assetId: String): AssetStackLookup =
        sessionStateRepository.withOperation {
            val baseUrl = serverConfigRepository.currentSession().serverUrl.trimEnd('/')
            val asset = try {
                apiService.getAssetInfo(assetId)
            } catch (error: Throwable) {
                throw error.asStackAccessException()
            }
            val stackId = asset.stack?.id ?: return@withOperation AssetStackLookup.NoStack
            val stack = try {
                apiService.getStack(stackId).toDomain(baseUrl)
            } catch (error: Throwable) {
                throw error.asStackAccessException()
            }
            if (stack.assets.isEmpty()) {
                AssetStackLookup.NoSupportedMembers
            } else {
                AssetStackLookup.Available(stack)
            }
        }

    internal suspend fun clearCacheForSessionMutation() {
        withContext(Dispatchers.IO) {
            assetDao.clearAll()
            syncMetadataDao.clearAll()
        }
    }
}

private fun Throwable.asStackAccessException(): AssetStackAccessException {
    if (this is CancellationException) throw this
    return when (this) {
        is AssetStackAccessException -> this
        is ClientRequestException -> when (response.status) {
            HttpStatusCode.Unauthorized,
            HttpStatusCode.Forbidden -> AssetStackAccessException.PermissionDenied(this)
            HttpStatusCode.NotFound,
            HttpStatusCode.MethodNotAllowed,
            HttpStatusCode.NotImplemented -> AssetStackAccessException.Unavailable(this)
            else -> AssetStackAccessException.Transient(this)
        }
        is ResponseException -> AssetStackAccessException.Transient(this)
        else -> AssetStackAccessException.Transient(this)
    }
}
