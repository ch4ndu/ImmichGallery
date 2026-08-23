package com.udnahc.immichgallery.data.repository

import com.udnahc.immichgallery.data.local.dao.AssetDao
import com.udnahc.immichgallery.data.local.entity.AssetEntity
import com.udnahc.immichgallery.data.model.AssetResponse
import com.udnahc.immichgallery.data.model.ImmichPaginationCompatibilityException
import com.udnahc.immichgallery.domain.model.toAssetEntityOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

internal data class DetailRawAssetResponse(
    val response: AssetResponse,
    val sortOrder: Int
)

internal data class DetailRawAssetRef(
    val assetId: String,
    val sortOrder: Int
)

internal data class DetailRawSortRange(
    val startSortOrder: Int,
    val endSortOrder: Int
)

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

internal fun supportedDetailRawAssetResponses(
    responses: List<AssetResponse>,
    baseRawOffset: Int
): List<DetailRawAssetResponse> =
    responses.mapIndexedNotNull { rawIndex, response ->
        if (response.toAssetEntityOrNull() == null) {
            null
        } else {
            DetailRawAssetResponse(response, checkedDetailRawOffset(baseRawOffset, rawIndex))
        }
    }

internal fun detailRawPageWindow(page: Int, size: Int): DetailRawSortRange {
    if (page <= 0 || size <= 0) {
        detailPaginationCompatibilityFailure()
    }
    val start = (page.toLong() - 1L) * size.toLong()
    val end = start + size.toLong()
    return DetailRawSortRange(
        startSortOrder = checkedDetailRawSortOrder(start),
        endSortOrder = checkedDetailRawSortOrder(end)
    )
}

internal fun detailRawBoundaryAfter(
    startSortOrder: Int,
    rawCount: Int
): Int {
    if (rawCount < 0) {
        detailPaginationCompatibilityFailure()
    }
    return checkedDetailRawSortOrder(startSortOrder.toLong() + rawCount.toLong())
}

internal fun detailSkippedForwardRawRange(
    page: Int,
    nextPage: Int?,
    size: Int
): DetailRawSortRange? {
    if (nextPage == null || nextPage.toLong() <= page.toLong() + 1L) return null
    val currentWindow = detailRawPageWindow(page, size)
    val nextWindow = detailRawPageWindow(nextPage, size)
    return DetailRawSortRange(
        startSortOrder = currentWindow.endSortOrder,
        endSortOrder = nextWindow.startSortOrder
    ).takeIf { it.startSortOrder < it.endSortOrder }
}

internal fun detailRawAssetRefs(items: List<DetailRawAssetResponse>): List<DetailRawAssetRef> =
    items.map { item -> DetailRawAssetRef(item.response.id, item.sortOrder) }

internal fun detailRawWindowChanged(
    existingWindowRefs: List<DetailRawAssetRef>,
    fetchedRefs: List<DetailRawAssetRef>,
    tailRefsAfterWindow: List<DetailRawAssetRef>,
    hasMore: Boolean
): Boolean {
    if (existingWindowRefs != fetchedRefs) return true
    return !hasMore && tailRefsAfterWindow.isNotEmpty()
}

internal fun detailRawPageWriteUnchanged(
    existingWindowRefs: List<DetailRawAssetRef>,
    fetchedRefs: List<DetailRawAssetRef>,
    tailRefsAfterWindow: List<DetailRawAssetRef>,
    existingAssetsById: Map<String, AssetEntity>,
    candidates: List<AssetEntity>,
    hasMore: Boolean
): Boolean {
    if (detailRawWindowChanged(existingWindowRefs, fetchedRefs, tailRefsAfterWindow, hasMore)) {
        return false
    }
    val existingCandidates = candidates.map { candidate ->
        existingAssetsById[candidate.id] ?: return false
    }
    return !orderedAssetsChanged(existingCandidates, candidates)
}

private fun checkedDetailRawOffset(
    baseRawOffset: Int,
    rawIndex: Int
): Int {
    if (baseRawOffset < 0 || rawIndex < 0) {
        detailPaginationCompatibilityFailure()
    }
    return checkedDetailRawSortOrder(baseRawOffset.toLong() + rawIndex.toLong())
}

private fun checkedDetailRawSortOrder(value: Long): Int {
    if (value < 0L || value > Int.MAX_VALUE.toLong()) {
        detailPaginationCompatibilityFailure()
    }
    return value.toInt()
}

private fun detailPaginationCompatibilityFailure(): Nothing =
    throw ImmichPaginationCompatibilityException("Invalid Immich detail pagination response")

internal fun detailRawFullWriteUnchanged(
    existingRefs: List<DetailRawAssetRef>,
    fetchedRefs: List<DetailRawAssetRef>,
    existingAssetsById: Map<String, AssetEntity>,
    candidates: List<AssetEntity>
): Boolean {
    if (existingRefs != fetchedRefs) return false
    val existingCandidates = candidates.map { candidate ->
        existingAssetsById[candidate.id] ?: return false
    }
    return !orderedAssetsChanged(existingCandidates, candidates)
}
