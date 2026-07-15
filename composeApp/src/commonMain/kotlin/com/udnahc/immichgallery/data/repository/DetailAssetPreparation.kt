package com.udnahc.immichgallery.data.repository

import com.udnahc.immichgallery.data.local.dao.AssetDao
import com.udnahc.immichgallery.data.local.entity.AssetEntity
import com.udnahc.immichgallery.data.model.AssetResponse
import com.udnahc.immichgallery.domain.model.toAssetEntityOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/**
 * Produces the final asset rows for an Album or Person sync before Room writes.
 * Reusing a known exact aspect prevents an unchanged owner refresh from
 * resetting [AssetEntity.editsResolved] and fetching `/edits` again.
 */
internal suspend fun prepareDetailAssetEntities(
    responses: List<AssetResponse>,
    assetDao: AssetDao,
    editsEnricher: AssetEditsEnricher
): List<AssetEntity> {
    val candidates = responses.mapNotNull { response ->
        response.toAssetEntityOrNull()?.let { response to it }
    }
    if (candidates.isEmpty()) return emptyList()

    val cachedById = withContext(Dispatchers.IO) {
        assetDao.getAssetsByIds(candidates.map { (_, entity) -> entity.id })
            .associateBy { it.id }
    }
    val aspectsById = editsEnricher.resolve(
        candidates
            .filterNot { (_, candidate) -> canReuseResolvedEdit(candidate, cachedById[candidate.id]) }
            .map { (response) -> response }
    )
    return candidates.map { (_, candidate) ->
        val cached = cachedById[candidate.id]
        val reusableAspect = cached
            ?.takeIf { canReuseResolvedEdit(candidate, it) }
            ?.aspectRatio
        when {
            reusableAspect != null -> candidate.copy(
                aspectRatio = reusableAspect,
                editsResolved = true
            )

            aspectsById[candidate.id] != null -> candidate.copy(
                aspectRatio = aspectsById.getValue(candidate.id),
                editsResolved = true
            )

            else -> candidate
        }
    }
}

private fun canReuseResolvedEdit(
    candidate: AssetEntity,
    cached: AssetEntity?
): Boolean =
    candidate.isEdited &&
        cached?.isEdited == true &&
        cached.editsResolved &&
        candidate.id == cached.id &&
        candidate.type == cached.type &&
        candidate.fileName == cached.fileName &&
        candidate.createdAt == cached.createdAt &&
        candidate.isFavorite == cached.isFavorite &&
        candidate.stackCount == cached.stackCount

internal fun detailPageWriteUnchanged(
    before: List<AssetEntity>,
    candidates: List<AssetEntity>,
    baseOffset: Int,
    pageChanged: Boolean,
    hasMore: Boolean
): Boolean {
    if (pageChanged || !hasMore) return false
    val existingPage = before.drop(baseOffset).take(candidates.size)
    return existingPage.size == candidates.size && !orderedAssetsChanged(existingPage, candidates)
}
