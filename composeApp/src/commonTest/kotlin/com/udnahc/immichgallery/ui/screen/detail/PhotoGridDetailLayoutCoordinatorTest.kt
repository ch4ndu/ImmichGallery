package com.udnahc.immichgallery.ui.screen.detail

import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.AssetType
import com.udnahc.immichgallery.domain.model.DEFAULT_TARGET_ROW_HEIGHT
import com.udnahc.immichgallery.domain.model.DetailMosaicAggregateGeometryEntry
import com.udnahc.immichgallery.domain.model.DetailMosaicArtifacts
import com.udnahc.immichgallery.domain.model.DetailMosaicArtifactsUpsert
import com.udnahc.immichgallery.domain.model.DetailMosaicAssignmentEntry
import com.udnahc.immichgallery.domain.model.DetailMosaicCacheEntry
import com.udnahc.immichgallery.domain.model.DetailMosaicCacheLookup
import com.udnahc.immichgallery.domain.model.DetailMosaicCacheOwnerType
import com.udnahc.immichgallery.domain.model.DetailMosaicSectionGeometryEntry
import com.udnahc.immichgallery.domain.model.GroupSize
import com.udnahc.immichgallery.domain.model.MosaicBandItem
import com.udnahc.immichgallery.domain.model.MosaicAssignmentCheckpoint
import com.udnahc.immichgallery.domain.model.MosaicBandAssignment
import com.udnahc.immichgallery.domain.model.MosaicBandKind
import com.udnahc.immichgallery.domain.model.MosaicLayoutSpec
import com.udnahc.immichgallery.domain.model.MosaicOwnerKey
import com.udnahc.immichgallery.domain.model.MosaicRenderEngine
import com.udnahc.immichgallery.domain.model.MosaicSectionComputer
import com.udnahc.immichgallery.domain.model.MosaicSectionRequest
import com.udnahc.immichgallery.domain.model.MosaicSectionResult
import com.udnahc.immichgallery.domain.model.PhotoGridDisplayItem
import com.udnahc.immichgallery.domain.model.PlaceholderItem
import com.udnahc.immichgallery.domain.model.ProgressChunk
import com.udnahc.immichgallery.domain.model.RowHeightBounds
import com.udnahc.immichgallery.domain.model.RowHeightScope
import com.udnahc.immichgallery.domain.model.AggregateGeometry
import com.udnahc.immichgallery.domain.model.SectionGeometry
import com.udnahc.immichgallery.domain.model.TimelineDisplayIndex
import com.udnahc.immichgallery.domain.model.ViewConfig
import com.udnahc.immichgallery.domain.model.buildPhotoGridDisplayIndex
import com.udnahc.immichgallery.domain.model.rowHeightBoundsForViewport
import com.udnahc.immichgallery.ui.util.MosaicWorkCancelledException
import com.udnahc.immichgallery.ui.util.MosaicWorkScheduler
import com.udnahc.immichgallery.ui.util.PhotoGridLayoutRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PhotoGridDetailLayoutCoordinatorTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun unchangedSyncDoesNotRebuildDisplayItems() = runTest {
        var state = coordinatorState(assets = listOf(asset("a")))
        val coordinator = coordinator(
            scope = this,
            scheduler = testScheduler,
            getState = { state },
            updateState = { transform -> state = transform(state) }
        )

        coordinator.setAvailableViewportHeight(600f)
        coordinator.setAvailableWidth(360f)
        coordinator.handleAssetEmission(state.assets)
        advanceUntilIdle()
        val rendered = state.displayItems

        coordinator.handleSyncedContentChange(changed = false)
        advanceUntilIdle()

        assertEquals(rendered, state.displayItems)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun changedSyncRebuildsDisplayItemsForNewAssets() = runTest {
        var state = coordinatorState(assets = listOf(asset("a")))
        val coordinator = coordinator(
            scope = this,
            scheduler = testScheduler,
            getState = { state },
            updateState = { transform -> state = transform(state) }
        )

        coordinator.setAvailableViewportHeight(600f)
        coordinator.setAvailableWidth(360f)
        coordinator.handleAssetEmission(state.assets)
        advanceUntilIdle()
        val initialItems = state.displayItems

        state = state.copy(assets = listOf(asset("a"), asset("b")))
        coordinator.handleSyncedContentChange(changed = true)
        advanceUntilIdle()

        assertNotEquals(initialItems, state.displayItems)
        assertEquals(1, state.displayIndex.assetDisplayIndexById["b"])
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun persistentCacheIsReadWhenOwnerSnapshotIsComplete() = runTest {
        var readCount = 0
        var state = coordinatorState(
            assets = listOf(asset("a"), asset("b"), asset("c"), asset("d")),
            viewConfig = ViewConfig(mosaicEnabled = true)
        )
        val coordinator = coordinator(
            scope = this,
            scheduler = testScheduler,
            getState = { state },
            updateState = { transform -> state = transform(state) },
            cacheOwnerType = DetailMosaicCacheOwnerType.ALBUM,
            isPersistentCacheComplete = { true },
            readPersistentCache = {
                readCount++
                emptyList()
            }
        )

        coordinator.setAvailableViewportHeight(600f)
        coordinator.setAvailableWidth(360f)
        coordinator.handleAssetEmission(state.assets)
        advanceUntilIdle()

        assertEquals(1, readCount)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun persistentCacheIsSkippedWhenOwnerSnapshotIsIncomplete() = runTest {
        var readCount = 0
        var state = coordinatorState(
            assets = listOf(asset("a"), asset("b"), asset("c"), asset("d")),
            viewConfig = ViewConfig(mosaicEnabled = true)
        )
        val coordinator = coordinator(
            scope = this,
            scheduler = testScheduler,
            getState = { state },
            updateState = { transform -> state = transform(state) },
            cacheOwnerType = DetailMosaicCacheOwnerType.PERSON,
            isPersistentCacheComplete = { false },
            readPersistentCache = {
                readCount++
                emptyList()
            }
        )

        coordinator.setAvailableViewportHeight(600f)
        coordinator.setAvailableWidth(360f)
        coordinator.handleAssetEmission(state.assets)
        advanceUntilIdle()

        assertEquals(0, readCount)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun persistentCacheWriteRunsWhenOwnerSnapshotIsComplete() = runTest {
        var writeCount = 0
        var state = coordinatorState(
            assets = List(6) { index -> asset("a$index") },
            viewConfig = ViewConfig(mosaicEnabled = true)
        )
        val coordinator = coordinator(
            scope = this,
            scheduler = testScheduler,
            getState = { state },
            updateState = { transform -> state = transform(state) },
            cacheOwnerType = DetailMosaicCacheOwnerType.ALBUM,
            isPersistentCacheComplete = { true },
            upsertPersistentCache = {
                writeCount++
            }
        )

        coordinator.setAvailableViewportHeight(600f)
        coordinator.setAvailableWidth(360f)
        coordinator.handleAssetEmission(state.assets)
        advanceUntilIdle()

        assertEquals(1, writeCount)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun persistentCacheWriteIsSkippedWhenOwnerSnapshotIsIncomplete() = runTest {
        var writeCount = 0
        var state = coordinatorState(
            assets = List(6) { index -> asset("a$index") },
            viewConfig = ViewConfig(mosaicEnabled = true)
        )
        val coordinator = coordinator(
            scope = this,
            scheduler = testScheduler,
            getState = { state },
            updateState = { transform -> state = transform(state) },
            cacheOwnerType = DetailMosaicCacheOwnerType.PERSON,
            isPersistentCacheComplete = { false },
            upsertPersistentCache = {
                writeCount++
            }
        )

        coordinator.setAvailableViewportHeight(600f)
        coordinator.setAvailableWidth(360f)
        coordinator.handleAssetEmission(state.assets)
        advanceUntilIdle()

        assertEquals(0, writeCount)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun offscreenRuntimeMosaicGroupsAreDeferredWhileScrolling() = runTest {
        var state = coordinatorState(
            assets = multiMonthAssets(),
            viewConfig = ViewConfig(mosaicEnabled = true)
        )
        val coordinator = coordinator(
            scope = this,
            scheduler = testScheduler,
            getState = { state },
            updateState = { transform -> state = transform(state) }
        )

        coordinator.setScrollInProgress(true)
        coordinator.setVisibleBucketIndexes(listOf(0))
        coordinator.setAvailableViewportHeight(600f)
        coordinator.setAvailableWidth(360f)
        coordinator.handleAssetEmission(state.assets)
        advanceUntilIdle()

        assertEquals(true, state.displayItems.any { it is PlaceholderItem && it.bucketIndex == 2 })

        coordinator.setScrollInProgress(false)
        advanceTimeBy(120)
        advanceUntilIdle()

        assertEquals(false, state.displayItems.any { it is PlaceholderItem && it.bucketIndex == 2 })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun activeScrollComputesOnlyVisibleRuntimeMosaicGroups() = runTest {
        val mosaicComputer = RecordingMosaicSectionComputer()
        var state = coordinatorState(
            assets = multiMonthAssets(),
            viewConfig = ViewConfig(mosaicEnabled = true, cacheMosaicResults = false)
        )
        val coordinator = coordinator(
            scope = this,
            scheduler = testScheduler,
            getState = { state },
            updateState = { transform -> state = transform(state) },
            mosaicEngine = mosaicComputer
        )

        coordinator.setScrollInProgress(true)
        coordinator.setVisibleBucketIndexes(listOf(0))
        coordinator.setAvailableViewportHeight(600f)
        coordinator.setAvailableWidth(360f)
        coordinator.handleAssetEmission(state.assets)
        advanceUntilIdle()

        assertEquals(listOf(0), mosaicComputer.groupIndexes)

        coordinator.setScrollInProgress(false)
        advanceUntilIdle()

        assertEquals(listOf(0, 1, 2), mosaicComputer.groupIndexes)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun offscreenRuntimePlaceholderUsesCachedHeightWhenAvailable() = runTest {
        val cachedHeight = 777f
        val assets = multiMonthAssets()
        var state = coordinatorState(
            assets = assets,
            viewConfig = ViewConfig(mosaicEnabled = true, cacheMosaicResults = true)
        )
        val coordinator = coordinator(
            scope = this,
            scheduler = testScheduler,
            getState = { state },
            updateState = { transform -> state = transform(state) },
            cacheOwnerType = DetailMosaicCacheOwnerType.ALBUM,
            readPersistentArtifacts = {
                detailArtifacts(
                    ownerAssets = assets,
                    sectionIndex = 2,
                    sectionKey = "January 2026",
                    sectionAssets = assets.filter { it.id.startsWith("jan_") },
                    placeholderHeight = cachedHeight
                )
            }
        )

        coordinator.setScrollInProgress(true)
        coordinator.setVisibleBucketIndexes(listOf(0))
        coordinator.setAvailableViewportHeight(600f)
        coordinator.setAvailableWidth(360f)
        coordinator.handleAssetEmission(state.assets)
        advanceUntilIdle()

        val placeholder = state.displayItems
            .filterIsInstance<PlaceholderItem>()
            .first { it.bucketIndex == 2 }
        assertEquals(cachedHeight, placeholder.estimatedHeight)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun cacheDisabledDoesNotReadPersistentArtifactsAndStillComputesRuntimeMosaic() = runTest {
        var readCount = 0
        var writeCount = 0
        var state = coordinatorState(
            assets = List(8) { index -> asset("a$index") },
            viewConfig = ViewConfig(mosaicEnabled = true, cacheMosaicResults = false)
        )
        val coordinator = coordinator(
            scope = this,
            scheduler = testScheduler,
            getState = { state },
            updateState = { transform -> state = transform(state) },
            cacheOwnerType = DetailMosaicCacheOwnerType.ALBUM,
            readPersistentArtifacts = {
                readCount++
                DetailMosaicArtifacts()
            },
            upsertPersistentArtifacts = {
                writeCount++
            }
        )

        coordinator.setAvailableViewportHeight(600f)
        coordinator.setAvailableWidth(360f)
        coordinator.handleAssetEmission(state.assets)
        advanceUntilIdle()

        assertEquals(0, readCount)
        assertEquals(0, writeCount)
        assertEquals(true, state.displayItems.hasFallbackMosaicBands())
        assertTrue(state.displayItems.any { it is MosaicBandItem || it is PlaceholderItem })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun incompleteRuntimeMosaicGroupResumesFromCheckpointWhenScrollSettles() = runTest {
        val mosaicComputer = CancelOnceMosaicSectionComputer()
        var state = coordinatorState(
            assets = List(12) { index -> asset("a$index") },
            viewConfig = ViewConfig(mosaicEnabled = true, cacheMosaicResults = false)
        )
        val coordinator = coordinator(
            scope = this,
            scheduler = testScheduler,
            getState = { state },
            updateState = { transform -> state = transform(state) },
            mosaicEngine = mosaicComputer
        )

        coordinator.setScrollInProgress(true)
        coordinator.setAvailableViewportHeight(600f)
        coordinator.setAvailableWidth(360f)
        coordinator.handleAssetEmission(state.assets)
        advanceUntilIdle()

        assertEquals(2, mosaicComputer.computeCalls)
        assertEquals(true, mosaicComputer.receivedResumeCheckpoint)

        coordinator.setScrollInProgress(false)
        advanceUntilIdle()

        assertEquals(2, mosaicComputer.computeCalls)
        assertEquals(true, mosaicComputer.receivedResumeCheckpoint)
        assertEquals(true, state.displayItems.hasFallbackMosaicBands())
        assertTrue(state.displayItems.any { it is MosaicBandItem || it is PlaceholderItem })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun retryableRuntimeFallbackIsRecomputedInsteadOfBecomingFinalDisplayCache() = runTest {
        val mosaicComputer = FailOnceMosaicSectionComputer()
        var state = coordinatorState(
            assets = List(12) { index -> asset("a$index") },
            viewConfig = ViewConfig(mosaicEnabled = true, cacheMosaicResults = false)
        )
        val coordinator = coordinator(
            scope = this,
            scheduler = testScheduler,
            getState = { state },
            updateState = { transform -> state = transform(state) },
            mosaicEngine = mosaicComputer
        )

        coordinator.setScrollInProgress(true)
        coordinator.setAvailableViewportHeight(600f)
        coordinator.setAvailableWidth(360f)
        coordinator.handleAssetEmission(state.assets)
        advanceUntilIdle()

        assertEquals(2, mosaicComputer.computeCalls)
        assertEquals(true, state.displayItems.hasFallbackMosaicBands())

        coordinator.setScrollInProgress(false)
        advanceUntilIdle()

        assertEquals(2, mosaicComputer.computeCalls)
        assertEquals(true, state.displayItems.hasFallbackMosaicBands())
        assertTrue(state.displayItems.any { it is MosaicBandItem || it is PlaceholderItem })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun stalePersistentArtifactsForAnotherColumnCountDoNotProvidePlaceholderHeight() = runTest {
        val staleHeight = 777f
        val assets = multiMonthAssets()
        var state = coordinatorState(
            assets = assets,
            viewConfig = ViewConfig(mosaicEnabled = true, cacheMosaicResults = true, mosaicColumnCount = 4)
        )
        val coordinator = coordinator(
            scope = this,
            scheduler = testScheduler,
            getState = { state },
            updateState = { transform -> state = transform(state) },
            cacheOwnerType = DetailMosaicCacheOwnerType.ALBUM,
            readPersistentArtifacts = {
                detailArtifacts(
                    ownerAssets = assets,
                    sectionIndex = 2,
                    sectionKey = "January 2026",
                    sectionAssets = assets.filter { it.id.startsWith("jan_") },
                    placeholderHeight = staleHeight,
                    columnCount = 3
                )
            }
        )

        coordinator.setScrollInProgress(true)
        coordinator.setVisibleBucketIndexes(listOf(0))
        coordinator.setAvailableViewportHeight(600f)
        coordinator.setAvailableWidth(360f)
        coordinator.handleAssetEmission(state.assets)
        advanceUntilIdle()

        val placeholder = state.displayItems
            .filterIsInstance<PlaceholderItem>()
            .first { it.bucketIndex == 2 }
        assertNotEquals(staleHeight, placeholder.estimatedHeight)
    }

    private fun coordinator(
        scope: CoroutineScope,
        scheduler: TestCoroutineScheduler,
        getState: () -> TestDetailState,
        updateState: ((TestDetailState) -> TestDetailState) -> Unit,
        cacheOwnerType: DetailMosaicCacheOwnerType? = null,
        isPersistentCacheComplete: () -> Boolean = { true },
        readPersistentCache: suspend (DetailMosaicCacheLookup) -> List<DetailMosaicCacheEntry> = { emptyList() },
        upsertPersistentCache: suspend (DetailMosaicCacheEntry) -> Unit = {},
        readPersistentArtifacts: suspend (DetailMosaicCacheLookup) -> DetailMosaicArtifacts = { lookup ->
            DetailMosaicArtifacts(displayCache = readPersistentCache(lookup))
        },
        upsertPersistentArtifacts: suspend (DetailMosaicArtifactsUpsert) -> Unit = { artifacts ->
            artifacts.displayCache.forEach { upsertPersistentCache(it) }
        },
        mosaicEngine: MosaicSectionComputer = MosaicRenderEngine()
    ): PhotoGridDetailLayoutCoordinator<TestDetailState> =
        PhotoGridDetailLayoutCoordinator(
            scope = scope,
            hasSavedTargetRowHeight = true,
            savedTargetRowHeight = DEFAULT_TARGET_ROW_HEIGHT,
            targetRowHeightScope = RowHeightScope.ALBUM_DETAIL,
            ownerKey = "test",
            logTag = "PhotoGridDetailLayoutCoordinatorTest",
            mosaicWorkScheduler = MosaicWorkScheduler(scope = scope),
            getState = getState,
            updateState = updateState,
            snapshotOf = { it.asCoordinatorSnapshot() },
            withAvailableWidth = { state, width, target -> state.copy(availableWidth = width, targetRowHeight = target) },
            withViewportHeight = { state, target, bounds -> state.copy(targetRowHeight = target, rowHeightBounds = bounds) },
            withTargetRowHeight = { state, target -> state.copy(targetRowHeight = target) },
            withGroupSize = { state, groupSize -> state.copy(groupSize = groupSize) },
            withViewConfig = { state, viewConfig -> state.copy(viewConfig = viewConfig) },
            withDisplayItems = { state, items -> state.withDisplayItems(items) },
            persistTargetRowHeight = { _, _ -> },
            persistViewConfig = {},
            cacheOwnerType = cacheOwnerType,
            cacheOwnerId = "test",
            isPersistentCacheComplete = isPersistentCacheComplete,
            readPersistentCache = readPersistentCache,
            upsertPersistentCache = upsertPersistentCache,
            readPersistentArtifacts = { lookup, _ -> readPersistentArtifacts(lookup) },
            upsertPersistentArtifacts = upsertPersistentArtifacts,
            mosaicEngine = mosaicEngine,
            layoutRunner = PhotoGridLayoutRunner(scope, StandardTestDispatcher(scheduler)),
            rowHeightPersistenceRunner = PhotoGridLayoutRunner(scope, StandardTestDispatcher(scheduler)),
            backgroundDispatcher = StandardTestDispatcher(scheduler),
            ioDispatcher = StandardTestDispatcher(scheduler)
        )

    private fun TestDetailState.asCoordinatorSnapshot(): PhotoGridDetailLayoutSnapshot =
        PhotoGridDetailLayoutSnapshot(
            assets = assets,
            availableWidth = availableWidth,
            targetRowHeight = targetRowHeight,
            rowHeightBounds = rowHeightBounds,
            groupSize = groupSize,
            viewConfig = viewConfig,
            displayItems = displayItems
        )

    private fun TestDetailState.withDisplayItems(items: List<PhotoGridDisplayItem>): TestDetailState =
        copy(
            displayItems = items,
            displayIndex = buildPhotoGridDisplayIndex(items)
        )

    private fun coordinatorState(
        assets: List<Asset>,
        viewConfig: ViewConfig = ViewConfig(mosaicEnabled = false)
    ): TestDetailState =
        TestDetailState(assets = assets, viewConfig = viewConfig)

    private fun multiMonthAssets(): List<Asset> =
        listOf(
            List(6) { index -> asset("jan_$index", createdAt = "2026-01-15T12:00:00Z") },
            List(6) { index -> asset("feb_$index", createdAt = "2026-02-15T12:00:00Z") },
            List(6) { index -> asset("mar_$index", createdAt = "2026-03-15T12:00:00Z") }
        ).flatten()

    private fun detailCacheEntry(
        sectionIndex: Int,
        sectionKey: String,
        assets: List<Asset>,
        placeholderHeight: Float,
        columnCount: Int = 4
    ): DetailMosaicCacheEntry =
        DetailMosaicCacheEntry(
            ownerType = DetailMosaicCacheOwnerType.ALBUM,
            ownerId = "test",
            groupSize = GroupSize.MONTH,
            columnCount = columnCount,
            sectionIndex = sectionIndex,
            sectionKey = sectionKey,
            familiesKey = "four|five|six",
            assetFingerprint = assets.detailPersistentFingerprint(),
            availableWidthKey = 360,
            cellHeightKey = 6000,
            maxRowHeightKey = 60000,
            spacingKey = 400,
            displayVersion = 1,
            bands = emptyList(),
            displayItemCount = 1,
            placeholderHeight = placeholderHeight,
            updatedAt = 0L
        )

    private fun detailArtifacts(
        ownerAssets: List<Asset>,
        sectionIndex: Int,
        sectionKey: String,
        sectionAssets: List<Asset>,
        placeholderHeight: Float,
        columnCount: Int = 4
    ): DetailMosaicArtifacts {
        val sectionFingerprint = sectionAssets.detailPersistentFingerprint()
        return DetailMosaicArtifacts(
            assignments = listOf(
                DetailMosaicAssignmentEntry(
                    ownerType = DetailMosaicCacheOwnerType.ALBUM,
                    ownerId = "test",
                    groupSize = GroupSize.MONTH,
                    columnCount = columnCount,
                    sectionIndex = sectionIndex,
                    sectionKey = sectionKey,
                    familiesKey = "four|five|six",
                    assetFingerprint = sectionFingerprint,
                    assignments = emptyList(),
                    updatedAt = 0L
                )
            ),
            displayCache = listOf(
                detailCacheEntry(
                    sectionIndex = sectionIndex,
                    sectionKey = sectionKey,
                    assets = sectionAssets,
                    placeholderHeight = placeholderHeight,
                    columnCount = columnCount
                )
            ),
            sectionGeometry = listOf(
                DetailMosaicSectionGeometryEntry(
                    ownerType = DetailMosaicCacheOwnerType.ALBUM,
                    ownerId = "test",
                    groupSize = GroupSize.MONTH,
                    columnCount = columnCount,
                    sectionIndex = sectionIndex,
                    sectionKey = sectionKey,
                    familiesKey = "four|five|six",
                    assetFingerprint = sectionFingerprint,
                    availableWidthKey = 360,
                    cellHeightKey = 6000,
                    maxRowHeightKey = 60000,
                    spacingKey = 400,
                    geometryVersion = 1,
                    placeholderHeight = placeholderHeight,
                    displayItemCount = 1,
                    updatedAt = 0L
                )
            ),
            aggregateGeometry = DetailMosaicAggregateGeometryEntry(
                ownerType = DetailMosaicCacheOwnerType.ALBUM,
                ownerId = "test",
                groupSize = GroupSize.MONTH,
                columnCount = columnCount,
                familiesKey = "four|five|six",
                assetFingerprint = ownerAssets.detailPersistentFingerprint(),
                availableWidthKey = 360,
                cellHeightKey = 6000,
                maxRowHeightKey = 60000,
                spacingKey = 400,
                geometryVersion = 1,
                placeholderHeight = placeholderHeight,
                displayItemCount = 1,
                updatedAt = 0L
            )
        )
    }

    private fun List<Asset>.detailPersistentFingerprint(): String {
        var hash = -3750763034362895579L
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
            hash *= 1099511628211L
        }
        hash = hash xor '|'.code.toLong()
        hash *= 1099511628211L
        return hash
    }

    private fun asset(
        id: String,
        createdAt: String = "2026-01-01T00:00:00Z"
    ): Asset =
        Asset(
            id = id,
            type = AssetType.IMAGE,
            fileName = "$id.jpg",
            createdAt = createdAt,
            thumbnailUrl = "thumb/$id",
            originalUrl = "original/$id",
            aspectRatio = 1f
        )
}

private data class TestDetailState(
    val assets: List<Asset>,
    val availableWidth: Float = 0f,
    val targetRowHeight: Float = DEFAULT_TARGET_ROW_HEIGHT,
    val rowHeightBounds: RowHeightBounds = rowHeightBoundsForViewport(0f),
    val groupSize: GroupSize = GroupSize.MONTH,
    val viewConfig: ViewConfig = ViewConfig(mosaicEnabled = false),
    val displayItems: List<PhotoGridDisplayItem> = emptyList(),
    val displayIndex: TimelineDisplayIndex = TimelineDisplayIndex()
)

private class CancelOnceMosaicSectionComputer : DelegatingTestMosaicSectionComputer() {
    var computeCalls = 0
        private set
    var receivedResumeCheckpoint = false
        private set

    override suspend fun computeSection(
        request: MosaicSectionRequest,
        resumeCheckpoint: MosaicAssignmentCheckpoint?,
        onProgressChunk: suspend (ProgressChunk) -> Unit,
        onCheckpoint: suspend (MosaicAssignmentCheckpoint) -> Unit,
        shouldContinue: () -> Unit
    ): MosaicSectionResult {
        computeCalls++
        receivedResumeCheckpoint = receivedResumeCheckpoint || resumeCheckpoint != null
        if (computeCalls == 1) {
            emitPartial(request, onCheckpoint, onProgressChunk)
            throw MosaicWorkCancelledException("test cancellation")
        }
        return super.computeSection(request, resumeCheckpoint, onProgressChunk, onCheckpoint, shouldContinue)
    }
}

private class FailOnceMosaicSectionComputer : DelegatingTestMosaicSectionComputer() {
    var computeCalls = 0
        private set

    override suspend fun computeSection(
        request: MosaicSectionRequest,
        resumeCheckpoint: MosaicAssignmentCheckpoint?,
        onProgressChunk: suspend (ProgressChunk) -> Unit,
        onCheckpoint: suspend (MosaicAssignmentCheckpoint) -> Unit,
        shouldContinue: () -> Unit
    ): MosaicSectionResult {
        computeCalls++
        if (computeCalls == 1) {
            emitPartial(request, onCheckpoint, onProgressChunk)
            throw IllegalStateException("test failure")
        }
        return super.computeSection(request, resumeCheckpoint, onProgressChunk, onCheckpoint, shouldContinue)
    }
}

private class RecordingMosaicSectionComputer : DelegatingTestMosaicSectionComputer() {
    val groupIndexes = mutableListOf<Int>()

    override suspend fun computeSection(
        request: MosaicSectionRequest,
        resumeCheckpoint: MosaicAssignmentCheckpoint?,
        onProgressChunk: suspend (ProgressChunk) -> Unit,
        onCheckpoint: suspend (MosaicAssignmentCheckpoint) -> Unit,
        shouldContinue: () -> Unit
    ): MosaicSectionResult {
        groupIndexes.add(request.bucketIndex)
        return super.computeSection(request, resumeCheckpoint, onProgressChunk, onCheckpoint, shouldContinue)
    }
}

private fun List<PhotoGridDisplayItem>.hasFallbackMosaicBands(): Boolean =
    any { item -> item is MosaicBandItem && item.kind == MosaicBandKind.FALLBACK }

private open class DelegatingTestMosaicSectionComputer : MosaicSectionComputer {
    private val delegate = MosaicRenderEngine()

    protected suspend fun emitPartial(
        request: MosaicSectionRequest,
        onCheckpoint: suspend (MosaicAssignmentCheckpoint) -> Unit,
        onProgressChunk: suspend (ProgressChunk) -> Unit
    ) {
        val sourceEnd = minOf(4, request.assets.size)
        val checkpoint = MosaicAssignmentCheckpoint(
            assignments = emptyList(),
            sourceIndex = sourceEnd,
            bandIndex = 0,
            chunkStartIndex = sourceEnd,
            lastEmittedBandCount = 0
        )
        onCheckpoint(checkpoint)
        onProgressChunk(
            ProgressChunk(
                keyScope = request.keyScope,
                sectionLabel = request.sectionLabel,
                sourceStartIndex = 0,
                sourceEndExclusive = sourceEnd,
                assignments = emptyList()
            )
        )
    }

    override suspend fun computeSection(
        request: MosaicSectionRequest,
        resumeCheckpoint: MosaicAssignmentCheckpoint?,
        onProgressChunk: suspend (ProgressChunk) -> Unit,
        onCheckpoint: suspend (MosaicAssignmentCheckpoint) -> Unit,
        shouldContinue: () -> Unit
    ): MosaicSectionResult =
        delegate.computeSection(request, resumeCheckpoint, onProgressChunk, onCheckpoint, shouldContinue)

    override fun projectPartialSectionWithPlaceholders(
        assets: List<Asset>,
        chunks: List<ProgressChunk>,
        bucketIndex: Int,
        sectionLabel: String,
        layoutSpec: MosaicLayoutSpec,
        spacing: Float,
        maxRowHeight: Float
    ): List<PhotoGridDisplayItem> =
        delegate.projectPartialSectionWithPlaceholders(
            assets = assets,
            chunks = chunks,
            bucketIndex = bucketIndex,
            sectionLabel = sectionLabel,
            layoutSpec = layoutSpec,
            spacing = spacing,
            maxRowHeight = maxRowHeight
        )

    override fun projectSection(
        assets: List<Asset>,
        assignments: List<MosaicBandAssignment>,
        bucketIndex: Int,
        sectionLabel: String,
        layoutSpec: MosaicLayoutSpec,
        spacing: Float,
        maxRowHeight: Float
    ): List<PhotoGridDisplayItem> =
        delegate.projectSection(assets, assignments, bucketIndex, sectionLabel, layoutSpec, spacing, maxRowHeight)

    override fun projectReadySection(
        assets: List<Asset>,
        assignments: List<MosaicBandAssignment>,
        bucketIndex: Int,
        sectionLabel: String,
        layoutSpec: MosaicLayoutSpec,
        spacing: Float,
        maxRowHeight: Float
    ): List<PhotoGridDisplayItem> =
        delegate.projectReadySection(assets, assignments, bucketIndex, sectionLabel, layoutSpec, spacing, maxRowHeight)

    override fun projectPartialSection(
        assets: List<Asset>,
        chunks: List<ProgressChunk>,
        bucketIndex: Int,
        sectionLabel: String,
        layoutSpec: MosaicLayoutSpec,
        spacing: Float,
        maxRowHeight: Float
    ): List<PhotoGridDisplayItem> =
        delegate.projectPartialSection(assets, chunks, bucketIndex, sectionLabel, layoutSpec, spacing, maxRowHeight)

    override fun computeSectionGeometry(
        keyScope: com.udnahc.immichgallery.domain.model.MosaicKeyScope,
        displayItems: List<PhotoGridDisplayItem>,
        spacing: Float
    ): SectionGeometry =
        delegate.computeSectionGeometry(keyScope, displayItems, spacing)

    override fun computeAggregateGeometry(
        owner: MosaicOwnerKey,
        key: String,
        sectionGeometries: List<SectionGeometry>,
        headerCount: Int,
        headerEstimatedHeight: Float,
        spacing: Float
    ): AggregateGeometry =
        delegate.computeAggregateGeometry(owner, key, sectionGeometries, headerCount, headerEstimatedHeight, spacing)
}
