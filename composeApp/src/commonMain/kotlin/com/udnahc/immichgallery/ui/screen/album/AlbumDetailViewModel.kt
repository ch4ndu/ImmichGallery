package com.udnahc.immichgallery.ui.screen.album

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.udnahc.immichgallery.domain.action.settings.SetTargetRowHeightAction
import com.udnahc.immichgallery.domain.action.settings.SetViewConfigAction
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.AssetDetail
import com.udnahc.immichgallery.domain.model.DEFAULT_TARGET_ROW_HEIGHT
import com.udnahc.immichgallery.domain.model.GRID_SPACING_DP
import com.udnahc.immichgallery.domain.model.GroupSize
import com.udnahc.immichgallery.domain.model.HeaderItem
import com.udnahc.immichgallery.domain.model.MOSAIC_FALLBACK_MIN_COMPLETE_ROW_PHOTOS
import com.udnahc.immichgallery.domain.model.MOSAIC_FALLBACK_PROMOTE_WIDE_IMAGES
import com.udnahc.immichgallery.domain.model.PhotoGridDisplayItem
import com.udnahc.immichgallery.domain.model.RowHeightBounds
import com.udnahc.immichgallery.domain.model.RowHeightScope
import com.udnahc.immichgallery.domain.model.TimelineDisplayIndex
import com.udnahc.immichgallery.domain.model.ViewConfig
import com.udnahc.immichgallery.domain.model.buildMosaicAssignments
import com.udnahc.immichgallery.domain.model.buildPhotoGridDisplayIndex
import com.udnahc.immichgallery.domain.model.buildPhotoGridItemsWithMosaic
import com.udnahc.immichgallery.domain.model.buildPhotoGridPlaceholderItems
import com.udnahc.immichgallery.domain.model.defaultTargetRowHeightForWidth
import com.udnahc.immichgallery.domain.model.groupAssets
import com.udnahc.immichgallery.domain.model.mosaicFallbackRowHeight
import com.udnahc.immichgallery.domain.model.mosaicLayoutSpecFor
import com.udnahc.immichgallery.domain.model.packIntoRows
import com.udnahc.immichgallery.domain.model.rowHeightBoundsForViewport
import com.udnahc.immichgallery.domain.usecase.album.GetAlbumDetailUseCase
import com.udnahc.immichgallery.domain.usecase.asset.GetAssetDetailUseCase
import com.udnahc.immichgallery.domain.usecase.auth.GetApiKeyUseCase
import com.udnahc.immichgallery.domain.usecase.settings.GetTargetRowHeightUseCase
import com.udnahc.immichgallery.domain.usecase.settings.GetViewConfigUseCase
import com.udnahc.immichgallery.ui.model.UiMessage
import com.udnahc.immichgallery.ui.util.MosaicWorkPriority
import com.udnahc.immichgallery.ui.util.MosaicWorkScheduler
import com.udnahc.immichgallery.ui.util.PhotoGridLayoutRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.lighthousegames.logging.logging

@Immutable
data class AlbumDetailState(
    val albumName: String = "",
    val assets: List<Asset> = emptyList(),
    val availableWidth: Float = 0f,
    val targetRowHeight: Float = DEFAULT_TARGET_ROW_HEIGHT,
    val rowHeightBounds: RowHeightBounds = rowHeightBoundsForViewport(0f),
    val groupSize: GroupSize = GroupSize.MONTH,
    val viewConfig: ViewConfig = ViewConfig(),
    val displayItems: List<PhotoGridDisplayItem> = emptyList(),
    val displayIndex: TimelineDisplayIndex = TimelineDisplayIndex(),
    val isLoading: Boolean = false,
    val error: UiMessage? = null,
    val bannerError: UiMessage? = null,
    val lastSyncedAt: Long? = null,
    val isBuilding: Boolean = false,
    val isSyncing: Boolean = false
)

class AlbumDetailViewModel(
    private val getAlbumDetailUseCase: GetAlbumDetailUseCase,
    getApiKeyUseCase: GetApiKeyUseCase,
    private val getAssetDetailUseCase: GetAssetDetailUseCase,
    private val getTargetRowHeightUseCase: GetTargetRowHeightUseCase,
    private val getViewConfigUseCase: GetViewConfigUseCase,
    private val setTargetRowHeightAction: SetTargetRowHeightAction,
    private val setViewConfigAction: SetViewConfigAction,
    private val mosaicWorkScheduler: MosaicWorkScheduler,
    private val albumId: String
) : ViewModel() {

    val apiKey: String = getApiKeyUseCase()
    var lastViewedAssetId: String? by mutableStateOf(null)

    private val log = logging("AlbumDetailViewModel")
    private var hasSavedTargetRowHeight = getTargetRowHeightUseCase.hasSavedValue(RowHeightScope.ALBUM_DETAIL)
    private var savedTargetRowHeight = getTargetRowHeightUseCase(RowHeightScope.ALBUM_DETAIL)
    private var availableViewportHeight = 0f
    private var mosaicLayoutGeneration = 0L
    private var assetRevision = 0
    private var pendingSyncedContentRevision = false
    private var renderedGroupFingerprints: List<DetailGroupFingerprint>? = null
    private var displayCache: CachedDisplayItems? = null
    private val groupDisplayCache = mutableMapOf<DetailGroupDisplayCacheKey, List<PhotoGridDisplayItem>>()
    private var visibleBucketIndexes: List<Int> = listOf(0)
    private val mosaicOwnerKey = "album:$albumId"
    private val layoutRunner = PhotoGridLayoutRunner(viewModelScope)
    private val rowHeightPersistenceRunner = PhotoGridLayoutRunner(viewModelScope)
    private var syncJob: Job? = null
    private val _state = MutableStateFlow(
        AlbumDetailState(
            targetRowHeight = savedTargetRowHeight,
            viewConfig = getViewConfigUseCase()
        )
    )
    val state: StateFlow<AlbumDetailState> = _state.asStateFlow()

    suspend fun getAssetDetail(assetId: String): Result<AssetDetail> =
        getAssetDetailUseCase(assetId)

    fun activateForegroundMosaic() {
        viewModelScope.launch {
            mosaicWorkScheduler.setActiveForegroundOwner(mosaicOwnerKey)
        }
    }

    fun deactivateForegroundMosaic() {
        viewModelScope.launch {
            mosaicWorkScheduler.clearActiveForegroundOwner(mosaicOwnerKey)
            mosaicWorkScheduler.cancelOwner(mosaicOwnerKey)
        }
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val cachedName = getAlbumDetailUseCase.getCachedAlbumName(albumId)
            if (cachedName != null) {
                _state.update { it.copy(albumName = cachedName) }
            }

            getAlbumDetailUseCase.observeAssets(albumId).collect { assets ->
                log.d { "Room emitted ${assets.size} assets for album $albumId" }
                _state.update { it.copy(assets = assets) }
                handleAssetEmission(assets)
            }
        }

        viewModelScope.launch {
            getViewConfigUseCase.observe().collect { saved ->
                val config = saved.normalized
                if (config != _state.value.viewConfig) {
                    clearLayoutCaches()
                    _state.update { it.copy(viewConfig = config) }
                    scheduleDisplayItems()
                }
            }
        }

        syncFromServer()
    }

    fun setAvailableWidth(widthDp: Float) {
        if (widthDp == _state.value.availableWidth) return
        clearLayoutCaches()
        _state.update { current ->
            current.copy(
                availableWidth = widthDp,
                targetRowHeight = targetRowHeightForConfig(widthDp, current.rowHeightBounds)
            )
        }
        scheduleDisplayItems()
    }

    fun setAvailableViewportHeight(heightDp: Float) {
        if (heightDp == availableViewportHeight) return
        clearLayoutCaches()
        availableViewportHeight = heightDp
        val bounds = rowHeightBoundsForViewport(heightDp)
        _state.update { current ->
            current.copy(
                targetRowHeight = targetRowHeightForConfig(current.availableWidth, bounds),
                rowHeightBounds = bounds
            )
        }
        scheduleDisplayItems()
    }

    fun setTargetRowHeight(height: Float) {
        val clamped = _state.value.rowHeightBounds.clamp(height)
        if (clamped == _state.value.targetRowHeight) return
        clearLayoutCaches()
        hasSavedTargetRowHeight = true
        savedTargetRowHeight = clamped
        rowHeightPersistenceRunner.launch(debounce = true) {
            setTargetRowHeightAction(RowHeightScope.ALBUM_DETAIL, clamped)
        }
        _state.update { it.copy(targetRowHeight = clamped) }
        scheduleDisplayItems(debounce = true)
    }

    private fun targetRowHeightForConfig(availableWidth: Float, bounds: RowHeightBounds): Float {
        val preferred = if (hasSavedTargetRowHeight) {
            savedTargetRowHeight
        } else {
            defaultTargetRowHeightForWidth(availableWidth)
        }
        return bounds.clamp(preferred)
    }

    fun setGroupSize(size: GroupSize) {
        if (size == _state.value.groupSize) return
        clearLayoutCaches()
        _state.update { it.copy(groupSize = size) }
        if (!pendingSyncedContentRevision && _state.value.assets.isNotEmpty()) {
            renderedGroupFingerprints = _state.value.assets.detailGroupFingerprints(size)
        }
        scheduleDisplayItems()
    }

    fun setViewConfig(config: ViewConfig) {
        val normalized = config.normalized
        if (normalized == _state.value.viewConfig) return
        clearLayoutCaches()
        setViewConfigAction(normalized)
        _state.update { it.copy(viewConfig = normalized) }
        scheduleDisplayItems()
    }

    fun setVisibleBucketIndexes(indexes: List<Int>) {
        val next = indexes.distinct().ifEmpty { listOf(0) }
        if (next == visibleBucketIndexes) return
        visibleBucketIndexes = next
        if (_state.value.viewConfig.mosaicEnabled) {
            viewModelScope.launch {
                mosaicWorkScheduler.reprioritizePending(
                    ownerKey = mosaicOwnerKey,
                    visibleRequestKeys = next.map(::mosaicGroupRequestKey).toSet()
                )
            }
        }
    }

    fun refreshAll() {
        syncFromServer()
    }

    fun dismissBannerError() {
        _state.update { it.copy(bannerError = null) }
    }

    private fun scheduleDisplayItems(debounce: Boolean = false) {
        val mosaicGeneration = ++mosaicLayoutGeneration
        val revision = assetRevision
        layoutRunner.launch(debounce = debounce) { generation ->
            val snapshot = _state.value
            val cacheKey = snapshot.displayCacheKey(revision)
            displayCache?.takeIf { it.key == cacheKey }?.let { cached ->
                if (layoutRunner.isCurrent(generation) && revision == assetRevision) {
                    _state.update { current -> current.withDisplayItems(cached.items) }
                }
                return@launch
            }
            val layoutSpec = mosaicLayoutSpecFor(snapshot.availableWidth, snapshot.targetRowHeight)
            if (snapshot.viewConfig.mosaicEnabled &&
                layoutSpec != null &&
                snapshot.assets.isNotEmpty() &&
                snapshot.availableWidth > 0f
            ) {
                buildMosaicDisplayItems(snapshot, generation, mosaicGeneration, revision, cacheKey)
            } else {
                val items = buildDisplayItems(
                    snapshot.assets,
                    snapshot.groupSize,
                    snapshot.availableWidth,
                    snapshot.targetRowHeight,
                    snapshot.rowHeightBounds.max,
                    snapshot.viewConfig
                )
                if (layoutRunner.isCurrent(generation) && revision == assetRevision) {
                    displayCache = CachedDisplayItems(cacheKey, items)
                    _state.update { current -> current.withDisplayItems(items) }
                }
            }
        }
    }

    private suspend fun buildMosaicDisplayItems(
        snapshot: AlbumDetailState,
        runnerGeneration: Long,
        mosaicGeneration: Long,
        revision: Int,
        cacheKey: DetailDisplayCacheKey
    ) {
        val layoutSpec = mosaicLayoutSpecFor(snapshot.availableWidth, snapshot.targetRowHeight) ?: return
        val groups = groupAssets(snapshot.assets, snapshot.groupSize)
        val keyedGroups = groups.mapIndexed { index, group ->
            val indexedGroup = IndexedAssetGroup(index, group.label, group.assets)
            KeyedAssetGroup(
                group = indexedGroup,
                cacheKey = snapshot.groupDisplayCacheKey(
                    bucketIndex = indexedGroup.index,
                    sectionLabel = indexedGroup.label,
                    assets = indexedGroup.assets,
                    mosaicCellHeight = layoutSpec.cellHeight
                )
            )
        }
        val initialItems = keyedGroups.flatMap { keyed ->
            groupDisplayCache[keyed.cacheKey] ?: placeholderItemsForGroup(
                bucketIndex = keyed.group.index,
                sectionLabel = keyed.group.label,
                assets = keyed.group.assets,
                availableWidth = snapshot.availableWidth,
                targetRowHeight = layoutSpec.cellHeight
            )
        }
        if (layoutRunner.isCurrent(runnerGeneration) &&
            mosaicGeneration == mosaicLayoutGeneration &&
            revision == assetRevision
        ) {
            _state.update { current -> current.withDisplayItems(initialItems) }
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
                    ownerKey = mosaicOwnerKey,
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.e(e) { "Mosaic assignments failed for album $albumId section=${group.label}" }
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
            _state.update { current ->
                current.withDisplayItems(replaceGroupItems(current.displayItems, group.index, groupItems))
            }
        }
        if (layoutRunner.isCurrent(runnerGeneration) &&
            mosaicGeneration == mosaicLayoutGeneration &&
            revision == assetRevision
        ) {
            displayCache = CachedDisplayItems(cacheKey, _state.value.displayItems)
        }
    }

    private fun buildDisplayItems(
        assets: List<Asset>,
        groupSize: GroupSize,
        availableWidth: Float,
        targetRowHeight: Float,
        maxRowHeight: Float,
        viewConfig: ViewConfig
    ): List<PhotoGridDisplayItem> {
        if (assets.isEmpty() || availableWidth <= 0f) return emptyList()
        val groups = groupAssets(assets, groupSize)
        val items = mutableListOf<PhotoGridDisplayItem>()
        val mosaicLayoutSpec = mosaicLayoutSpecFor(availableWidth, targetRowHeight)
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
            if (viewConfig.mosaicEnabled && layoutSpec != null) {
                val assignments = buildMosaicAssignments(
                    assets = group.assets,
                    layoutSpec = layoutSpec,
                    spacing = GRID_SPACING_DP,
                    enabledFamilies = viewConfig.mosaicFamilies
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
                        maxRowHeight = maxRowHeight,
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
                        availableWidth = availableWidth,
                        targetRowHeight = if (viewConfig.mosaicEnabled && layoutSpec != null) {
                            mosaicFallbackRowHeight(
                                layoutSpec = layoutSpec,
                                assetCount = group.assets.size,
                                maxRowHeight = maxRowHeight
                            )
                        } else {
                            targetRowHeight
                        },
                        spacing = GRID_SPACING_DP,
                        maxRowHeight = maxRowHeight,
                        promoteWideImages = if (viewConfig.mosaicEnabled) {
                            MOSAIC_FALLBACK_PROMOTE_WIDE_IMAGES
                        } else {
                            true
                        },
                        minCompleteRowPhotos = if (viewConfig.mosaicEnabled) {
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

    override fun onCleared() {
        mosaicWorkScheduler.clearActiveForegroundOwnerAsync(mosaicOwnerKey)
        mosaicWorkScheduler.cancelOwnerAsync(mosaicOwnerKey)
        super.onCleared()
    }

    private data class IndexedAssetGroup(
        val index: Int,
        val label: String,
        val assets: List<Asset>
    )

    private data class KeyedAssetGroup(
        val group: IndexedAssetGroup,
        val cacheKey: DetailGroupDisplayCacheKey
    )

    private fun placeholderItemsForGroup(
        bucketIndex: Int,
        sectionLabel: String,
        assets: List<Asset>,
        availableWidth: Float,
        targetRowHeight: Float
    ): List<PhotoGridDisplayItem> =
        groupHeader(IndexedAssetGroup(bucketIndex, sectionLabel, assets)) +
            buildPhotoGridPlaceholderItems(
                bucketIndex = bucketIndex,
                sectionLabel = sectionLabel,
                assetCount = assets.size,
                availableWidth = availableWidth,
                targetRowHeight = targetRowHeight
            )

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

    private fun syncFromServer() {
        if (syncJob?.isActive == true) return
        syncJob = viewModelScope.launch(Dispatchers.IO) {
            val hasCachedAssets = getAlbumDetailUseCase.hasCachedAssets(albumId)

            if (!hasCachedAssets) {
                _state.update { it.copy(isBuilding = true, error = null) }
            } else {
                _state.update { it.copy(isSyncing = true, bannerError = null) }
            }

            val lastSync = getAlbumDetailUseCase.getLastSyncedAt(albumId)
            _state.update { it.copy(lastSyncedAt = lastSync) }

            getAlbumDetailUseCase.sync(albumId).fold(
                onSuccess = { result ->
                    log.d { "Synced album detail for $albumId" }
                    // Network success and grid-visible content changes are
                    // separate signals. Album-name-only refreshes update the
                    // title while keeping the current packed rows/Mosaic work.
                    handleSyncedContentChange(result.changed)
                    _state.update {
                        it.copy(
                            albumName = result.albumName,
                            isBuilding = false,
                            isSyncing = false,
                            error = null
                        )
                    }
                },
                onFailure = { e ->
                    log.e(e) { "Failed to sync album detail for $albumId" }
                    if (!hasCachedAssets) {
                        _state.update {
                            it.copy(
                                isBuilding = false,
                                isSyncing = false,
                                error = UiMessage.NoConnectionToServer
                            )
                        }
                    } else {
                        _state.update {
                            it.copy(
                                isSyncing = false,
                                bannerError = UiMessage.CannotConnectToServer
                            )
                        }
                    }
                }
            )
        }
    }

    private fun handleAssetEmission(assets: List<Asset>) {
        val fingerprint = assets.detailGroupFingerprints(_state.value.groupSize)
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

    private fun handleSyncedContentChange(changed: Boolean) {
        if (!changed) return
        val fingerprint = _state.value.assets.detailGroupFingerprints(_state.value.groupSize)
        if (fingerprint != renderedGroupFingerprints &&
            (fingerprint.isNotEmpty() || renderedGroupFingerprints != null)
        ) {
            bumpAssetRevision(fingerprint)
            scheduleDisplayItems()
        } else {
            pendingSyncedContentRevision = true
        }
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
        // Group display items carry bucketIndex values. If group order changed,
        // cached rows/Mosaic bands from unchanged labels may point at stale
        // indexes, so drop all group-level cache and rebuild from the new order.
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
    }

    private fun nextMosaicGroup(groups: List<KeyedAssetGroup>): KeyedAssetGroup {
        val visible = visibleBucketIndexes.toSet()
        return groups.minWith(compareBy<KeyedAssetGroup> {
            if (it.group.index in visible) 0 else 1
        }.thenBy { it.group.index })
    }

    private fun mosaicGroupRequestKey(groupIndex: Int): String = "group_$groupIndex"
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

private fun AlbumDetailState.withDisplayItems(items: List<PhotoGridDisplayItem>): AlbumDetailState =
    copy(
        displayItems = items,
        displayIndex = buildPhotoGridDisplayIndex(items)
    )

private fun AlbumDetailState.displayCacheKey(assetRevision: Int): DetailDisplayCacheKey =
    DetailDisplayCacheKey(
        availableWidth = availableWidth,
        targetRowHeight = targetRowHeight,
        maxRowHeight = rowHeightBounds.max,
        groupSize = groupSize,
        viewConfig = viewConfig.normalized,
        assetRevision = assetRevision
    )

private fun AlbumDetailState.groupDisplayCacheKey(
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
