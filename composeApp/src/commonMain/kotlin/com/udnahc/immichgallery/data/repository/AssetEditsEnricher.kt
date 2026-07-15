package com.udnahc.immichgallery.data.repository

import com.udnahc.immichgallery.data.local.dao.AssetDao
import com.udnahc.immichgallery.data.model.AssetResponse
import com.udnahc.immichgallery.data.remote.ImmichApiService
import com.udnahc.immichgallery.domain.model.simulateEditDimensions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.lighthousegames.logging.logging

/**
 * Post-sync pass that corrects the stored aspectRatio for assets with
 * `isEdited = true`. Immich's main asset response carries the *original*
 * `width`/`height` — a non-destructive edit (crop/rotate) is stored in a
 * separate table and fetched via `GET /api/assets/{id}/edits`. The initial
 * aspect we stored at sync time (`Mappers.computeAspectRatio` with the
 * edited-priority branch that prefers thumbhash) is only a coarse
 * approximation; this enricher fetches the actual edit actions and simulates
 * them on the original dimensions to produce the exact post-edit aspect.
 *
 * Bounded parallelism avoids saturating the server on libraries with many
 * edited assets. Individual failures are swallowed; the row keeps its
 * approximate aspect and `editsResolved` stays false so the next sync retries.
 */
class AssetEditsEnricher(
    private val apiService: ImmichApiService,
    private val assetDao: AssetDao
) {
    private val log = logging("AssetEditsEnricher")

    suspend fun enrich(assets: List<AssetResponse>) {
        resolve(assets).forEach { (assetId, aspectRatio) ->
            assetDao.updateAspectRatioResolved(assetId, aspectRatio)
        }
    }

    /**
     * Resolves edited asset aspects without mutating Room. Detail repositories use
     * this before their writer transaction so relation replacement and Mosaic
     * invalidation describe the final persisted content atomically.
     */
    suspend fun resolve(assets: List<AssetResponse>): Map<String, Float> {
        val edited = assets.filter { it.isEdited && it.width != null && it.height != null }
        if (edited.isEmpty()) return emptyMap()
        log.d { "Enriching aspect for ${edited.size} edited assets" }
        val semaphore = Semaphore(EDIT_FETCH_PARALLELISM)
        return coroutineScope {
            edited.map { asset ->
                async(Dispatchers.IO) {
                    semaphore.withPermit { resolveAspect(asset) }
                }
            }.awaitAll()
                .mapNotNull { it }
                .associate { it.assetId to it.aspectRatio }
        }
    }

    private suspend fun resolveAspect(asset: AssetResponse): ResolvedEditAspect? {
        try {
            val editsResp = apiService.getAssetEdits(asset.id)
            val finalDims = simulateEditDimensions(
                asset.width, asset.height, editsResp.edits
            ) ?: return null
            val aspect = finalDims[0].toFloat() / finalDims[1].toFloat()
            return ResolvedEditAspect(asset.id, aspect)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "Failed to resolve edits for ${asset.id}" }
            return null
        }
    }

    private data class ResolvedEditAspect(
        val assetId: String,
        val aspectRatio: Float
    )

    companion object {
        private const val EDIT_FETCH_PARALLELISM = 8
    }
}
