package com.udnahc.immichgallery.data.repository

import com.udnahc.immichgallery.data.local.dao.AssetDao
import com.udnahc.immichgallery.data.model.AssetResponse
import com.udnahc.immichgallery.data.remote.ImmichApiService
import com.udnahc.immichgallery.domain.model.simulateEditDimensions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
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
    private val log = logging()

    suspend fun enrich(assets: List<AssetResponse>) {
        val edited = assets.filter { it.isEdited && it.width != null && it.height != null }
        if (edited.isEmpty()) return
        log.d { "Enriching aspect for ${edited.size} edited assets" }
        val semaphore = Semaphore(EDIT_FETCH_PARALLELISM)
        coroutineScope {
            edited.map { asset ->
                async(Dispatchers.IO) {
                    semaphore.withPermit { resolveAndPersist(asset) }
                }
            }.awaitAll()
        }
    }

    private suspend fun resolveAndPersist(asset: AssetResponse) {
        try {
            val editsResp = apiService.getAssetEdits(asset.id)
            val finalDims = simulateEditDimensions(
                asset.width, asset.height, editsResp.edits
            ) ?: return
            val aspect = finalDims[0].toFloat() / finalDims[1].toFloat()
            assetDao.updateAspectRatioResolved(asset.id, aspect)
        } catch (e: Exception) {
            log.w(e) { "Failed to resolve edits for ${asset.id}" }
        }
    }

    companion object {
        private const val EDIT_FETCH_PARALLELISM = 8
    }
}
