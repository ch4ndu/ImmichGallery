package com.udnahc.immichgallery.ui.screen.detail

import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.AssetType
import com.udnahc.immichgallery.domain.model.DEFAULT_TARGET_ROW_HEIGHT
import com.udnahc.immichgallery.domain.model.GroupSize
import com.udnahc.immichgallery.domain.model.PhotoGridDisplayItem
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

    private fun coordinator(
        scope: CoroutineScope,
        scheduler: TestCoroutineScheduler,
        getState: () -> TestDetailState,
        updateState: ((TestDetailState) -> TestDetailState) -> Unit
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

    private fun coordinatorState(assets: List<Asset>): TestDetailState =
        TestDetailState(assets = assets)

    private fun asset(id: String): Asset =
        Asset(
            id = id,
            type = AssetType.IMAGE,
            fileName = "$id.jpg",
            createdAt = "2026-01-01T00:00:00Z",
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
