package com.udnahc.immichgallery.ui.screen.detail

import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.AssetType
import com.udnahc.immichgallery.domain.model.DEFAULT_TARGET_ROW_HEIGHT
import com.udnahc.immichgallery.domain.model.DetailMosaicCacheEntry
import com.udnahc.immichgallery.domain.model.DetailMosaicCacheLookup
import com.udnahc.immichgallery.domain.model.DetailMosaicCacheOwnerType
import com.udnahc.immichgallery.domain.model.GroupSize
import com.udnahc.immichgallery.domain.model.PhotoGridDisplayItem
import com.udnahc.immichgallery.domain.model.PlaceholderItem
import com.udnahc.immichgallery.domain.model.RowHeightBounds
import com.udnahc.immichgallery.domain.model.RowHeightScope
import com.udnahc.immichgallery.domain.model.TimelineDisplayIndex
import com.udnahc.immichgallery.domain.model.ViewConfig
import com.udnahc.immichgallery.domain.model.buildPhotoGridDisplayIndex
import com.udnahc.immichgallery.domain.model.rowHeightBoundsForViewport
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
            readPersistentCache = {
                listOf(
                    detailCacheEntry(
                        sectionIndex = 2,
                        sectionKey = "January 2026",
                        assets = assets.filter { it.id.startsWith("jan_") },
                        placeholderHeight = cachedHeight
                    )
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

    private fun coordinator(
        scope: CoroutineScope,
        scheduler: TestCoroutineScheduler,
        getState: () -> TestDetailState,
        updateState: ((TestDetailState) -> TestDetailState) -> Unit,
        cacheOwnerType: DetailMosaicCacheOwnerType? = null,
        isPersistentCacheComplete: () -> Boolean = { true },
        readPersistentCache: suspend (DetailMosaicCacheLookup) -> List<DetailMosaicCacheEntry> = { emptyList() },
        upsertPersistentCache: suspend (DetailMosaicCacheEntry) -> Unit = {}
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
            layoutRunner = PhotoGridLayoutRunner(scope, StandardTestDispatcher(scheduler)),
            rowHeightPersistenceRunner = PhotoGridLayoutRunner(scope, StandardTestDispatcher(scheduler))
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
        placeholderHeight: Float
    ): DetailMosaicCacheEntry =
        DetailMosaicCacheEntry(
            ownerType = DetailMosaicCacheOwnerType.ALBUM,
            ownerId = "test",
            groupSize = GroupSize.MONTH,
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
