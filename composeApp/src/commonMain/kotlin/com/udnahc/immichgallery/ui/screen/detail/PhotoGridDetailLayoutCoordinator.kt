package com.udnahc.immichgallery.ui.screen.detail

import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.DetailMosaicCacheEntry
import com.udnahc.immichgallery.domain.model.DetailMosaicCacheLookup
import com.udnahc.immichgallery.domain.model.DetailMosaicCacheOwnerType
import com.udnahc.immichgallery.domain.model.GRID_SPACING_DP
import com.udnahc.immichgallery.domain.model.GroupSize
import com.udnahc.immichgallery.domain.model.HeaderItem
import com.udnahc.immichgallery.domain.model.MOSAIC_FALLBACK_MIN_COMPLETE_ROW_PHOTOS
import com.udnahc.immichgallery.domain.model.MOSAIC_FALLBACK_PROMOTE_WIDE_IMAGES
import com.udnahc.immichgallery.domain.model.PhotoGridDisplayItem
import com.udnahc.immichgallery.domain.model.RowHeightBounds
import com.udnahc.immichgallery.domain.model.RowHeightScope
import com.udnahc.immichgallery.domain.model.ViewConfig
import com.udnahc.immichgallery.domain.model.buildMosaicAssignments
import com.udnahc.immichgallery.domain.model.buildPhotoGridItemsWithMosaic
import com.udnahc.immichgallery.domain.model.buildPhotoGridPlaceholderItems
import com.udnahc.immichgallery.domain.model.buildPhotoGridPlaceholderItemsForHeight
import com.udnahc.immichgallery.domain.model.defaultTargetRowHeightForWidth
import com.udnahc.immichgallery.domain.model.estimatePhotoGridDisplayItemsHeight
import com.udnahc.immichgallery.domain.model.groupAssets
import com.udnahc.immichgallery.domain.model.mosaicFallbackRowHeight
import com.udnahc.immichgallery.domain.model.mosaicLayoutSpecForColumnCount
import com.udnahc.immichgallery.domain.model.mosaicDisplayCacheDimensionKey
import com.udnahc.immichgallery.domain.model.mosaicDisplayCacheFamiliesKey
import com.udnahc.immichgallery.domain.model.mosaicDisplayCacheWidthKey
import com.udnahc.immichgallery.domain.model.packIntoRows
import com.udnahc.immichgallery.domain.model.rowHeightBoundsForViewport
import com.udnahc.immichgallery.ui.util.MosaicWorkCancelledException
import com.udnahc.immichgallery.ui.util.MosaicWorkPriority
import com.udnahc.immichgallery.ui.util.MosaicWorkScheduler
import com.udnahc.immichgallery.ui.util.PhotoGridLayoutRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lighthousegames.logging.logging

data class PhotoGridDetailLayoutSnapshot(
    val assets: List<Asset>,
    val availableWidth: Float,
    val targetRowHeight: Float,
    val rowHeightBounds: RowHeightBounds,
    val groupSize: GroupSize,
    val viewConfig: ViewConfig,
    val displayItems: List<PhotoGridDisplayItem>
)

class PhotoGridDetailLayoutCoordinator<S>(
    private val scope: CoroutineScope,
    private var hasSavedTargetRowHeight: Boolean,
    private var savedTargetRowHeight: Float,
    private val targetRowHeightScope: RowHeightScope,
    private val ownerKey: String,
    private val logTag: String,
    private val mosaicWorkScheduler: MosaicWorkScheduler,
    private val getState: () -> S,
    private val updateState: ((S) -> S) -> Unit,
    private val snapshotOf: (S) -> PhotoGridDetailLayoutSnapshot,
    private val withAvailableWidth: (S, Float, Float) -> S,
    private val withViewportHeight: (S, Float, RowHeightBounds) -> S,
    private val withTargetRowHeight: (S, Float) -> S,
    private val withGroupSize: (S, GroupSize) -> S,
    private val withViewConfig: (S, ViewConfig) -> S,
    private val withDisplayItems: (S, List<PhotoGridDisplayItem>) -> S,
    private val persistTargetRowHeight: suspend (RowHeightScope, Float) -> Unit,
    private val persistViewConfig: (ViewConfig) -> Unit,
    private val cacheOwnerType: DetailMosaicCacheOwnerType? = null,
    private val cacheOwnerId: String = "",
    private val isPersistentCacheComplete: () -> Boolean = { true },
    private val readPersistentCache: suspend (DetailMosaicCacheLookup) -> List<DetailMosaicCacheEntry> = { emptyList() },
    private val upsertPersistentCache: suspend (DetailMosaicCacheEntry) -> Unit = {},
    private val layoutRunner: PhotoGridLayoutRunner = PhotoGridLayoutRunner(scope),
    private val rowHeightPersistenceRunner: PhotoGridLayoutRunner = PhotoGridLayoutRunner(scope)
) {
    private val log = logging(logTag)
    private var availableViewportHeight = 0f
    private var mosaicLayoutGeneration = 0L
    private var assetRevision = 0
    private var pendingSyncedContentRevision = false
    private var renderedGroupFingerprints: List<DetailGroupFingerprint>? = null
    private var displayCache: CachedDisplayItems? = null
    private val groupDisplayCache = mutableMapOf<DetailGroupDisplayCacheKey, List<PhotoGridDisplayItem>>()
    private var visibleBucketIndexes: List<Int> = listOf(0)
    private var isScrollInProgress = false
    private val deferredMosaicGroupItems = mutableMapOf<Int, List<PhotoGridDisplayItem>>()
    private var deferredMosaicFlushJob: Job? = null
    private val persistentCacheRenderer = CachedPhotoGridMosaicRenderer()
    private val runtimeRenderer = RuntimePhotoGridMosaicRenderer()

    fun activateForegroundMosaic() {
        scope.launch {
            mosaicWorkScheduler.setActiveForegroundOwner(ownerKey)
        }
    }

    fun deactivateForegroundMosaic() {
        scope.launch {
            mosaicWorkScheduler.clearActiveForegroundOwner(ownerKey)
            mosaicWorkScheduler.cancelOwner(ownerKey)
        }
    }

    fun onCleared() {
        mosaicWorkScheduler.clearActiveForegroundOwnerAsync(ownerKey)
        mosaicWorkScheduler.cancelOwnerAsync(ownerKey)
    }

    fun setAvailableWidth(widthDp: Float) {
        val current = snapshotOf(getState())
        if (widthDp == current.availableWidth) return
        clearLayoutCaches()
        updateState { state ->
            val snapshot = snapshotOf(state)
            withAvailableWidth(
                state,
                widthDp,
                targetRowHeightForConfig(widthDp, snapshot.rowHeightBounds)
            )
        }
        scheduleDisplayItems()
    }

    fun setAvailableViewportHeight(heightDp: Float) {
        if (heightDp == availableViewportHeight) return
        clearLayoutCaches()
        availableViewportHeight = heightDp
        val bounds = rowHeightBoundsForViewport(heightDp)
        updateState { state ->
            val snapshot = snapshotOf(state)
            withViewportHeight(
                state,
                targetRowHeightForConfig(snapshot.availableWidth, bounds),
                bounds
            )
        }
        scheduleDisplayItems()
    }

    fun setTargetRowHeight(height: Float) {
        val snapshot = snapshotOf(getState())
        val clamped = snapshot.rowHeightBounds.clamp(height)
        if (clamped == snapshot.targetRowHeight) return
        clearLayoutCaches()
        hasSavedTargetRowHeight = true
        savedTargetRowHeight = clamped
        rowHeightPersistenceRunner.launch(debounce = true) {
            persistTargetRowHeight(targetRowHeightScope, clamped)
        }
        updateState { state -> withTargetRowHeight(state, clamped) }
        scheduleDisplayItems(debounce = true)
    }

    fun setGroupSize(size: GroupSize) {
        val snapshot = snapshotOf(getState())
        if (size == snapshot.groupSize) return
        clearLayoutCaches()
        updateState { state -> withGroupSize(state, size) }
        val nextSnapshot = snapshotOf(getState())
        if (!pendingSyncedContentRevision && nextSnapshot.assets.isNotEmpty()) {
            renderedGroupFingerprints = nextSnapshot.assets.detailGroupFingerprints(size)
        }
        scheduleDisplayItems()
    }

    fun setViewConfig(config: ViewConfig) {
        val normalized = config.normalized
        if (normalized == snapshotOf(getState()).viewConfig) return
        clearLayoutCaches()
        persistViewConfig(normalized)
        updateState { state -> withViewConfig(state, normalized) }
        scheduleDisplayItems()
    }

    suspend fun prepareMosaicViewConfig(config: ViewConfig): Result<Unit> {
        val normalized = config.normalized
        if (!normalized.cacheMosaicResults || !normalized.mosaicEnabled) return Result.success(Unit)
        val snapshot = snapshotOf(getState())
        if (snapshot.assets.isEmpty()) return Result.success(Unit)
        if (!isPersistentCacheComplete()) {
            return Result.failure(IllegalStateException("Detail Mosaic cache requires a complete owner snapshot"))
        }
        val layoutSpec = mosaicLayoutSpecForColumnCount(snapshot.availableWidth, normalized.mosaicColumnCount)
            ?: return Result.failure(IllegalStateException("Detail Mosaic metrics are not ready"))
        val lookup = persistentCacheLookup(snapshot.copy(viewConfig = normalized), layoutSpec.cellHeight)
            ?: return Result.failure(IllegalStateException("Detail Mosaic cache is unavailable"))
        return runCatching {
            val existing = readPersistentCache(lookup).associateBy {
                DetailPersistentGroupKey(it.sectionIndex, it.sectionKey, it.assetFingerprint)
            }
            val groups = groupAssets(snapshot.assets, snapshot.groupSize)
            groups.forEachIndexed { index, assetGroup ->
                val group = IndexedAssetGroup(index, assetGroup.label, assetGroup.assets)
                val fingerprint = group.assets.detailPersistentFingerprint()
                val persistentKey = DetailPersistentGroupKey(group.index, group.label, fingerprint)
                if (existing[persistentKey] != null) return@forEachIndexed
                val groupItems = withContext(Dispatchers.Default) {
                    val assignments = buildMosaicAssignments(
                        assets = group.assets,
                        layoutSpec = layoutSpec,
                        spacing = GRID_SPACING_DP,
                        enabledFamilies = normalized.mosaicFamilies
                    )
                    groupHeader(group) + buildPhotoGridItemsWithMosaic(
                        assets = group.assets,
                        assignments = assignments,
                        bucketIndex = group.index,
                        sectionLabel = group.label,
                        layoutSpec = layoutSpec,
                        spacing = GRID_SPACING_DP,
                        maxRowHeight = snapshot.rowHeightBounds.max,
                        promoteWideImages = MOSAIC_FALLBACK_PROMOTE_WIDE_IMAGES,
                        minCompleteRowPhotos = MOSAIC_FALLBACK_MIN_COMPLETE_ROW_PHOTOS
                    )
                }
                runtimeRenderer.groupCacheEntry(
                    group = group,
                    groupItems = groupItems,
                    assetFingerprint = fingerprint,
                    lookup = lookup
                )?.let { upsertPersistentCache(it) }
            }
        }
    }

    fun onViewConfigObserved(config: ViewConfig) {
        val normalized = config.normalized
        if (normalized == snapshotOf(getState()).viewConfig) return
        clearLayoutCaches()
        updateState { state -> withViewConfig(state, normalized) }
        scheduleDisplayItems()
    }

    fun setVisibleBucketIndexes(indexes: List<Int>) {
        val next = indexes.distinct().ifEmpty { listOf(0) }
        if (next == visibleBucketIndexes) return
        visibleBucketIndexes = next
        if (snapshotOf(getState()).viewConfig.mosaicEnabled) {
            flushDeferredMosaicGroups(visibleWindowOnly = true)
            scope.launch {
                mosaicWorkScheduler.reprioritizePending(
                    ownerKey = ownerKey,
                    visibleRequestKeys = next.map(::mosaicGroupRequestKey).toSet()
                )
            }
        }
    }

    fun setScrollInProgress(inProgress: Boolean) {
        if (inProgress == isScrollInProgress) return
        isScrollInProgress = inProgress
        if (inProgress) {
            deferredMosaicFlushJob?.cancel()
            deferredMosaicFlushJob = null
        } else {
            scheduleDeferredMosaicFlush()
        }
    }

    fun handleAssetEmission(assets: List<Asset>) {
        val fingerprint = assets.detailGroupFingerprints(snapshotOf(getState()).groupSize)
        when {
            renderedGroupFingerprints == null && assets.isNotEmpty() -> {
                bumpAssetRevision(fingerprint)
                scheduleDisplayItems()
            }
            pendingSyncedContentRevision && fingerprint != renderedGroupFingerprints -> {
                bumpAssetRevision(fingerprint)
                scheduleDisplayItems()
            }
        }
    }

    fun handleSyncedContentChange(changed: Boolean) {
        if (!changed) return
        val snapshot = snapshotOf(getState())
        val fingerprint = snapshot.assets.detailGroupFingerprints(snapshot.groupSize)
        if (fingerprint != renderedGroupFingerprints &&
            (fingerprint.isNotEmpty() || renderedGroupFingerprints != null)
        ) {
            bumpAssetRevision(fingerprint)
            scheduleDisplayItems()
        } else {
            pendingSyncedContentRevision = true
        }
    }

    private fun targetRowHeightForConfig(availableWidth: Float, bounds: RowHeightBounds): Float {
        val preferred = if (hasSavedTargetRowHeight) {
            savedTargetRowHeight
        } else {
            defaultTargetRowHeightForWidth(availableWidth)
        }
        return bounds.clamp(preferred)
    }

    private fun scheduleDisplayItems(debounce: Boolean = false) {
        val mosaicGeneration = ++mosaicLayoutGeneration
        val revision = assetRevision
        layoutRunner.launch(debounce = debounce) { generation ->
            val snapshot = snapshotOf(getState())
            val cacheKey = snapshot.displayCacheKey(revision)
            displayCache?.takeIf { it.key == cacheKey }?.let { cached ->
                if (layoutRunner.isCurrent(generation) && revision == assetRevision) {
                    updateState { state -> withDisplayItems(state, cached.items) }
                }
                return@launch
            }
            val layoutSpec = mosaicLayoutSpecForSnapshot(snapshot)
            if (snapshot.viewConfig.mosaicEnabled &&
                layoutSpec != null &&
                snapshot.assets.isNotEmpty() &&
                snapshot.availableWidth > 0f
            ) {
                buildMosaicDisplayItems(snapshot, generation, mosaicGeneration, revision, cacheKey)
            } else {
                val items = buildDisplayItems(snapshot)
                if (layoutRunner.isCurrent(generation) && revision == assetRevision) {
                    displayCache = CachedDisplayItems(cacheKey, items)
                    updateState { state -> withDisplayItems(state, items) }
                }
            }
        }
    }

    private suspend fun buildMosaicDisplayItems(
        snapshot: PhotoGridDetailLayoutSnapshot,
        runnerGeneration: Long,
        mosaicGeneration: Long,
        revision: Int,
        cacheKey: DetailDisplayCacheKey
    ) {
        val layoutSpec = mosaicLayoutSpecForSnapshot(snapshot) ?: return
        val persistentCacheLookup = persistentCacheLookup(snapshot, layoutSpec.cellHeight)
        val persistentCacheEntries = if (persistentCacheLookup != null) {
            runCatching { readPersistentCache(persistentCacheLookup) }
                .onFailure { e -> log.e(e) { "Failed to read Mosaic cache for $logTag owner=$cacheOwnerId" } }
                .getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val persistentCacheByGroup = persistentCacheEntries.associateBy {
            DetailPersistentGroupKey(it.sectionIndex, it.sectionKey, it.assetFingerprint)
        }
        val keyedGroups = groupAssets(snapshot.assets, snapshot.groupSize).mapIndexed { index, group ->
            val indexedGroup = IndexedAssetGroup(index, group.label, group.assets)
            val assetFingerprint = group.assets.detailPersistentFingerprint()
            KeyedAssetGroup(
                group = indexedGroup,
                assetFingerprint = assetFingerprint,
                cacheKey = snapshot.groupDisplayCacheKey(
                    bucketIndex = indexedGroup.index,
                    sectionLabel = indexedGroup.label,
                    assets = indexedGroup.assets,
                    mosaicCellHeight = layoutSpec.cellHeight
                )
            )
        }
        val initialItems = keyedGroups.flatMap { keyed ->
            groupDisplayCache[keyed.cacheKey]
                ?: persistentCacheRenderer.cachedGroupItems(
                    group = keyed.group,
                    assetFingerprint = keyed.assetFingerprint,
                    cachedEntries = persistentCacheByGroup
                )?.also { items -> groupDisplayCache[keyed.cacheKey] = items }
                ?: placeholderItemsForGroup(
                bucketIndex = keyed.group.index,
                sectionLabel = keyed.group.label,
                assets = keyed.group.assets,
                availableWidth = snapshot.availableWidth,
                targetRowHeight = layoutSpec.cellHeight,
                cachedPlaceholderHeight = persistentCacheByGroup[
                    DetailPersistentGroupKey(keyed.group.index, keyed.group.label, keyed.assetFingerprint)
                ]?.placeholderHeight
            )
        }
        if (layoutRunner.isCurrent(runnerGeneration) &&
            mosaicGeneration == mosaicLayoutGeneration &&
            revision == assetRevision
        ) {
            updateState { state -> withDisplayItems(state, initialItems) }
        }
        val remainingGroups = keyedGroups
            .filter { keyed -> groupDisplayCache[keyed.cacheKey] == null }
            .toMutableList()
        while (remainingGroups.isNotEmpty()) {
            val keyedGroup = nextMosaicGroup(remainingGroups)
            remainingGroups.remove(keyedGroup)
            val group = keyedGroup.group
            val priority = if (group.index in visibleBucketIndexes) {
                MosaicWorkPriority.ForegroundVisible
            } else {
                MosaicWorkPriority.ForegroundPrefetch
            }
            val groupItems = try {
                val assignments = mosaicWorkScheduler.run(
                    ownerKey = ownerKey,
                    requestKey = mosaicGroupRequestKey(group.index),
                    generation = mosaicGeneration,
                    priority = priority
                ) { token ->
                    buildMosaicAssignments(
                        assets = group.assets,
                        layoutSpec = layoutSpec,
                        spacing = GRID_SPACING_DP,
                        enabledFamilies = snapshot.viewConfig.mosaicFamilies,
                        shouldContinue = { token.ensureActive() }
                    )
                }
                logMosaicAssignmentStats(group.label, group.assets.size, assignments.sumOf { it.sourceCount }, assignments.size)
                groupHeader(group) + buildPhotoGridItemsWithMosaic(
                    assets = group.assets,
                    assignments = assignments,
                    bucketIndex = group.index,
                    sectionLabel = group.label,
                    layoutSpec = layoutSpec,
                    spacing = GRID_SPACING_DP,
                    maxRowHeight = snapshot.rowHeightBounds.max,
                    promoteWideImages = MOSAIC_FALLBACK_PROMOTE_WIDE_IMAGES,
                    minCompleteRowPhotos = MOSAIC_FALLBACK_MIN_COMPLETE_ROW_PHOTOS
                )
            } catch (e: MosaicWorkCancelledException) {
                if (!layoutRunner.isCurrent(runnerGeneration) ||
                    mosaicGeneration != mosaicLayoutGeneration ||
                    revision != assetRevision
                ) return
                remainingGroups.add(keyedGroup)
                continue
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.e(e) { "Mosaic assignments failed for $logTag owner=$ownerKey section=${group.label}" }
                groupHeader(group) + packIntoRows(
                    group.assets,
                    bucketIndex = group.index,
                    sectionLabel = group.label,
                    availableWidth = snapshot.availableWidth,
                    targetRowHeight = mosaicFallbackRowHeight(
                        layoutSpec = layoutSpec,
                        assetCount = group.assets.size,
                        maxRowHeight = snapshot.rowHeightBounds.max
                    ),
                    spacing = GRID_SPACING_DP,
                    maxRowHeight = snapshot.rowHeightBounds.max,
                    promoteWideImages = MOSAIC_FALLBACK_PROMOTE_WIDE_IMAGES,
                    minCompleteRowPhotos = MOSAIC_FALLBACK_MIN_COMPLETE_ROW_PHOTOS
                )
            }
            if (!layoutRunner.isCurrent(runnerGeneration) ||
                mosaicGeneration != mosaicLayoutGeneration ||
                revision != assetRevision
            ) return
            groupDisplayCache[keyedGroup.cacheKey] = groupItems
            if (shouldPublishMosaicGroupImmediately(group.index)) {
                deferredMosaicGroupItems.remove(group.index)
                publishMosaicGroupItems(mapOf(group.index to groupItems))
            } else {
                deferredMosaicGroupItems[group.index] = groupItems
                scheduleDeferredMosaicFlush()
            }
            persistentCacheLookup?.let { lookup ->
                runtimeRenderer.writeGroupCacheAsync(
                    scope = scope,
                    group = group,
                    groupItems = groupItems,
                    assetFingerprint = keyedGroup.assetFingerprint,
                    lookup = lookup,
                    upsert = upsertPersistentCache,
                    onFailure = { e ->
                        log.e(e) { "Failed to write Mosaic cache for $logTag owner=$cacheOwnerId section=${group.label}" }
                    }
                )
            }
        }
        if (layoutRunner.isCurrent(runnerGeneration) &&
            mosaicGeneration == mosaicLayoutGeneration &&
            revision == assetRevision
        ) {
            displayCache = CachedDisplayItems(cacheKey, snapshotOf(getState()).displayItems)
        }
    }

    private fun buildDisplayItems(snapshot: PhotoGridDetailLayoutSnapshot): List<PhotoGridDisplayItem> {
        if (snapshot.assets.isEmpty() || snapshot.availableWidth <= 0f) return emptyList()
        val groups = groupAssets(snapshot.assets, snapshot.groupSize)
        val items = mutableListOf<PhotoGridDisplayItem>()
        val mosaicLayoutSpec = mosaicLayoutSpecForSnapshot(snapshot)
        for ((index, group) in groups.withIndex()) {
            if (group.label.isNotEmpty()) {
                items.add(HeaderItem(
                    gridKey = "h_${index}_${group.label}",
                    bucketIndex = index,
                    sectionLabel = group.label,
                    label = group.label
                ))
            }
            val layoutSpec = mosaicLayoutSpec
            if (snapshot.viewConfig.mosaicEnabled && layoutSpec != null) {
                val assignments = buildMosaicAssignments(
                    assets = group.assets,
                    layoutSpec = layoutSpec,
                    spacing = GRID_SPACING_DP,
                    enabledFamilies = snapshot.viewConfig.mosaicFamilies
                )
                logMosaicAssignmentStats(group.label, group.assets.size, assignments.sumOf { it.sourceCount }, assignments.size)
                items.addAll(
                    buildPhotoGridItemsWithMosaic(
                        assets = group.assets,
                        assignments = assignments,
                        bucketIndex = index,
                        sectionLabel = group.label,
                        layoutSpec = layoutSpec,
                        spacing = GRID_SPACING_DP,
                        maxRowHeight = snapshot.rowHeightBounds.max,
                        promoteWideImages = MOSAIC_FALLBACK_PROMOTE_WIDE_IMAGES,
                        minCompleteRowPhotos = MOSAIC_FALLBACK_MIN_COMPLETE_ROW_PHOTOS
                    )
                )
            } else {
                items.addAll(
                    packIntoRows(
                        group.assets,
                        bucketIndex = index,
                        sectionLabel = group.label,
                        availableWidth = snapshot.availableWidth,
                        targetRowHeight = if (snapshot.viewConfig.mosaicEnabled && layoutSpec != null) {
                            mosaicFallbackRowHeight(
                                layoutSpec = layoutSpec,
                                assetCount = group.assets.size,
                                maxRowHeight = snapshot.rowHeightBounds.max
                            )
                        } else {
                            snapshot.targetRowHeight
                        },
                        spacing = GRID_SPACING_DP,
                        maxRowHeight = snapshot.rowHeightBounds.max,
                        promoteWideImages = if (snapshot.viewConfig.mosaicEnabled) {
                            MOSAIC_FALLBACK_PROMOTE_WIDE_IMAGES
                        } else {
                            true
                        },
                        minCompleteRowPhotos = if (snapshot.viewConfig.mosaicEnabled) {
                            MOSAIC_FALLBACK_MIN_COMPLETE_ROW_PHOTOS
                        } else {
                            1
                        }
                    )
                )
            }
        }
        return items
    }

    private fun logMosaicAssignmentStats(
        sectionLabel: String,
        assetCount: Int,
        assignedAssetCount: Int,
        assignmentCount: Int
    ) {
        log.d {
            "Mosaic assignments section=$sectionLabel assets=$assetCount " +
                "bands=$assignmentCount assignedAssets=$assignedAssetCount " +
                "fallbackOrSkippedAssets=${assetCount - assignedAssetCount}"
        }
    }

    private fun placeholderItemsForGroup(
        bucketIndex: Int,
        sectionLabel: String,
        assets: List<Asset>,
        availableWidth: Float,
        targetRowHeight: Float,
        cachedPlaceholderHeight: Float? = null
    ): List<PhotoGridDisplayItem> {
        val header = groupHeader(IndexedAssetGroup(bucketIndex, sectionLabel, assets))
        val placeholders = if (cachedPlaceholderHeight != null && cachedPlaceholderHeight > 0f) {
            buildPhotoGridPlaceholderItemsForHeight(
                bucketIndex = bucketIndex,
                sectionLabel = sectionLabel,
                estimatedHeight = cachedPlaceholderHeight
            )
        } else {
            buildPhotoGridPlaceholderItems(
                bucketIndex = bucketIndex,
                sectionLabel = sectionLabel,
                assetCount = assets.size,
                availableWidth = availableWidth,
                targetRowHeight = targetRowHeight
            )
        }
        return header + placeholders
    }

    private fun shouldPublishMosaicGroupImmediately(bucketIndex: Int): Boolean =
        !isScrollInProgress || bucketIndex in visibleBucketWindow()

    private fun visibleBucketWindow(): Set<Int> =
        visibleBucketIndexes.flatMap { index -> (index - VISIBLE_GROUP_PREFETCH_RADIUS)..(index + VISIBLE_GROUP_PREFETCH_RADIUS) }
            .filter { it >= 0 }
            .toSet()

    private fun scheduleDeferredMosaicFlush() {
        if (isScrollInProgress || deferredMosaicGroupItems.isEmpty()) return
        if (deferredMosaicFlushJob?.isActive == true) return
        deferredMosaicFlushJob = scope.launch {
            delay(DEFERRED_MOSAIC_FLUSH_DELAY_MS)
            flushDeferredMosaicGroups(visibleWindowOnly = false)
        }
    }

    private fun flushDeferredMosaicGroups(visibleWindowOnly: Boolean) {
        if (deferredMosaicGroupItems.isEmpty()) return
        val eligibleIndexes = if (visibleWindowOnly) visibleBucketWindow() else null
        val updates = deferredMosaicGroupItems
            .filterKeys { index -> eligibleIndexes == null || index in eligibleIndexes }
        if (updates.isEmpty()) return
        updates.keys.forEach(deferredMosaicGroupItems::remove)
        publishMosaicGroupItems(updates)
    }

    private fun publishMosaicGroupItems(updates: Map<Int, List<PhotoGridDisplayItem>>) {
        if (updates.isEmpty()) return
        updateState { state ->
            val current = snapshotOf(state)
            val nextItems = updates.entries.sortedBy { it.key }.fold(current.displayItems) { items, (groupIndex, groupItems) ->
                replaceGroupItems(items, groupIndex, groupItems)
            }
            withDisplayItems(state, nextItems)
        }
    }

    private fun groupHeader(group: IndexedAssetGroup): List<PhotoGridDisplayItem> =
        if (group.label.isNotEmpty()) {
            listOf(HeaderItem(
                gridKey = "h_${group.index}_${group.label}",
                bucketIndex = group.index,
                sectionLabel = group.label,
                label = group.label
            ))
        } else {
            emptyList()
        }

    private fun mosaicLayoutSpecForSnapshot(snapshot: PhotoGridDetailLayoutSnapshot) =
        mosaicLayoutSpecForColumnCount(snapshot.availableWidth, snapshot.viewConfig.mosaicColumnCount)

    private fun replaceGroupItems(
        currentItems: List<PhotoGridDisplayItem>,
        bucketIndex: Int,
        groupItems: List<PhotoGridDisplayItem>
    ): List<PhotoGridDisplayItem> {
        val firstIndex = currentItems.indexOfFirst { it.bucketIndex == bucketIndex }
        if (firstIndex < 0) return currentItems + groupItems
        val lastIndex = currentItems.indexOfLast { it.bucketIndex == bucketIndex }
        return currentItems.take(firstIndex) + groupItems + currentItems.drop(lastIndex + 1)
    }

    private fun bumpAssetRevision(fingerprint: List<DetailGroupFingerprint>) {
        val previous = renderedGroupFingerprints
        assetRevision += 1
        renderedGroupFingerprints = fingerprint
        pendingSyncedContentRevision = false
        displayCache = null
        invalidateChangedGroupCaches(previous, fingerprint)
    }

    private fun invalidateChangedGroupCaches(
        previous: List<DetailGroupFingerprint>?,
        next: List<DetailGroupFingerprint>
    ) {
        val previousOrder = previous?.map { it.label }
        val nextOrder = next.map { it.label }
        // Display items include bucketIndex. If group order changes, cached
        // rows/Mosaic bands from unchanged labels may point at stale indexes.
        if (previous == null || previousOrder != nextOrder) {
            groupDisplayCache.clear()
            return
        }
        val changedIndexes = (0 until maxOf(previous.size, next.size))
            .filter { index -> previous.getOrNull(index) != next.getOrNull(index) }
            .toSet()
        if (changedIndexes.isEmpty()) return
        groupDisplayCache.keys.removeAll { it.bucketIndex in changedIndexes }
    }

    private fun clearLayoutCaches() {
        displayCache = null
        groupDisplayCache.clear()
        deferredMosaicFlushJob?.cancel()
        deferredMosaicFlushJob = null
        deferredMosaicGroupItems.clear()
    }

    private fun nextMosaicGroup(groups: List<KeyedAssetGroup>): KeyedAssetGroup {
        val visible = visibleBucketIndexes.toSet()
        return groups.minWith(compareBy<KeyedAssetGroup> {
            if (it.group.index in visible) 0 else 1
        }.thenBy { it.group.index })
    }

    private fun mosaicGroupRequestKey(groupIndex: Int): String = "group_$groupIndex"

    private fun persistentCacheLookup(
        snapshot: PhotoGridDetailLayoutSnapshot,
        cellHeight: Float
    ): DetailMosaicCacheLookup? {
        val ownerType = cacheOwnerType ?: return null
        if (!snapshot.viewConfig.cacheMosaicResults || !isPersistentCacheComplete()) return null
        return DetailMosaicCacheLookup(
            ownerType = ownerType,
            ownerId = cacheOwnerId,
            groupSize = snapshot.groupSize,
            familiesKey = mosaicDisplayCacheFamiliesKey(snapshot.viewConfig.mosaicFamilies),
            availableWidthKey = mosaicDisplayCacheWidthKey(snapshot.availableWidth),
            cellHeightKey = mosaicDisplayCacheDimensionKey(cellHeight),
            maxRowHeightKey = mosaicDisplayCacheDimensionKey(snapshot.rowHeightBounds.max),
            spacingKey = mosaicDisplayCacheDimensionKey(GRID_SPACING_DP),
            displayVersion = DETAIL_MOSAIC_DISPLAY_CACHE_VERSION
        )
    }
}

private const val VISIBLE_GROUP_PREFETCH_RADIUS = 1
private const val DEFERRED_MOSAIC_FLUSH_DELAY_MS = 120L

private data class KeyedAssetGroup(
    val group: IndexedAssetGroup,
    val assetFingerprint: String,
    val cacheKey: DetailGroupDisplayCacheKey
)

private data class DetailDisplayCacheKey(
    val availableWidth: Float,
    val targetRowHeight: Float,
    val maxRowHeight: Float,
    val groupSize: GroupSize,
    val viewConfig: ViewConfig,
    val assetRevision: Int
)

private data class CachedDisplayItems(
    val key: DetailDisplayCacheKey,
    val items: List<PhotoGridDisplayItem>
)

private data class DetailGroupDisplayCacheKey(
    val bucketIndex: Int,
    val sectionLabel: String,
    val groupSize: GroupSize,
    val availableWidth: Float,
    val targetRowHeight: Float,
    val maxRowHeight: Float,
    val viewConfig: ViewConfig,
    val assets: List<DetailAssetFingerprint>
)

private data class DetailGroupFingerprint(
    val label: String,
    val assets: List<DetailAssetFingerprint>
)

private data class DetailAssetFingerprint(
    val id: String,
    val type: String,
    val fileName: String,
    val createdAt: String,
    val isFavorite: Boolean,
    val stackCount: Int,
    val aspectRatio: Float,
    val isEdited: Boolean
)

private fun PhotoGridDetailLayoutSnapshot.displayCacheKey(assetRevision: Int): DetailDisplayCacheKey =
    DetailDisplayCacheKey(
        availableWidth = availableWidth,
        targetRowHeight = targetRowHeight,
        maxRowHeight = rowHeightBounds.max,
        groupSize = groupSize,
        viewConfig = viewConfig.normalized,
        assetRevision = assetRevision
    )

private fun PhotoGridDetailLayoutSnapshot.groupDisplayCacheKey(
    bucketIndex: Int,
    sectionLabel: String,
    assets: List<Asset>,
    mosaicCellHeight: Float
): DetailGroupDisplayCacheKey =
    DetailGroupDisplayCacheKey(
        bucketIndex = bucketIndex,
        sectionLabel = sectionLabel,
        groupSize = groupSize,
        availableWidth = availableWidth,
        targetRowHeight = mosaicCellHeight,
        maxRowHeight = rowHeightBounds.max,
        viewConfig = viewConfig.normalized,
        assets = assets.detailFingerprint()
    )

private fun List<Asset>.detailGroupFingerprints(groupSize: GroupSize): List<DetailGroupFingerprint> =
    groupAssets(this, groupSize).map { group ->
        DetailGroupFingerprint(
            label = group.label,
            assets = group.assets.detailFingerprint()
        )
    }

private fun List<Asset>.detailFingerprint(): List<DetailAssetFingerprint> =
    map { asset ->
        DetailAssetFingerprint(
            id = asset.id,
            type = asset.type.name,
            fileName = asset.fileName,
            createdAt = asset.createdAt,
            isFavorite = asset.isFavorite,
            stackCount = asset.stackCount,
            aspectRatio = asset.aspectRatio,
            isEdited = asset.isEdited
        )
    }

private fun List<Asset>.detailPersistentFingerprint(): String {
    var hash = FNV_64_OFFSET_BASIS
    forEach { asset ->
        hash = hash.update(asset.id)
        hash = hash.update(asset.type.name)
        hash = hash.update(asset.fileName)
        hash = hash.update(asset.createdAt)
        hash = hash.update(asset.isFavorite.toString())
        hash = hash.update(asset.stackCount.toString())
        hash = hash.update(asset.aspectRatio.toString())
        hash = hash.update(asset.isEdited.toString())
    }
    return hash.toULong().toString(16)
}

private fun Long.update(value: String): Long {
    var hash = this
    value.forEach { char ->
        hash = hash xor char.code.toLong()
        hash *= FNV_64_PRIME
    }
    hash = hash xor '|'.code.toLong()
    hash *= FNV_64_PRIME
    return hash
}

private const val DETAIL_MOSAIC_DISPLAY_CACHE_VERSION = 1
private const val FNV_64_OFFSET_BASIS = -3750763034362895579L
private const val FNV_64_PRIME = 1099511628211L
