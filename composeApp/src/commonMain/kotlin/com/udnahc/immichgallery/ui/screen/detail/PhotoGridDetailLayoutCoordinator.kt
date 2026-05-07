package com.udnahc.immichgallery.ui.screen.detail

import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.DetailMosaicAggregateGeometryEntry
import com.udnahc.immichgallery.domain.model.DetailMosaicArtifacts
import com.udnahc.immichgallery.domain.model.DetailMosaicArtifactsUpsert
import com.udnahc.immichgallery.domain.model.DetailMosaicAssignmentEntry
import com.udnahc.immichgallery.domain.model.DetailMosaicCacheEntry
import com.udnahc.immichgallery.domain.model.DetailMosaicCacheLookup
import com.udnahc.immichgallery.domain.model.DetailMosaicCacheOwnerType
import com.udnahc.immichgallery.domain.model.DetailMosaicSectionGeometryEntry
import com.udnahc.immichgallery.domain.model.GRID_SPACING_DP
import com.udnahc.immichgallery.domain.model.GroupSize
import com.udnahc.immichgallery.domain.model.HeaderItem
import com.udnahc.immichgallery.domain.model.MosaicAssignmentCheckpoint
import com.udnahc.immichgallery.domain.model.MosaicBandItem
import com.udnahc.immichgallery.domain.model.MosaicDisplayBandRecord
import com.udnahc.immichgallery.domain.model.MosaicKeyScope
import com.udnahc.immichgallery.domain.model.MosaicOwnerKey
import com.udnahc.immichgallery.domain.model.MosaicOwnerScope
import com.udnahc.immichgallery.domain.model.MosaicRenderEngine
import com.udnahc.immichgallery.domain.model.MosaicSectionComputer
import com.udnahc.immichgallery.domain.model.MosaicSectionRequest
import com.udnahc.immichgallery.domain.model.MosaicSectionResult
import com.udnahc.immichgallery.domain.model.MOSAIC_FALLBACK_MIN_COMPLETE_ROW_PHOTOS
import com.udnahc.immichgallery.domain.model.MOSAIC_FALLBACK_PROMOTE_WIDE_IMAGES
import com.udnahc.immichgallery.domain.model.PhotoGridDisplayItem
import com.udnahc.immichgallery.domain.model.ProgressChunk
import com.udnahc.immichgallery.domain.model.RowHeightBounds
import com.udnahc.immichgallery.domain.model.RowHeightScope
import com.udnahc.immichgallery.domain.model.RowItem
import com.udnahc.immichgallery.domain.model.RowItemKind
import com.udnahc.immichgallery.domain.model.SectionGeometry
import com.udnahc.immichgallery.domain.model.SectionReady
import com.udnahc.immichgallery.domain.model.ViewConfig
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
import com.udnahc.immichgallery.domain.model.resolvedRealDisplayBandsOrEmpty
import com.udnahc.immichgallery.domain.model.rowHeightBoundsForViewport
import com.udnahc.immichgallery.domain.model.toRealMosaicDisplayItems
import com.udnahc.immichgallery.ui.util.MosaicWorkCancelledException
import com.udnahc.immichgallery.ui.util.MosaicWorkPriority
import com.udnahc.immichgallery.ui.util.MosaicWorkScheduler
import com.udnahc.immichgallery.ui.util.PhotoGridLayoutRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
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
    private val readPersistentArtifacts: suspend (DetailMosaicCacheLookup, Int) -> DetailMosaicArtifacts = { lookup, _ ->
        DetailMosaicArtifacts(displayCache = readPersistentCache(lookup))
    },
    private val upsertPersistentArtifacts: suspend (DetailMosaicArtifactsUpsert) -> Unit = { artifacts ->
        artifacts.displayCache.forEach { upsertPersistentCache(it) }
    },
    private val mosaicEngine: MosaicSectionComputer = MosaicRenderEngine(),
    private val layoutRunner: PhotoGridLayoutRunner = PhotoGridLayoutRunner(scope),
    private val rowHeightPersistenceRunner: PhotoGridLayoutRunner = PhotoGridLayoutRunner(scope),
    private val backgroundDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val log = logging(logTag)
    private var availableViewportHeight = 0f
    private var mosaicLayoutGeneration = 0L
    private var assetRevision = 0
    private var pendingSyncedContentRevision = false
    private var renderedGroupFingerprints: List<DetailGroupFingerprint>? = null
    private var displayCache: CachedDisplayItems? = null
    private val groupDisplayCache = mutableMapOf<DetailGroupDisplayCacheKey, List<PhotoGridDisplayItem>>()
    private val groupDisplayBandCache = mutableMapOf<DetailGroupDisplayCacheKey, List<MosaicDisplayBandRecord>>()
    private val groupSectionGeometryCache = mutableMapOf<DetailGroupDisplayCacheKey, DetailMosaicSectionGeometryEntry>()
    private var visibleBucketIndexes: List<Int> = listOf(0)
    private var isScrollInProgress = false
    private val deferredMosaicGroupItems = mutableMapOf<Int, List<PhotoGridDisplayItem>>()
    private var deferredMosaicFlushJob: Job? = null
    private var resumeMosaicJob: Job? = null
    private var resumeRequestedAgain = false
    private var requestedResumeDelayMs = 0L
    private val mosaicRuntimeStateMutex = Mutex()
    private val persistentCacheRenderer = CachedPhotoGridMosaicRenderer()
    private val runtimeRenderer = RuntimePhotoGridMosaicRenderer()
    private val runtimeGroupStates = mutableMapOf<DetailGroupDisplayCacheKey, DetailRuntimeMosaicGroupState>()

    fun activateForegroundMosaic() {
        scope.launch(backgroundDispatcher) {
            mosaicWorkScheduler.setActiveForegroundOwner(ownerKey)
        }
    }

    fun deactivateForegroundMosaic() {
        scope.launch(backgroundDispatcher) {
            mosaicWorkScheduler.clearActiveForegroundOwner(ownerKey)
            mosaicWorkScheduler.cancelOwner(ownerKey)
        }
    }

    fun onCleared() {
        resumeMosaicJob?.cancel()
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
            val groups = groupAssets(snapshot.assets, snapshot.groupSize)
            val ownerFingerprint = snapshot.assets.detailPersistentFingerprint()
            val existingArtifacts = readPersistentArtifacts(lookup, DETAIL_MOSAIC_GEOMETRY_VERSION)
                .strictReadyDisplayCache(ownerFingerprint, lookup.columnCount)
            val groupItemsByIndex = mutableMapOf<Int, List<PhotoGridDisplayItem>>()
            groups.forEachIndexed { index, assetGroup ->
                val group = IndexedAssetGroup(index, assetGroup.label, assetGroup.assets)
                val fingerprint = group.assets.detailPersistentFingerprint()
                val cachedItems = persistentCacheRenderer.cachedGroupItems(
                    group = group,
                    assetFingerprint = fingerprint,
                    cachedEntries = existingArtifacts
                )
                if (cachedItems != null) {
                    groupItemsByIndex[group.index] = cachedItems
                    return@forEachIndexed
                }
                val ready = withContext(Dispatchers.Default) {
                    val request = mosaicSectionRequest(
                        snapshot = snapshot.copy(viewConfig = normalized),
                        group = group,
                        assetFingerprint = fingerprint,
                        layoutSpec = layoutSpec,
                        generation = mosaicLayoutGeneration
                    )
                    when (val result = mosaicEngine.computeSection(request)) {
                        is MosaicSectionResult.Ready -> result.value
                        is MosaicSectionResult.Failed -> throw result.value.cause
                    }
                }
                val groupItems = groupHeader(group) + ready.displayItems
                groupItemsByIndex[group.index] = groupItems
                upsertPersistentArtifacts(
                    groupArtifactsUpsert(
                        group = group,
                        groupItems = groupItems,
                        assetFingerprint = fingerprint,
                        lookup = lookup,
                        ready = ready
                    )
                )
            }
            if (groupItemsByIndex.size == groups.size) {
                val orderedItems = groupItemsByIndex.entries.sortedBy { it.key }.flatMap { it.value }
                upsertPersistentArtifacts(
                    DetailMosaicArtifactsUpsert(
                        aggregateGeometry = aggregateGeometryEntry(
                            snapshot = snapshot.copy(viewConfig = normalized),
                            lookup = lookup,
                            ownerFingerprint = ownerFingerprint,
                            items = orderedItems
                        )
                    )
                )
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
            scope.launch(backgroundDispatcher) {
                flushDeferredMosaicGroups(visibleWindowOnly = true)
                recoverVisiblePlaceholderMosaicWork()
            }
            scope.launch(backgroundDispatcher) {
                mosaicWorkScheduler.reprioritizePending(
                    ownerKey = ownerKey,
                    visibleRequestKeys = next.map(::mosaicGroupRequestKey).toSet()
                )
            }
            scheduleIncompleteMosaicResume()
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
            scheduleIncompleteMosaicResume()
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
        val ownerFingerprint = snapshot.assets.detailPersistentFingerprint()
        val persistentArtifacts = if (persistentCacheLookup != null) {
            runCatching { readPersistentArtifacts(persistentCacheLookup, DETAIL_MOSAIC_GEOMETRY_VERSION) }
                .onFailure { e -> log.e(e) { "Failed to read Mosaic cache for $logTag owner=$cacheOwnerId" } }
                .getOrDefault(DetailMosaicArtifacts())
        } else {
            DetailMosaicArtifacts()
        }
        val persistentCacheByGroup = persistentArtifacts.strictReadyDisplayCache(ownerFingerprint, persistentCacheLookup?.columnCount ?: 0)
        val persistentGeometryByGroup = persistentArtifacts.strictSectionGeometry(ownerFingerprint, persistentCacheLookup?.columnCount ?: 0)
        val keyedGroups = keyedGroupsForSnapshot(snapshot, layoutSpec)
        val currentKeys = keyedGroups.map { it.cacheKey }.toSet()
        val activeScrollRunnableIndexes = if (isScrollInProgress) {
            visibleBucketIndexes.toSet()
        } else {
            null
        }
        val initialItems = mosaicRuntimeStateMutex.withLock {
            removeRuntimeGroupStatesLocked { it !in currentKeys }
            groupDisplayCache.keys.removeAll { it !in currentKeys }
            groupDisplayBandCache.keys.removeAll { it !in currentKeys }
            groupSectionGeometryCache.keys.removeAll { it !in currentKeys }
            keyedGroups.flatMap { keyed ->
                val persistentGroupKey = DetailPersistentGroupKey(keyed.group.index, keyed.group.label, keyed.assetFingerprint)
                val sectionGeometry = groupSectionGeometryCache[keyed.cacheKey]
                    ?: persistentGeometryByGroup[persistentGroupKey]?.also { groupSectionGeometryCache[keyed.cacheKey] = it }
                groupDisplayCache[keyed.cacheKey]
                    ?: resolvedBandItemsForGroup(keyed.group, groupDisplayBandCache[keyed.cacheKey])
                        ?.also { items -> groupDisplayCache[keyed.cacheKey] = items }
                    ?: persistentCacheRenderer.cachedGroup(
                        group = keyed.group,
                        assetFingerprint = keyed.assetFingerprint,
                        cachedEntries = persistentCacheByGroup
                    )?.also { cached ->
                        groupDisplayCache[keyed.cacheKey] = cached.items
                        if (cached.resolvedBands.isNotEmpty()) {
                            groupDisplayBandCache[keyed.cacheKey] = cached.resolvedBands
                        }
                    }?.items
                    ?: runtimeItemsForGroup(snapshot, layoutSpec, keyed, sectionGeometry)
                    ?: placeholderItemsForGroup(
                        bucketIndex = keyed.group.index,
                        sectionLabel = keyed.group.label,
                        assets = keyed.group.assets,
                        availableWidth = snapshot.availableWidth,
                        targetRowHeight = layoutSpec.cellHeight,
                        cachedPlaceholderHeight = sectionGeometry?.placeholderHeight
                    )
            }.also {
                if (activeScrollRunnableIndexes != null) {
                    keyedGroups
                        .filter { keyed ->
                            keyed.group.index !in activeScrollRunnableIndexes &&
                                groupDisplayCache[keyed.cacheKey] == null &&
                                runtimeGroupStates[keyed.cacheKey] == null
                        }
                        .forEach { keyed ->
                            runtimeGroupStates[keyed.cacheKey] = DetailRuntimeMosaicGroupState.InFlight(
                                checkpoint = emptyMosaicCheckpoint(),
                                chunks = emptyList(),
                                attempts = 0
                            )
                        }
                }
            }
        }
        if (layoutRunner.isCurrent(runnerGeneration) &&
            mosaicGeneration == mosaicLayoutGeneration &&
            revision == assetRevision
        ) {
            updateState { state -> withDisplayItems(state, initialItems) }
        }
        val remainingGroups = mosaicRuntimeStateMutex.withLock {
            keyedGroups
                .filter { keyed ->
                    groupDisplayCache[keyed.cacheKey] == null &&
                        (activeScrollRunnableIndexes == null || keyed.group.index in activeScrollRunnableIndexes) &&
                        runtimeGroupStates[keyed.cacheKey] !is DetailRuntimeMosaicGroupState.RetryableFailure
                }
                .toMutableList()
        }
        while (remainingGroups.isNotEmpty()) {
            val keyedGroup = nextMosaicGroup(remainingGroups)
            remainingGroups.remove(keyedGroup)
            computeRuntimeMosaicGroup(
                snapshot = snapshot,
                layoutSpec = layoutSpec,
                keyedGroup = keyedGroup,
                runnerGeneration = runnerGeneration,
                mosaicGeneration = mosaicGeneration,
                revision = revision,
                persistentCacheLookup = persistentCacheLookup,
                ownerFingerprint = ownerFingerprint,
                keyedGroups = keyedGroups,
                allowRetryable = false
            )
        }
        if (layoutRunner.isCurrent(runnerGeneration) &&
            mosaicGeneration == mosaicLayoutGeneration &&
            revision == assetRevision
        ) {
            cacheCurrentDisplayIfComplete(cacheKey, keyedGroups)
        }
    }

    private suspend fun computeRuntimeMosaicGroup(
        snapshot: PhotoGridDetailLayoutSnapshot,
        layoutSpec: com.udnahc.immichgallery.domain.model.MosaicLayoutSpec,
        keyedGroup: KeyedAssetGroup,
        runnerGeneration: Long?,
        mosaicGeneration: Long,
        revision: Int,
        persistentCacheLookup: DetailMosaicCacheLookup?,
        ownerFingerprint: String,
        keyedGroups: List<KeyedAssetGroup>,
        allowRetryable: Boolean
    ) {
        val existingState = mosaicRuntimeStateMutex.withLock {
            val current = runtimeGroupStates[keyedGroup.cacheKey]
            if (current == null) {
                val checkpoint = emptyMosaicCheckpoint()
                runtimeGroupStates[keyedGroup.cacheKey] = DetailRuntimeMosaicGroupState.InFlight(
                    checkpoint = checkpoint,
                    chunks = emptyList(),
                    attempts = 0
                )
                runtimeGroupStates[keyedGroup.cacheKey]
            } else {
                current
            }
        }
        if (existingState is DetailRuntimeMosaicGroupState.RetryableFailure && !allowRetryable) return
        val group = keyedGroup.group
        var latestCheckpoint = existingState?.checkpoint
        var progressChunks = existingState?.chunks.orEmpty()
        val existingAttempts = existingState?.attempts ?: 0
        val priority = if (group.index in visibleBucketIndexes) {
            MosaicWorkPriority.ForegroundVisible
        } else {
            MosaicWorkPriority.ForegroundPrefetch
        }
        try {
            val ready = mosaicWorkScheduler.run(
                ownerKey = ownerKey,
                requestKey = mosaicGroupRequestKey(group.index),
                generation = mosaicGeneration,
                priority = priority
            ) { token ->
                val request = mosaicSectionRequest(
                    snapshot = snapshot,
                    group = group,
                    assetFingerprint = keyedGroup.assetFingerprint,
                    layoutSpec = layoutSpec,
                    generation = mosaicGeneration
                )
                when (val result = mosaicEngine.computeSection(
                    request = request,
                    resumeCheckpoint = latestCheckpoint,
                    onCheckpoint = { checkpoint -> latestCheckpoint = checkpoint },
                    onProgressChunk = progress@ { chunk ->
                        if (!isCurrentMosaicRun(runnerGeneration, mosaicGeneration, revision)) return@progress
                        progressChunks = mergeProgressChunks(progressChunks, listOf(chunk))
                        val checkpoint = latestCheckpoint ?: return@progress
                        val sectionGeometry = mosaicRuntimeStateMutex.withLock {
                            runtimeGroupStates[keyedGroup.cacheKey] = DetailRuntimeMosaicGroupState.Partial(
                                checkpoint = checkpoint,
                                chunks = progressChunks,
                                attempts = existingAttempts
                            )
                            groupSectionGeometryCache[keyedGroup.cacheKey]
                        }
                        publishOrDeferGroupItems(
                            groupIndex = group.index,
                            items = partialItemsForGroup(snapshot, layoutSpec, group, progressChunks, sectionGeometry)
                        )
                    },
                    shouldContinue = { token.ensureActive() }
                )) {
                    is MosaicSectionResult.Ready -> result.value
                    is MosaicSectionResult.Failed -> throw result.value.cause
                }
            }
            if (!isCurrentMosaicRun(runnerGeneration, mosaicGeneration, revision)) return
            logMosaicAssignmentStats(
                group.label,
                group.assets.size,
                ready.assignments.sumOf { it.sourceCount },
                ready.assignments.size
            )
            val readyGroupItems = groupHeader(group) + ready.displayItems
            val groupItems = if (readyGroupItems.isFinalMosaicGroupDisplay()) {
                readyGroupItems
            } else {
                readyItemsForGroup(snapshot, layoutSpec, group, ready.assignments)
            }
            if (!groupItems.isFinalMosaicGroupDisplay()) {
                val attempts = existingAttempts + 1
                mosaicRuntimeStateMutex.withLock {
                    runtimeGroupStates[keyedGroup.cacheKey] = DetailRuntimeMosaicGroupState.RetryableFailure(
                        checkpoint = latestCheckpoint ?: emptyMosaicCheckpoint(),
                        chunks = progressChunks,
                        attempts = attempts
                    )
                }
                publishOrDeferGroupItems(
                    groupIndex = group.index,
                    items = placeholderItemsForGroup(
                        bucketIndex = group.index,
                        sectionLabel = group.label,
                        assets = group.assets,
                        availableWidth = snapshot.availableWidth,
                        targetRowHeight = layoutSpec.cellHeight,
                        cachedPlaceholderHeight = mosaicRuntimeStateMutex.withLock {
                            groupSectionGeometryCache[keyedGroup.cacheKey]?.placeholderHeight
                        }
                    )
                )
                scheduleIncompleteMosaicResume(delayMs = detailRetryDelayMs(attempts))
                return
            }
            mosaicRuntimeStateMutex.withLock {
                runtimeGroupStates.remove(keyedGroup.cacheKey)
                groupDisplayCache[keyedGroup.cacheKey] = groupItems
                persistentCacheLookup?.let { lookup ->
                    groupSectionGeometryCache[keyedGroup.cacheKey] = sectionGeometryEntry(
                        group = group,
                        geometry = ready.geometry,
                        assetFingerprint = keyedGroup.assetFingerprint,
                        lookup = lookup
                    )
                }
                val resolvedBands = resolvedBandsForGroupItems(group, groupItems)
                if (resolvedBands.isNotEmpty()) {
                    groupDisplayBandCache[keyedGroup.cacheKey] = resolvedBands
                } else {
                    groupDisplayBandCache.remove(keyedGroup.cacheKey)
                }
            }
            publishOrDeferGroupItems(group.index, groupItems)
            persistentCacheLookup?.let { lookup ->
                writeArtifactsAsync(
                    artifacts = groupArtifactsUpsert(
                        group = group,
                        groupItems = groupItems,
                        assetFingerprint = keyedGroup.assetFingerprint,
                        lookup = lookup,
                        ready = ready
                    ),
                    onFailure = { e ->
                        log.e(e) { "Failed to write Mosaic cache for $logTag owner=$cacheOwnerId section=${group.label}" }
                    }
                )
                maybeWriteAggregateGeometryAsync(
                    snapshot = snapshot,
                    lookup = lookup,
                    ownerFingerprint = ownerFingerprint,
                    keyedGroups = keyedGroups
                ) { e ->
                    log.e(e) { "Failed to write Mosaic aggregate geometry for $logTag owner=$cacheOwnerId" }
                }
            }
        } catch (e: MosaicWorkCancelledException) {
            if (!isCurrentMosaicRun(runnerGeneration, mosaicGeneration, revision)) return
            val checkpoint = latestCheckpoint ?: emptyMosaicCheckpoint()
            if (progressChunks.isEmpty()) {
                mosaicRuntimeStateMutex.withLock {
                    runtimeGroupStates[keyedGroup.cacheKey] = DetailRuntimeMosaicGroupState.InFlight(
                        checkpoint = checkpoint,
                        chunks = emptyList(),
                        attempts = existingAttempts
                    )
                }
            } else {
                mosaicRuntimeStateMutex.withLock {
                    runtimeGroupStates[keyedGroup.cacheKey] = DetailRuntimeMosaicGroupState.Partial(
                        checkpoint = checkpoint,
                        chunks = progressChunks,
                        attempts = existingAttempts
                    )
                }
            }
            scheduleIncompleteMosaicResume()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!isCurrentMosaicRun(runnerGeneration, mosaicGeneration, revision)) return
            log.e(e) { "Mosaic assignments failed for $logTag owner=$ownerKey section=${group.label}" }
            val checkpoint = latestCheckpoint ?: emptyMosaicCheckpoint()
            val attempts = existingAttempts + 1
            mosaicRuntimeStateMutex.withLock {
                runtimeGroupStates[keyedGroup.cacheKey] = DetailRuntimeMosaicGroupState.RetryableFailure(
                    checkpoint = checkpoint,
                    chunks = progressChunks,
                    attempts = attempts
                )
            }
            publishOrDeferGroupItems(
                group.index,
                placeholderItemsForGroup(
                    bucketIndex = group.index,
                    sectionLabel = group.label,
                    assets = group.assets,
                    availableWidth = snapshot.availableWidth,
                    targetRowHeight = layoutSpec.cellHeight,
                    cachedPlaceholderHeight = mosaicRuntimeStateMutex.withLock {
                        groupSectionGeometryCache[keyedGroup.cacheKey]?.placeholderHeight
                    }
                )
            )
            if (group.index in visibleBucketWindow()) {
                scheduleIncompleteMosaicResume(delayMs = detailRetryDelayMs(attempts))
            }
        }
    }

    private fun writeArtifactsAsync(
        artifacts: DetailMosaicArtifactsUpsert,
        onFailure: (Throwable) -> Unit
    ) {
        scope.launch(ioDispatcher) {
            runCatching { upsertPersistentArtifacts(artifacts) }.onFailure(onFailure)
        }
    }

    private fun keyedGroupsForSnapshot(
        snapshot: PhotoGridDetailLayoutSnapshot,
        layoutSpec: com.udnahc.immichgallery.domain.model.MosaicLayoutSpec
    ): List<KeyedAssetGroup> =
        groupAssets(snapshot.assets, snapshot.groupSize).mapIndexed { index, group ->
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

    private fun runtimeItemsForGroup(
        snapshot: PhotoGridDetailLayoutSnapshot,
        layoutSpec: com.udnahc.immichgallery.domain.model.MosaicLayoutSpec,
        keyed: KeyedAssetGroup,
        sectionGeometry: DetailMosaicSectionGeometryEntry?
    ): List<PhotoGridDisplayItem>? =
        when (val state = runtimeGroupStates[keyed.cacheKey]) {
            is DetailRuntimeMosaicGroupState.InFlight ->
                placeholderItemsForGroup(
                    bucketIndex = keyed.group.index,
                    sectionLabel = keyed.group.label,
                    assets = keyed.group.assets,
                    availableWidth = snapshot.availableWidth,
                    targetRowHeight = layoutSpec.cellHeight,
                    cachedPlaceholderHeight = sectionGeometry?.placeholderHeight
                )
            is DetailRuntimeMosaicGroupState.Partial ->
                partialItemsForGroup(snapshot, layoutSpec, keyed.group, state.chunks, sectionGeometry)
            is DetailRuntimeMosaicGroupState.RetryableFailure ->
                placeholderItemsForGroup(
                    bucketIndex = keyed.group.index,
                    sectionLabel = keyed.group.label,
                    assets = keyed.group.assets,
                    availableWidth = snapshot.availableWidth,
                    targetRowHeight = layoutSpec.cellHeight,
                    cachedPlaceholderHeight = sectionGeometry?.placeholderHeight
                )
            null -> null
        }

    private fun partialItemsForGroup(
        snapshot: PhotoGridDetailLayoutSnapshot,
        layoutSpec: com.udnahc.immichgallery.domain.model.MosaicLayoutSpec,
        group: IndexedAssetGroup,
        chunks: List<ProgressChunk>,
        sectionGeometry: DetailMosaicSectionGeometryEntry? = null
    ): List<PhotoGridDisplayItem> {
        val exactItems = sectionGeometry?.ranges?.takeIf { it.isNotEmpty() }?.let { ranges ->
            mosaicEngine.projectPartialSectionWithGeometry(
                assets = group.assets,
                chunks = chunks,
                geometryRanges = ranges,
                bucketIndex = group.index,
                sectionLabel = group.label,
                layoutSpec = layoutSpec,
                spacing = GRID_SPACING_DP,
                maxRowHeight = snapshot.rowHeightBounds.max
            )
        }
        if (sectionGeometry != null && exactItems == null) {
            return placeholderItemsForGroup(
                bucketIndex = group.index,
                sectionLabel = group.label,
                assets = group.assets,
                availableWidth = snapshot.availableWidth,
                targetRowHeight = layoutSpec.cellHeight,
                cachedPlaceholderHeight = sectionGeometry.placeholderHeight
            )
        }
        val projected = exactItems ?: mosaicEngine.projectPartialSectionWithPlaceholders(
            assets = group.assets,
            chunks = chunks,
            bucketIndex = group.index,
            sectionLabel = group.label,
            layoutSpec = layoutSpec,
            spacing = GRID_SPACING_DP,
            maxRowHeight = snapshot.rowHeightBounds.max
        )
        return groupHeader(group) + projected
    }

    private fun readyItemsForGroup(
        snapshot: PhotoGridDetailLayoutSnapshot,
        layoutSpec: com.udnahc.immichgallery.domain.model.MosaicLayoutSpec,
        group: IndexedAssetGroup,
        assignments: List<com.udnahc.immichgallery.domain.model.MosaicBandAssignment>
    ): List<PhotoGridDisplayItem> =
        groupHeader(group) + mosaicEngine.projectReadySection(
            assets = group.assets,
            assignments = assignments,
            bucketIndex = group.index,
            sectionLabel = group.label,
            layoutSpec = layoutSpec,
            spacing = GRID_SPACING_DP,
            maxRowHeight = snapshot.rowHeightBounds.max
        )

    private fun resolvedBandItemsForGroup(
        group: IndexedAssetGroup,
        resolvedBands: List<MosaicDisplayBandRecord>?
    ): List<PhotoGridDisplayItem>? {
        if (resolvedBands.isNullOrEmpty()) return null
        val bandItems = resolvedBands.toRealMosaicDisplayItems(
            assets = group.assets,
            bucketIndex = group.index,
            sectionLabel = group.label,
            keyPrefix = "detail_mosaic_memory"
        )
        if (resolvedRealDisplayBandsOrEmpty(bandItems, group.assets).isEmpty()) return null
        return groupHeader(group) + bandItems
    }

    private fun resolvedBandsForGroupItems(
        group: IndexedAssetGroup,
        groupItems: List<PhotoGridDisplayItem>
    ): List<MosaicDisplayBandRecord> =
        resolvedRealDisplayBandsOrEmpty(
            displayItems = groupItems.filter { it !is HeaderItem },
            assets = group.assets
        )

    private suspend fun publishOrDeferGroupItems(
        groupIndex: Int,
        items: List<PhotoGridDisplayItem>
    ) {
        val publishNow = mosaicRuntimeStateMutex.withLock {
            if (!items.isFinalMosaicGroupDisplay() && readyCachedItemsForGroupIndex(groupIndex) != null) {
                null
            } else if (shouldPublishMosaicGroupImmediately(groupIndex)) {
                deferredMosaicGroupItems.remove(groupIndex)
                true
            } else {
                deferredMosaicGroupItems[groupIndex] = items
                false
            }
        } ?: return
        if (publishNow) {
            publishMosaicGroupItems(mapOf(groupIndex to items))
        } else {
            scheduleDeferredMosaicFlush()
        }
    }

    private fun readyCachedItemsForGroupIndex(groupIndex: Int): List<PhotoGridDisplayItem>? =
        groupDisplayCache.values.firstOrNull { cachedItems ->
            cachedItems.isFinalMosaicGroupDisplay() && cachedItems.any { it.bucketIndex == groupIndex }
        }

    private fun isCurrentMosaicRun(
        runnerGeneration: Long?,
        mosaicGeneration: Long,
        revision: Int
    ): Boolean =
        (runnerGeneration == null || layoutRunner.isCurrent(runnerGeneration)) &&
            mosaicGeneration == mosaicLayoutGeneration &&
            revision == assetRevision

    private fun mergeProgressChunks(
        existing: List<ProgressChunk>,
        incoming: List<ProgressChunk>
    ): List<ProgressChunk> =
        (existing + incoming)
            .distinctBy { it.sourceStartIndex to it.sourceEndExclusive }
            .sortedBy { it.sourceStartIndex }

    private fun emptyMosaicCheckpoint(): MosaicAssignmentCheckpoint =
        MosaicAssignmentCheckpoint(
            assignments = emptyList(),
            sourceIndex = 0,
            bandIndex = 0,
            chunkStartIndex = 0,
            lastEmittedBandCount = 0
        )

    private suspend fun cacheCurrentDisplayIfComplete(
        cacheKey: DetailDisplayCacheKey,
        keyedGroups: List<KeyedAssetGroup>
    ) {
        val currentKeys = keyedGroups.map { it.cacheKey }.toSet()
        val currentItems = snapshotOf(getState()).displayItems
        if (currentItems.any { it is com.udnahc.immichgallery.domain.model.PlaceholderItem }) return
        val allPublished = mosaicRuntimeStateMutex.withLock {
            if (keyedGroups.any { groupDisplayCache[it.cacheKey] == null } ||
                runtimeGroupStates.keys.toList().any { it in currentKeys } ||
                deferredMosaicGroupItems.isNotEmpty()
            ) {
                false
            } else keyedGroups.all { keyed ->
                val finalItems = groupDisplayCache[keyed.cacheKey] ?: return@all false
                if (!finalItems.isFinalMosaicGroupDisplay()) return@all false
                if (finalItems.any { it is com.udnahc.immichgallery.domain.model.PlaceholderItem }) return@all false
                val firstIndex = currentItems.indexOfFirst { it.bucketIndex == keyed.group.index }
                if (firstIndex < 0) return@all false
                val lastIndex = currentItems.indexOfLast { it.bucketIndex == keyed.group.index }
                currentItems.subList(firstIndex, lastIndex + 1) == finalItems
            }
        }
        if (allPublished) {
            displayCache = CachedDisplayItems(cacheKey, currentItems)
        }
    }

    private fun scheduleIncompleteMosaicResume(delayMs: Long = 0L) {
        if (resumeMosaicJob?.isActive == true) {
            resumeRequestedAgain = true
            if (delayMs > requestedResumeDelayMs) requestedResumeDelayMs = delayMs
            return
        }
        resumeMosaicJob = scope.launch(backgroundDispatcher) {
            if (delayMs > 0L) delay(delayMs)
            try {
                val snapshot = snapshotOf(getState())
                val layoutSpec = mosaicLayoutSpecForSnapshot(snapshot) ?: return@launch
                if (!snapshot.viewConfig.mosaicEnabled || snapshot.assets.isEmpty() || snapshot.availableWidth <= 0f) return@launch
                val keyedGroups = keyedGroupsForSnapshot(snapshot, layoutSpec)
                val keyedByKey = keyedGroups.associateBy { it.cacheKey }
                val visibleWindow = visibleBucketWindow()
                val runnableIndexes = if (isScrollInProgress) {
                    visibleBucketIndexes.toSet()
                } else {
                    null
                }
                val (runtimeStateSnapshot, cachedGroupKeys) = mosaicRuntimeStateMutex.withLock {
                    removeRuntimeGroupStatesLocked { it !in keyedByKey.keys }
                    runtimeGroupStates.toMap() to groupDisplayCache.keys.toSet()
                }
                val targets = runtimeStateSnapshot.entries
                    .mapNotNull { (key, runtimeState) ->
                        keyedByKey[key]?.let { keyed -> keyed to runtimeState }
                    }
                    .filter { (keyed, _) -> keyed.cacheKey !in cachedGroupKeys }
                    .filter { (keyed, runtimeState) ->
                        runnableIndexes == null || keyed.group.index in runnableIndexes
                    }
                    .filter { (keyed, runtimeState) ->
                        when (runtimeState) {
                            is DetailRuntimeMosaicGroupState.RetryableFailure -> keyed.group.index in visibleWindow
                            else -> true
                        }
                    }
                    .map { (keyed, _) -> keyed }
                    .sortedWith(compareBy<KeyedAssetGroup> {
                        if (it.group.index in visibleBucketIndexes) 0 else if (it.group.index in visibleWindow) 1 else 2
                    }.thenBy { it.group.index })
                val persistentLookup = persistentCacheLookup(snapshot, layoutSpec.cellHeight)
                val ownerFingerprint = snapshot.assets.detailPersistentFingerprint()
                val generation = mosaicLayoutGeneration
                val revision = assetRevision
                targets.forEach { keyed ->
                    computeRuntimeMosaicGroup(
                        snapshot = snapshot,
                        layoutSpec = layoutSpec,
                        keyedGroup = keyed,
                        runnerGeneration = null,
                        mosaicGeneration = generation,
                        revision = revision,
                        persistentCacheLookup = persistentLookup,
                        ownerFingerprint = ownerFingerprint,
                        keyedGroups = keyedGroups,
                        allowRetryable = true
                    )
                    if (keyed.group.index !in visibleBucketIndexes) yield()
                }
                cacheCurrentDisplayIfComplete(snapshot.displayCacheKey(revision), keyedGroups)
            } finally {
                val shouldRunAgain = resumeRequestedAgain
                val nextDelay = requestedResumeDelayMs
                resumeRequestedAgain = false
                requestedResumeDelayMs = 0L
                resumeMosaicJob = null
                if (shouldRunAgain) {
                    scheduleIncompleteMosaicResume(delayMs = nextDelay)
                }
            }
        }
    }

    private suspend fun recoverVisiblePlaceholderMosaicWork() {
        val snapshot = snapshotOf(getState())
        if (!snapshot.viewConfig.mosaicEnabled || snapshot.assets.isEmpty() || snapshot.availableWidth <= 0f) return
        val layoutSpec = mosaicLayoutSpecForSnapshot(snapshot) ?: return
        val visible = visibleBucketIndexes.toSet()
        if (visible.isEmpty()) return
        val placeholderGroups = snapshot.displayItems
            .filterIsInstance<com.udnahc.immichgallery.domain.model.PlaceholderItem>()
            .mapTo(mutableSetOf()) { it.bucketIndex }
        if (placeholderGroups.isEmpty()) return
        val keyedGroups = keyedGroupsForSnapshot(snapshot, layoutSpec)
            .filter { keyed -> keyed.group.index in visible && keyed.group.index in placeholderGroups }
        if (keyedGroups.isEmpty()) return
        var created = 0
        mosaicRuntimeStateMutex.withLock {
            keyedGroups.forEach { keyed ->
                if (groupDisplayCache[keyed.cacheKey] != null) return@forEach
                if (deferredMosaicGroupItems[keyed.group.index]?.isFinalMosaicGroupDisplay() == true) return@forEach
                if (runtimeGroupStates[keyed.cacheKey] != null) return@forEach
                runtimeGroupStates[keyed.cacheKey] = DetailRuntimeMosaicGroupState.InFlight(
                    checkpoint = emptyMosaicCheckpoint(),
                    chunks = emptyList(),
                    attempts = 0
                )
                created += 1
            }
        }
        if (created > 0) {
            log.d {
                "Detail Mosaic visible placeholder recovery scheduled owner=$ownerKey " +
                    "visible=$visible placeholders=$placeholderGroups created=$created"
            }
            scheduleIncompleteMosaicResume()
        }
    }

    private suspend fun maybeWriteAggregateGeometryAsync(
        snapshot: PhotoGridDetailLayoutSnapshot,
        lookup: DetailMosaicCacheLookup,
        ownerFingerprint: String,
        keyedGroups: List<KeyedAssetGroup>,
        onFailure: (Throwable) -> Unit
    ) {
        val orderedItems = mosaicRuntimeStateMutex.withLock {
            keyedGroups.map { keyed ->
                groupDisplayCache[keyed.cacheKey] ?: return@withLock null
            }.flatten()
        } ?: return
        writeArtifactsAsync(
            artifacts = DetailMosaicArtifactsUpsert(
                aggregateGeometry = aggregateGeometryEntry(
                    snapshot = snapshot,
                    lookup = lookup,
                    ownerFingerprint = ownerFingerprint,
                    items = orderedItems
                )
            ),
            onFailure = onFailure
        )
    }

    private fun groupArtifactsUpsert(
        group: IndexedAssetGroup,
        groupItems: List<PhotoGridDisplayItem>,
        assetFingerprint: String,
        lookup: DetailMosaicCacheLookup,
        ready: SectionReady
    ): DetailMosaicArtifactsUpsert =
        DetailMosaicArtifactsUpsert(
            assignments = listOf(assignmentEntry(group, assetFingerprint, lookup, ready)),
            displayCache = listOfNotNull(runtimeRenderer.groupCacheEntry(group, groupItems, assetFingerprint, lookup)),
            sectionGeometry = listOf(sectionGeometryEntry(group, ready.geometry, assetFingerprint, lookup))
        )

    private fun assignmentEntry(
        group: IndexedAssetGroup,
        assetFingerprint: String,
        lookup: DetailMosaicCacheLookup,
        ready: SectionReady
    ): DetailMosaicAssignmentEntry =
        DetailMosaicAssignmentEntry(
            ownerType = lookup.ownerType,
            ownerId = lookup.ownerId,
            groupSize = lookup.groupSize,
            columnCount = lookup.columnCount,
            sectionIndex = group.index,
            sectionKey = group.label,
            familiesKey = lookup.familiesKey,
            assetFingerprint = assetFingerprint,
            assignments = ready.assignments,
            updatedAt = kotlin.time.Clock.System.now().toEpochMilliseconds()
        )

    private fun sectionGeometryEntry(
        group: IndexedAssetGroup,
        geometry: SectionGeometry,
        assetFingerprint: String,
        lookup: DetailMosaicCacheLookup
    ): DetailMosaicSectionGeometryEntry =
        DetailMosaicSectionGeometryEntry(
            ownerType = lookup.ownerType,
            ownerId = lookup.ownerId,
            groupSize = lookup.groupSize,
            columnCount = lookup.columnCount,
            sectionIndex = group.index,
            sectionKey = group.label,
            familiesKey = lookup.familiesKey,
            assetFingerprint = assetFingerprint,
            availableWidthKey = lookup.availableWidthKey,
            cellHeightKey = lookup.cellHeightKey,
            maxRowHeightKey = lookup.maxRowHeightKey,
            spacingKey = lookup.spacingKey,
            geometryVersion = DETAIL_MOSAIC_GEOMETRY_VERSION,
            placeholderHeight = geometry.placeholderHeight,
            displayItemCount = geometry.displayItemCount,
            ranges = geometry.ranges,
            updatedAt = kotlin.time.Clock.System.now().toEpochMilliseconds()
        )

    private fun aggregateGeometryEntry(
        snapshot: PhotoGridDetailLayoutSnapshot,
        lookup: DetailMosaicCacheLookup,
        ownerFingerprint: String,
        items: List<PhotoGridDisplayItem>
    ): DetailMosaicAggregateGeometryEntry =
        DetailMosaicAggregateGeometryEntry(
            ownerType = lookup.ownerType,
            ownerId = lookup.ownerId,
            groupSize = snapshot.groupSize,
            columnCount = lookup.columnCount,
            familiesKey = lookup.familiesKey,
            assetFingerprint = ownerFingerprint,
            availableWidthKey = lookup.availableWidthKey,
            cellHeightKey = lookup.cellHeightKey,
            maxRowHeightKey = lookup.maxRowHeightKey,
            spacingKey = lookup.spacingKey,
            geometryVersion = DETAIL_MOSAIC_GEOMETRY_VERSION,
            placeholderHeight = estimatePhotoGridDisplayItemsHeight(items, GRID_SPACING_DP),
            displayItemCount = items.size,
            updatedAt = kotlin.time.Clock.System.now().toEpochMilliseconds()
        )

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
                items.addAll(
                    placeholderItemsForGroup(
                        bucketIndex = index,
                        sectionLabel = group.label,
                        assets = group.assets,
                        availableWidth = snapshot.availableWidth,
                        targetRowHeight = layoutSpec.cellHeight
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
        if (isScrollInProgress) return
        if (deferredMosaicFlushJob?.isActive == true) return
        deferredMosaicFlushJob = scope.launch(backgroundDispatcher) {
            delay(DEFERRED_MOSAIC_FLUSH_DELAY_MS)
            flushDeferredMosaicGroups(visibleWindowOnly = false)
        }
    }

    private suspend fun flushDeferredMosaicGroups(visibleWindowOnly: Boolean) {
        val eligibleIndexes = if (visibleWindowOnly) visibleBucketWindow() else null
        val updates = mosaicRuntimeStateMutex.withLock {
            if (deferredMosaicGroupItems.isEmpty()) {
                emptyMap()
            } else {
                deferredMosaicGroupItems
                    .filterKeys { index -> eligibleIndexes == null || index in eligibleIndexes }
                    .also { updates ->
                        updates.keys.forEach(deferredMosaicGroupItems::remove)
                    }
            }
        }
        if (updates.isEmpty()) return
        publishMosaicGroupItems(updates)
        cacheCurrentDisplayIfCompleteForCurrentSnapshot()
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

    private suspend fun cacheCurrentDisplayIfCompleteForCurrentSnapshot() {
        val snapshot = snapshotOf(getState())
        val layoutSpec = mosaicLayoutSpecForSnapshot(snapshot) ?: return
        cacheCurrentDisplayIfComplete(
            cacheKey = snapshot.displayCacheKey(assetRevision),
            keyedGroups = keyedGroupsForSnapshot(snapshot, layoutSpec)
        )
    }

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
            mutateMosaicRuntimeState {
                groupDisplayCache.clear()
                groupDisplayBandCache.clear()
                groupSectionGeometryCache.clear()
                runtimeGroupStates.clear()
            }
            return
        }
        val changedIndexes = (0 until maxOf(previous.size, next.size))
            .filter { index -> previous.getOrNull(index) != next.getOrNull(index) }
            .toSet()
        if (changedIndexes.isEmpty()) return
        mutateMosaicRuntimeState {
            val staleKeys = (groupDisplayCache.keys + groupDisplayBandCache.keys + groupSectionGeometryCache.keys)
                .filter { it.bucketIndex in changedIndexes }
            staleKeys.forEach(groupDisplayCache::remove)
            staleKeys.forEach(groupDisplayBandCache::remove)
            staleKeys.forEach(groupSectionGeometryCache::remove)
        }
        removeRuntimeGroupStates { it.bucketIndex in changedIndexes }
    }

    private fun removeRuntimeGroupStates(predicate: (DetailGroupDisplayCacheKey) -> Boolean) {
        // Non-suspend callers clear generation-owned state before scheduling
        // new work. Suspend runtime paths use removeRuntimeGroupStatesLocked.
        mutateMosaicRuntimeState {
            runtimeGroupStates.keys.toList()
                .filter(predicate)
                .forEach(runtimeGroupStates::remove)
        }
    }

    private fun removeRuntimeGroupStatesLocked(predicate: (DetailGroupDisplayCacheKey) -> Boolean) {
        runtimeGroupStates.keys.toList()
            .filter(predicate)
            .forEach(runtimeGroupStates::remove)
    }

    private fun clearLayoutCaches() {
        displayCache = null
        resumeMosaicJob?.cancel()
        resumeMosaicJob = null
        deferredMosaicFlushJob?.cancel()
        deferredMosaicFlushJob = null
        mutateMosaicRuntimeState {
            groupDisplayCache.clear()
            groupDisplayBandCache.clear()
            groupSectionGeometryCache.clear()
            runtimeGroupStates.clear()
            deferredMosaicGroupItems.clear()
        }
    }

    private inline fun mutateMosaicRuntimeState(crossinline block: () -> Unit) {
        if (mosaicRuntimeStateMutex.tryLock()) {
            try {
                block()
            } finally {
                mosaicRuntimeStateMutex.unlock()
            }
        } else {
            scope.launch(backgroundDispatcher) {
                mosaicRuntimeStateMutex.withLock {
                    block()
                }
            }
        }
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
            columnCount = snapshot.viewConfig.mosaicColumnCount,
            familiesKey = mosaicDisplayCacheFamiliesKey(snapshot.viewConfig.mosaicFamilies),
            availableWidthKey = mosaicDisplayCacheWidthKey(snapshot.availableWidth),
            cellHeightKey = mosaicDisplayCacheDimensionKey(cellHeight),
            maxRowHeightKey = mosaicDisplayCacheDimensionKey(snapshot.rowHeightBounds.max),
            spacingKey = mosaicDisplayCacheDimensionKey(GRID_SPACING_DP),
            displayVersion = DETAIL_MOSAIC_DISPLAY_CACHE_VERSION
        )
    }

    private fun mosaicSectionRequest(
        snapshot: PhotoGridDetailLayoutSnapshot,
        group: IndexedAssetGroup,
        assetFingerprint: String,
        layoutSpec: com.udnahc.immichgallery.domain.model.MosaicLayoutSpec,
        generation: Long
    ): MosaicSectionRequest {
        val ownerScope = when (cacheOwnerType) {
            DetailMosaicCacheOwnerType.PERSON -> MosaicOwnerScope.PERSON
            DetailMosaicCacheOwnerType.ALBUM,
            null -> MosaicOwnerScope.ALBUM
        }
        return MosaicSectionRequest(
            keyScope = MosaicKeyScope(
                owner = MosaicOwnerKey(ownerScope, cacheOwnerId.ifEmpty { ownerKey }),
                sectionKey = group.label.ifEmpty { "section_${group.index}" },
                columnCount = snapshot.viewConfig.mosaicColumnCount,
                familiesKey = mosaicDisplayCacheFamiliesKey(snapshot.viewConfig.mosaicFamilies),
                contentFingerprint = assetFingerprint,
                generation = generation
            ),
            assets = group.assets,
            bucketIndex = group.index,
            sectionLabel = group.label,
            assignmentLayoutSpec = layoutSpec,
            displayLayoutSpec = layoutSpec,
            spacing = GRID_SPACING_DP,
            maxRowHeight = snapshot.rowHeightBounds.max,
            enabledFamilies = snapshot.viewConfig.mosaicFamilies
        )
    }
}

private const val VISIBLE_GROUP_PREFETCH_RADIUS = 1
private const val DEFERRED_MOSAIC_FLUSH_DELAY_MS = 120L

private fun detailRetryDelayMs(attempts: Int): Long {
    var delay = 250L
    repeat((attempts - 1).coerceIn(0, 4)) {
        delay *= 2
    }
    return minOf(delay, 5_000L)
}

private fun List<PhotoGridDisplayItem>.isFinalMosaicGroupDisplay(): Boolean =
    isNotEmpty() &&
        filterNot { it is HeaderItem }.let { photoItems ->
            photoItems.isNotEmpty() &&
                photoItems.none { it is com.udnahc.immichgallery.domain.model.PlaceholderItem } &&
                photoItems.all { item ->
                    item is MosaicBandItem ||
                        (item is RowItem && item.kind == RowItemKind.MOSAIC_FALLBACK)
                }
        }

private data class KeyedAssetGroup(
    val group: IndexedAssetGroup,
    val assetFingerprint: String,
    val cacheKey: DetailGroupDisplayCacheKey
)

private sealed interface DetailRuntimeMosaicGroupState {
    val checkpoint: MosaicAssignmentCheckpoint
    val chunks: List<ProgressChunk>
    val attempts: Int

    data class InFlight(
        override val checkpoint: MosaicAssignmentCheckpoint,
        override val chunks: List<ProgressChunk>,
        override val attempts: Int
    ) : DetailRuntimeMosaicGroupState

    data class Partial(
        override val checkpoint: MosaicAssignmentCheckpoint,
        override val chunks: List<ProgressChunk>,
        override val attempts: Int
    ) : DetailRuntimeMosaicGroupState

    data class RetryableFailure(
        override val checkpoint: MosaicAssignmentCheckpoint,
        override val chunks: List<ProgressChunk>,
        override val attempts: Int
    ) : DetailRuntimeMosaicGroupState
}

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

private fun DetailMosaicArtifacts.strictReadyDisplayCache(
    ownerFingerprint: String,
    columnCount: Int
): Map<DetailPersistentGroupKey, DetailMosaicCacheEntry> {
    val assignmentKeys = assignments.mapTo(mutableSetOf()) {
        if (it.columnCount != columnCount) return@mapTo DetailPersistentGroupKey(-1, "", "")
        DetailPersistentGroupKey(it.sectionIndex, it.sectionKey, it.assetFingerprint)
    }
    val geometryKeys = sectionGeometry.mapTo(mutableSetOf()) {
        if (it.columnCount != columnCount) return@mapTo DetailPersistentGroupKey(-1, "", "")
        DetailPersistentGroupKey(it.sectionIndex, it.sectionKey, it.assetFingerprint)
    }
    return displayCache.associateBy {
        if (it.columnCount != columnCount) return@associateBy DetailPersistentGroupKey(-1, "", "")
        DetailPersistentGroupKey(it.sectionIndex, it.sectionKey, it.assetFingerprint)
    }.filterKeys { key ->
        key.assetFingerprint.isNotEmpty() && key in assignmentKeys && key in geometryKeys
    }
}

private fun DetailMosaicArtifacts.strictSectionGeometry(
    ownerFingerprint: String,
    columnCount: Int
): Map<DetailPersistentGroupKey, DetailMosaicSectionGeometryEntry> {
    return sectionGeometry.associateBy {
        if (it.columnCount != columnCount || it.assetFingerprint.isEmpty() || it.placeholderHeight <= 0f) {
            return@associateBy DetailPersistentGroupKey(-1, "", "")
        }
        DetailPersistentGroupKey(it.sectionIndex, it.sectionKey, it.assetFingerprint)
    }.filterKeys { it.assetFingerprint.isNotEmpty() }
}

private const val DETAIL_MOSAIC_DISPLAY_CACHE_VERSION = 1
private const val DETAIL_MOSAIC_GEOMETRY_VERSION = 1
private const val FNV_64_OFFSET_BASIS = -3750763034362895579L
private const val FNV_64_PRIME = 1099511628211L
