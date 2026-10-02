package com.udnahc.immichgallery.ui.component

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PhotoOverlayHostTest {
    @Test
    fun staticSourceResolvesFoundBeforePendingAndAbsentOnlyWhenAuthoritative() {
        val pending = StaticPhotoOverlaySourceIdentity(
            ownerKey = "album:one",
            assetIds = listOf("first", "requested"),
            isPending = true
        )

        assertEquals(
            PhotoOverlayInitialIndexResolution.Found(1),
            resolveStaticPhotoOverlayInitialIndex(pending, "requested")
        )
        assertEquals(
            PhotoOverlayInitialIndexResolution.Pending,
            resolveStaticPhotoOverlayInitialIndex(pending, "missing")
        )
        assertEquals(
            PhotoOverlayInitialIndexResolution.Absent,
            resolveStaticPhotoOverlayInitialIndex(
                pending.copy(isPending = false),
                "missing"
            )
        )
    }

    @Test
    fun staticSourceIdentityChangesForReadinessAndOwnerTransitions() {
        val pending = StaticPhotoOverlaySourceIdentity(
            ownerKey = "album:one",
            assetIds = emptyList(),
            isPending = true
        )

        assertNotEquals(pending, pending.copy(isPending = false))
        assertNotEquals(pending, pending.copy(ownerKey = "album:two"))
    }

    @Test
    fun sourceRefreshRetainsTheSettledCurrentAsset() {
        assertEquals(
            "paged-to",
            retainedCurrentViewedAssetId(
                currentAssetId = "paged-to",
                requestedAssetId = "origin"
            )
        )
        assertEquals(
            "origin",
            retainedCurrentViewedAssetId(
                currentAssetId = null,
                requestedAssetId = "origin"
            )
        )
    }

    @Test
    fun pendingRetainsSelectionAndAbsentClearsWithoutPageZeroFallback() {
        assertEquals(
            PhotoOverlayResolvedSelection("asset", initialIndex = null),
            resolvePhotoOverlaySelection(
                requestedAssetId = "asset",
                resolution = PhotoOverlayInitialIndexResolution.Pending
            )
        )
        assertEquals(
            PhotoOverlayResolvedSelection(selectedAssetId = null, initialIndex = null),
            resolvePhotoOverlaySelection(
                requestedAssetId = "asset",
                resolution = PhotoOverlayInitialIndexResolution.Absent
            )
        )
        assertEquals(
            PhotoOverlayResolvedSelection("asset", initialIndex = 7),
            resolvePhotoOverlaySelection(
                requestedAssetId = "asset",
                resolution = PhotoOverlayInitialIndexResolution.Found(7)
            )
        )
    }

    @Test
    fun returnSourcePreparationCompletesBeforeFinalDismissal() = runTest {
        val events = mutableListOf<String>()

        prepareThenDismissPhotoOverlay(
            returnAssetId = "asset",
            onPrepare = { events += "prepare:$it" },
            onDismissed = { events += "dismiss:$it" }
        )

        assertEquals(listOf("prepare:asset", "dismiss:asset"), events)
    }

    @Test
    fun missingReturnSourceSkipsScroll() = runTest {
        val ensuredIndexes = mutableListOf<Int>()

        preparePhotoGridReturnSource(
            assetId = "missing",
            displayIndexByAssetId = mapOf("present" to 4),
            ensureVisible = ensuredIndexes::add
        )
        preparePhotoGridReturnSource(
            assetId = "present",
            displayIndexByAssetId = mapOf("present" to 4),
            ensureVisible = ensuredIndexes::add
        )

        assertEquals(listOf(4), ensuredIndexes)
    }

    @Test
    fun routingWaitsForReturnPreparationAndCompletedExit() = runTest {
        val prepared = CompletableDeferred<Unit>()
        val exited = CompletableDeferred<Boolean>()
        val events = mutableListOf<String>()
        val dismissal = launch {
            prepareThenDismissPhotoOverlay(
                returnAssetId = "base",
                onPrepare = { prepared.await(); events += "prepare:$it" },
                onDismissed = { events += "bookkeeping:$it" },
                onStartExit = { events += "hide" },
                awaitExit = { exited.await() },
                afterExit = { events += "route" }
            )
        }

        runCurrent()
        assertEquals(emptyList(), events)
        prepared.complete(Unit)
        runCurrent()
        assertEquals(listOf("prepare:base", "bookkeeping:base", "hide"), events)
        exited.complete(true)
        dismissal.join()
        assertEquals(listOf("prepare:base", "bookkeeping:base", "hide", "route"), events)
    }

    @Test
    fun failedPreparationNeverRoutes() = runTest {
        val events = mutableListOf<String>()

        assertFailsWith<IllegalStateException> {
            prepareThenDismissPhotoOverlay(
                returnAssetId = "base",
                onPrepare = { error("Failed preparation") },
                onDismissed = { events += "bookkeeping" },
                onStartExit = { events += "hide" },
                awaitExit = { events += "await"; true },
                afterExit = { events += "route" }
            )
        }

        assertEquals(listOf("hide"), events)
    }

    @Test
    fun cancellationDuringPreparationNeverRoutesAfterLateCompletion() = runTest {
        val prepared = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val dismissal = launch {
            prepareThenDismissPhotoOverlay(
                returnAssetId = "base",
                onPrepare = { prepared.await() },
                onDismissed = { events += "bookkeeping" },
                onStartExit = { events += "hide" },
                afterExit = { events += "route" }
            )
        }

        runCurrent()
        dismissal.cancelAndJoin()
        prepared.complete(Unit)
        runCurrent()

        assertEquals(listOf("hide"), events)
    }

    @Test
    fun disposalWhileWaitingForExitNeverRoutes() = runTest {
        val exited = CompletableDeferred<Boolean>()
        val events = mutableListOf<String>()
        val dismissal = launch {
            prepareThenDismissPhotoOverlay(
                returnAssetId = "base",
                onPrepare = {},
                onDismissed = {},
                awaitExit = { exited.await() },
                afterExit = { events += "route" }
            )
        }

        runCurrent()
        dismissal.cancelAndJoin()
        exited.complete(true)
        runCurrent()

        assertEquals(emptyList(), events)
    }

    @Test
    fun supersededExitDoesNotRunItsRoutingCompletion() = runTest {
        var routes = 0

        prepareThenDismissPhotoOverlay(
            returnAssetId = "base",
            onPrepare = {},
            onDismissed = {},
            awaitExit = { false },
            afterExit = { routes++ }
        )

        assertEquals(0, routes)
    }

    @Test
    fun ordinaryDismissalKeepsItsNonAwaitingContract() = runTest {
        val events = mutableListOf<String>()

        prepareThenDismissPhotoOverlay(
            returnAssetId = "base",
            onPrepare = { events += "prepare" },
            onDismissed = { events += "bookkeeping" },
            onStartExit = { events += "hide" },
            awaitExit = { error("Ordinary dismissal must not await exit") }
        )

        assertEquals(listOf("prepare", "bookkeeping", "hide"), events)
    }

    @Test
    fun dismissActionRemainsAssignableToExistingFunctionCallback() {
        val assetIds = mutableListOf<String?>()
        var completion: (() -> Unit)? = null
        var routes = 0
        val action = PhotoOverlayDismissAction { assetId, afterExit ->
            assetIds += assetId
            completion = afterExit
        }
        val ordinaryCallback: (String?) -> Unit = action

        ordinaryCallback("ordinary")
        assertNull(completion)
        action.dismiss("date-source") { routes++ }
        assertEquals(0, routes)
        assertNotNull(completion).invoke()

        assertEquals<List<String?>>(listOf("ordinary", "date-source"), assetIds)
        assertEquals(1, routes)
    }
}
