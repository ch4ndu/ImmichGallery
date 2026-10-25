package com.udnahc.immichgallery.ui.screen.timeline

import com.udnahc.immichgallery.domain.model.GRID_SPACING_DP
import com.udnahc.immichgallery.domain.model.GeometryAlignedRange
import com.udnahc.immichgallery.domain.model.GeometryAlignedSection
import com.udnahc.immichgallery.domain.model.HeaderItem
import com.udnahc.immichgallery.domain.model.MosaicBandItem
import com.udnahc.immichgallery.domain.model.MosaicSectionGeometryRange
import com.udnahc.immichgallery.domain.model.MosaicTemplateFamily
import com.udnahc.immichgallery.domain.model.PlaceholderItem
import com.udnahc.immichgallery.domain.model.RowItem
import com.udnahc.immichgallery.domain.model.RowItemKind
import com.udnahc.immichgallery.domain.model.TimelineBucketGeometrySummary
import com.udnahc.immichgallery.domain.model.TimelineGroupSize
import com.udnahc.immichgallery.domain.model.TimelineMosaicBucketSkeleton
import com.udnahc.immichgallery.domain.model.TimelineMosaicGeometryRequest
import com.udnahc.immichgallery.domain.model.TimelineMosaicGeometrySummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimelineSkeletonStateTest {
    @Test
    fun gateStaysClosedUntilSkeletonForCurrentIdentityLoads() {
        val loading = TimelineSkeletonState().startingLoad(identity)

        assertFalse(timelineSkeletonGateOpen(loading, identity))
        assertTrue(timelineSkeletonGateOpen(loading, required = null))
        val loaded = loading.withLoadedBuckets(identity, mapOf("b" to skeleton("b", 100f)), null, emptyMap())
        assertTrue(timelineSkeletonGateOpen(loaded, identity))
        assertFalse(timelineSkeletonGateOpen(loaded, identity.copy(columnCount = 6)))
        assertFalse(timelineSkeletonGateOpen(loading.copy(status = TimelineSkeletonLoadStatus.Failed), identity))
    }

    @Test
    fun olderFullReadCannotOverwriteNewerBucketReload() {
        val loaded = TimelineSkeletonState().startingLoad(identity)
            .withLoadedBuckets(identity, mapOf("a" to skeleton("a", 100f)), null, emptyMap())
        val fullReadOrigin = loaded.bucketEpochs
        val reloading = loaded.withBumpedEpochs(setOf("a"))
        val reloaded = reloading.withLoadedBuckets(
            identity,
            mapOf("a" to skeleton("a", 200f)),
            setOf("a"),
            reloading.bucketEpochs
        )

        val afterStaleFullRead = reloaded.withLoadedBuckets(
            identity,
            mapOf("a" to skeleton("a", 100f), "b" to skeleton("b", 50f)),
            null,
            fullReadOrigin
        )

        assertEquals(200f, afterStaleFullRead.buckets.getValue("a").placeholderHeight)
        assertEquals(50f, afterStaleFullRead.buckets.getValue("b").placeholderHeight)
    }

    @Test
    fun bucketReloadRemovesBucketThatNoLongerValidates() {
        val loaded = TimelineSkeletonState().startingLoad(identity)
            .withLoadedBuckets(identity, mapOf("a" to skeleton("a", 100f)), null, emptyMap())
            .withBumpedEpochs(setOf("a"))

        val reloaded = loaded.withLoadedBuckets(identity, emptyMap(), setOf("a"), loaded.bucketEpochs)

        assertNull(reloaded.buckets["a"])
    }

    @Test
    fun readForPreviousIdentityIsIgnored() {
        val loaded = TimelineSkeletonState().startingLoad(identity.copy(columnCount = 6))

        val result = loaded.withLoadedBuckets(identity, mapOf("a" to skeleton("a", 100f)), null, emptyMap())

        assertEquals(loaded, result)
    }

    @Test
    fun contentTokenChangesWhenRangeBoundariesChangeWithEqualTotals() {
        val first = TimelineMosaicSectionGeometry(202f, listOf(range(0, 2, 100f), range(2, 2, 100f)))
        val second = TimelineMosaicSectionGeometry(202f, listOf(range(0, 3, 100f), range(3, 1, 100f)))

        assertTrue(first.contentToken != second.contentToken)
    }

    @Test
    fun daySkeletonEmitsFinalHeaderAndRangeKeys() {
        val skeleton = TimelineMosaicBucketSkeleton(
            timeBucket = "2026-01",
            assetCount = 5,
            placeholderHeight = 0f,
            sections = listOf(
                section("2026-01|day|2026-01-31", range(0, 2, 120f), range(2, 1, 80f)),
                section("2026-01|day|unknown-date", range(0, 2, 90f))
            )
        )

        val items = timelineSkeletonBucketItems("2026-01", 3, TimelineGroupSize.DAY, skeleton)

        assertEquals(
            listOf(
                "dh_2026-01_2026-01|day|2026-01-31",
                "mr_2026-01_2026-01|day|2026-01-31_0_2",
                "mr_2026-01_2026-01|day|2026-01-31_2_1",
                "dh_2026-01_2026-01|day|unknown-date",
                "mr_2026-01_2026-01|day|unknown-date_0_2"
            ),
            items.map { it.gridKey }
        )
        assertEquals(listOf(120f, 80f, 90f), items.filterIsInstance<PlaceholderItem>().map { it.estimatedHeight })
        assertTrue(items.filterIsInstance<HeaderItem>().all { it.assetGroupDisplayLabel != null })
    }

    @Test
    fun alignedItemsKeepPlaceholderKeyAndHeightWhenBandArrives() {
        val ranges = listOf(range(0, 2, 120f), range(2, 3, 80f))
        val band = MosaicBandItem(
            gridKey = "engine-key",
            bucketIndex = 0,
            sectionLabel = "s",
            tiles = emptyList(),
            bandHeight = 120f,
            sourceStartIndex = 0,
            sourceCount = 2
        )

        val pending = timelineGeometryAlignedItems(
            "2026-01", "s", 0, "s",
            GeometryAlignedSection(ranges.map { GeometryAlignedRange(it, null) })
        )
        val partial = timelineGeometryAlignedItems(
            "2026-01", "s", 0, "s",
            GeometryAlignedSection(listOf(GeometryAlignedRange(ranges[0], band), GeometryAlignedRange(ranges[1], null)))
        )

        assertEquals(pending.map { it.gridKey }, partial.map { it.gridKey })
        assertEquals(120f, (partial[0] as MosaicBandItem).bandHeight)
        assertEquals(80f, (partial[1] as PlaceholderItem).estimatedHeight)
    }

    @Test
    fun gateRequiresAllExpectedBucketsAndDoesNotOpenOnFailed() {
        val partialLoaded = TimelineSkeletonState()
            .startingLoad(identity)
            .withLoadedBuckets(identity, mapOf("a" to skeleton("a", 100f)), null, emptyMap())

        assertFalse(timelineSkeletonGateOpen(partialLoaded, identity, requiredBuckets = setOf("a", "b")))
        assertTrue(timelineSkeletonGateOpen(partialLoaded, identity, requiredBuckets = setOf("a")))

        val completeLoaded = partialLoaded.withLoadedBuckets(
            identity,
            mapOf("a" to skeleton("a", 100f), "b" to skeleton("b", 140f)),
            null,
            emptyMap()
        )
        assertTrue(timelineSkeletonGateOpen(completeLoaded, identity, requiredBuckets = setOf("a", "b")))

        val failedPartial = partialLoaded.copy(status = TimelineSkeletonLoadStatus.Failed)
        assertFalse(timelineSkeletonGateOpen(failedPartial, identity, requiredBuckets = setOf("a", "b")))
        assertFalse(timelineSkeletonGateOpen(failedPartial, identity, requiredBuckets = emptySet()))
    }

    @Test
    fun olderSkeletonReadCannotOverwriteNewerPrecomputeOrInvalidatedGeometry() {
        val harness = coordinatorHarness()
        val initialRevisions = mapOf("a" to 0, "b" to 0)
        val initialSnapshot = TimelineRuntimeGenerationSnapshot(
            globalGeneration = 1L,
            bucketGenerations = mapOf("a" to 1L, "b" to 1L)
        )

        val oldReadOrigin = assertNotNull(
            harness.coordinator.beginFullLoad(
                identity = identity,
                requiredBuckets = setOf("a", "b"),
                isSourceReady = true,
                assetRevisions = initialRevisions,
                generationSnapshot = initialSnapshot
            )
        )

        // Bucket "a" changes while the old read is in flight: invalidate and publish R=1 geometry.
        harness.coordinator.invalidateBuckets(setOf("a"))
        val updatedRevisions = mapOf("a" to 1, "b" to 0)
        val updatedSnapshot = TimelineRuntimeGenerationSnapshot(
            globalGeneration = 1L,
            bucketGenerations = mapOf("a" to 2L, "b" to 1L)
        )
        harness.coordinator.publishPrecomputedResult(
            summariesByGroup = mapOf(
                TimelineGroupSize.MONTH to listOf(TimelineBucketGeometrySummary("a", 200f, 1)),
                TimelineGroupSize.DAY to listOf(TimelineBucketGeometrySummary("a", 230f, 2))
            ),
            sectionSummariesByGroup = mapOf(
                TimelineGroupSize.MONTH to listOf(sectionFor("a", "a|month", range(0, 1, 200f))),
                TimelineGroupSize.DAY to listOf(sectionFor("a", "a|day|2026-01-01", range(0, 1, 200f)))
            ),
            skeletonsByGroup = mapOf(
                TimelineGroupSize.MONTH to mapOf("a" to skeleton("a", 200f)),
                TimelineGroupSize.DAY to mapOf("a" to daySkeleton("a", 230f, 200f))
            ),
            columnCount = identity.columnCount,
            families = setOf(MosaicTemplateFamily.FOUR_TILE),
            geometryRequest = identity.geometryRequest,
            assetRevisions = updatedRevisions,
            activeIdentity = identity,
            requiredBuckets = setOf("a", "b"),
            isSourceReady = true
        )

        // Now the old read completes with stale R=0 data for "a" (100f) and valid R=0 data for "b" (140f).
        val completed = harness.coordinator.completeFullLoad(
            readOrigin = oldReadOrigin,
            primaryReadSkeletons = mapOf("a" to skeleton("a", 100f), "b" to skeleton("b", 140f)),
            siblingReadSkeletons = mapOf("a" to daySkeleton("a", 130f, 100f), "b" to daySkeleton("b", 170f, 140f)),
            activeIdentity = identity,
            requiredBuckets = setOf("a", "b"),
            isSourceReady = true,
            currentGenerationSnapshot = updatedSnapshot,
            currentAssetRevisions = updatedRevisions
        )

        assertTrue(completed)
        assertTrue(harness.timelineGeometryReady.value)
        assertEquals(TimelineSkeletonLoadStatus.Loaded, harness.skeletonState.value.status)
        assertEquals(200f, harness.skeletonState.value.buckets.getValue("a").placeholderHeight)
        assertEquals(140f, harness.skeletonState.value.buckets.getValue("b").placeholderHeight)

        val preparedMonth = assertNotNull(
            harness.coordinator.currentPreparedSkeletonsForIdentity(identity, updatedRevisions)
        )
        val preparedDay = assertNotNull(
            harness.coordinator.currentPreparedSkeletonsForIdentity(dayIdentity, updatedRevisions)
        )
        assertEquals(200f, preparedMonth.getValue("a").placeholderHeight)
        assertEquals(140f, preparedMonth.getValue("b").placeholderHeight)
        assertEquals(230f, preparedDay.getValue("a").placeholderHeight)
        assertEquals(170f, preparedDay.getValue("b").placeholderHeight)

        val monthBucketKeyA = MosaicCacheKey(
            timeBucket = "a",
            sectionKey = timelineBucketGeometrySectionKey("a", TimelineGroupSize.MONTH, identity.geometryRequest),
            columnCount = identity.columnCount,
            assetRevision = 1,
            familiesKey = identity.familiesKey
        )
        val staleMonthBucketKeyA = monthBucketKeyA.copy(assetRevision = 0)
        assertEquals(200f, harness.bucketGeometryStates.value[monthBucketKeyA])
        assertNull(harness.bucketGeometryStates.value[staleMonthBucketKeyA])
        assertTrue(monthBucketKeyA in harness.geometryReadyBuckets)
        assertFalse(staleMonthBucketKeyA in harness.geometryReadyBuckets)

        val monthSectionKeyA = MosaicCacheKey(
            timeBucket = "a",
            sectionKey = "a|month",
            columnCount = identity.columnCount,
            assetRevision = 1,
            familiesKey = identity.familiesKey
        )
        assertEquals(200f, harness.mosaicGeometryStates.value[monthSectionKeyA]?.placeholderHeight)
        assertNull(harness.mosaicGeometryStates.value[monthSectionKeyA.copy(assetRevision = 0)])
    }

    @Test
    fun bucketReloadEvictsRejectedBucketAcrossPreparedAndRuntimeGeometry() {
        val harness = coordinatorHarness()
        val revisions = mapOf("a" to 0, "b" to 0)
        val snapshot = TimelineRuntimeGenerationSnapshot(
            globalGeneration = 1L,
            bucketGenerations = mapOf("a" to 1L, "b" to 1L)
        )

        val readOrigin = assertNotNull(
            harness.coordinator.beginFullLoad(
                identity = identity,
                requiredBuckets = setOf("a", "b"),
                isSourceReady = true,
                assetRevisions = revisions,
                generationSnapshot = snapshot
            )
        )
        assertTrue(
            harness.coordinator.completeFullLoad(
                readOrigin = readOrigin,
                primaryReadSkeletons = mapOf("a" to skeleton("a", 100f), "b" to skeleton("b", 140f)),
                siblingReadSkeletons = mapOf("a" to daySkeleton("a", 130f, 100f), "b" to daySkeleton("b", 170f, 140f)),
                activeIdentity = identity,
                requiredBuckets = setOf("a", "b"),
                isSourceReady = true,
                currentGenerationSnapshot = snapshot,
                currentAssetRevisions = revisions
            )
        )
        assertTrue(harness.timelineGeometryReady.value)

        // Reload bucket "a", and the repository rejects "a" (returns emptyMap()).
        val reloadOrigin = assertNotNull(
            harness.coordinator.beginBucketReload(
                identity = identity,
                timeBuckets = setOf("a"),
                assetRevisions = revisions,
                generationSnapshot = snapshot
            )
        )
        harness.coordinator.completeBucketReload(
            reloadOrigin = reloadOrigin,
            primaryReloadSkeletons = emptyMap(),
            siblingReloadSkeletons = emptyMap(),
            activeIdentity = identity,
            requiredBuckets = setOf("a", "b"),
            isSourceReady = true,
            currentGenerationSnapshot = snapshot,
            currentAssetRevisions = revisions
        )

        assertNull(harness.skeletonState.value.buckets["a"])
        assertEquals(140f, harness.skeletonState.value.buckets.getValue("b").placeholderHeight)
        assertFalse(harness.timelineGeometryReady.value)
        assertFalse(timelineSkeletonGateOpen(harness.skeletonState.value, identity, setOf("a", "b")))

        assertNull(harness.coordinator.currentPreparedSkeletonsForIdentity(identity, revisions)?.get("a"))
        assertNull(harness.coordinator.currentPreparedSkeletonsForIdentity(dayIdentity, revisions)?.get("a"))
        assertTrue(harness.bucketGeometryStates.value.keys.none { it.timeBucket == "a" })
        assertTrue(harness.mosaicGeometryStates.value.keys.none { it.timeBucket == "a" })
        assertTrue(harness.geometryReadyBuckets.none { it.timeBucket == "a" })

        // Switching away to DAY and back to MONTH cannot resurrect the rejected bucket "a".
        assertFalse(harness.coordinator.restorePreparedForIdentity(dayIdentity, setOf("a", "b"), true, revisions))
        assertFalse(harness.coordinator.restorePreparedForIdentity(identity, setOf("a", "b"), true, revisions))
    }

    @Test
    fun fullLoadRequiresDualGroupCompletenessBeforeOpeningReadiness() {
        val harness = coordinatorHarness()
        val revisions = mapOf("a" to 0, "b" to 0)
        val snapshot = TimelineRuntimeGenerationSnapshot(
            globalGeneration = 1L,
            bucketGenerations = mapOf("a" to 1L, "b" to 1L)
        )

        // Case 1: MONTH complete, DAY missing "b" and DAY repair fails -> not ready, gate closed.
        val origin1 = assertNotNull(
            harness.coordinator.beginFullLoad(
                identity = identity,
                requiredBuckets = setOf("a", "b"),
                isSourceReady = true,
                assetRevisions = revisions,
                generationSnapshot = snapshot
            )
        )
        val repairOrigin1 = assertNotNull(
            harness.coordinator.captureRepairOrigin(
                readOrigin = origin1,
                missingBuckets = setOf("b"),
                activeIdentity = identity,
                assetRevisions = revisions,
                generationSnapshot = snapshot
            )
        )
        val complete1 = harness.coordinator.completeFullLoad(
            readOrigin = origin1,
            primaryReadSkeletons = mapOf("a" to skeleton("a", 100f), "b" to skeleton("b", 140f)),
            siblingReadSkeletons = mapOf("a" to daySkeleton("a", 130f, 100f)),
            repairOrigin = repairOrigin1,
            repairedPrimarySkeletons = mapOf("b" to skeleton("b", 140f)),
            repairedSiblingSkeletons = emptyMap(),
            activeIdentity = identity,
            requiredBuckets = setOf("a", "b"),
            isSourceReady = true,
            currentGenerationSnapshot = snapshot,
            currentAssetRevisions = revisions
        )
        assertFalse(complete1)
        assertFalse(harness.timelineGeometryReady.value)
        assertEquals(TimelineSkeletonLoadStatus.Failed, harness.skeletonState.value.status)
        assertFalse(timelineSkeletonGateOpen(harness.skeletonState.value, identity, setOf("a", "b")))
        assertFalse(harness.coordinator.restorePreparedForIdentity(dayIdentity, setOf("a", "b"), true, revisions))

        // Case 2: Primary partial repair failure -> not ready, gate closed.
        val origin2 = assertNotNull(
            harness.coordinator.beginFullLoad(
                identity = identity,
                requiredBuckets = setOf("a", "b"),
                isSourceReady = true,
                assetRevisions = revisions,
                generationSnapshot = snapshot
            )
        )
        val repairOrigin2 = assertNotNull(
            harness.coordinator.captureRepairOrigin(
                readOrigin = origin2,
                missingBuckets = setOf("a", "b"),
                activeIdentity = identity,
                assetRevisions = revisions,
                generationSnapshot = snapshot
            )
        )
        val complete2 = harness.coordinator.completeFullLoad(
            readOrigin = origin2,
            primaryReadSkeletons = emptyMap(),
            siblingReadSkeletons = emptyMap(),
            repairOrigin = repairOrigin2,
            repairedPrimarySkeletons = mapOf("a" to skeleton("a", 100f)),
            repairedSiblingSkeletons = mapOf("a" to daySkeleton("a", 130f, 100f), "b" to daySkeleton("b", 170f, 140f)),
            activeIdentity = identity,
            requiredBuckets = setOf("a", "b"),
            isSourceReady = true,
            currentGenerationSnapshot = snapshot,
            currentAssetRevisions = revisions
        )
        assertFalse(complete2)
        assertFalse(harness.timelineGeometryReady.value)
        assertEquals(TimelineSkeletonLoadStatus.Failed, harness.skeletonState.value.status)
        assertFalse(timelineSkeletonGateOpen(harness.skeletonState.value, identity, setOf("a", "b")))

        // Case 3: Both MONTH and DAY complete after repair -> ready, gate open, and DAY switch restores immediately.
        val origin3 = assertNotNull(
            harness.coordinator.beginFullLoad(
                identity = identity,
                requiredBuckets = setOf("a", "b"),
                isSourceReady = true,
                assetRevisions = revisions,
                generationSnapshot = snapshot
            )
        )
        val repairOrigin3 = assertNotNull(
            harness.coordinator.captureRepairOrigin(
                readOrigin = origin3,
                missingBuckets = setOf("b"),
                activeIdentity = identity,
                assetRevisions = revisions,
                generationSnapshot = snapshot
            )
        )
        val complete3 = harness.coordinator.completeFullLoad(
            readOrigin = origin3,
            primaryReadSkeletons = mapOf("a" to skeleton("a", 100f)),
            siblingReadSkeletons = mapOf("a" to daySkeleton("a", 130f, 100f)),
            repairOrigin = repairOrigin3,
            repairedPrimarySkeletons = mapOf("b" to skeleton("b", 140f)),
            repairedSiblingSkeletons = mapOf("b" to daySkeleton("b", 170f, 140f)),
            activeIdentity = identity,
            requiredBuckets = setOf("a", "b"),
            isSourceReady = true,
            currentGenerationSnapshot = snapshot,
            currentAssetRevisions = revisions
        )
        assertTrue(complete3)
        assertTrue(harness.timelineGeometryReady.value)
        assertEquals(TimelineSkeletonLoadStatus.Loaded, harness.skeletonState.value.status)
        assertTrue(timelineSkeletonGateOpen(harness.skeletonState.value, identity, setOf("a", "b")))
        assertTrue(harness.coordinator.restorePreparedForIdentity(dayIdentity, setOf("a", "b"), true, revisions))
        assertEquals(dayIdentity, harness.skeletonState.value.identity)
        assertTrue(timelineSkeletonGateOpen(harness.skeletonState.value, dayIdentity, setOf("a", "b")))
    }

    @Test
    fun invalidateBucketsClosesBothGeometryReadyAndSkeletonGateForRequiredBucket() {
        val harness = coordinatorHarness()
        val revisions = mapOf("a" to 0, "b" to 0)
        val snapshot = TimelineRuntimeGenerationSnapshot(
            globalGeneration = 1L,
            bucketGenerations = mapOf("a" to 1L, "b" to 1L)
        )
        val readOrigin = assertNotNull(
            harness.coordinator.beginFullLoad(
                identity = identity,
                requiredBuckets = setOf("a", "b"),
                isSourceReady = true,
                assetRevisions = revisions,
                generationSnapshot = snapshot
            )
        )
        assertTrue(
            harness.coordinator.completeFullLoad(
                readOrigin = readOrigin,
                primaryReadSkeletons = mapOf("a" to skeleton("a", 100f), "b" to skeleton("b", 140f)),
                siblingReadSkeletons = mapOf("a" to daySkeleton("a", 130f, 100f), "b" to daySkeleton("b", 170f, 140f)),
                activeIdentity = identity,
                requiredBuckets = setOf("a", "b"),
                isSourceReady = true,
                currentGenerationSnapshot = snapshot,
                currentAssetRevisions = revisions
            )
        )
        assertTrue(harness.timelineGeometryReady.value)
        assertTrue(timelineSkeletonGateOpen(harness.skeletonState.value, identity, setOf("a", "b")))

        harness.coordinator.invalidateBuckets(
            timeBuckets = setOf("a"),
            requiredBuckets = setOf("a", "b"),
            isSourceReady = true,
            assetRevisions = revisions
        )

        assertFalse(harness.timelineGeometryReady.value)
        assertEquals(TimelineSkeletonLoadStatus.Loading, harness.skeletonState.value.status)
        assertNull(harness.skeletonState.value.buckets["a"])
        assertEquals(140f, harness.skeletonState.value.buckets.getValue("b").placeholderHeight)
        assertFalse(timelineSkeletonGateOpen(harness.skeletonState.value, identity, setOf("a", "b")))
        assertTrue(
            shouldRepairTimelineSkeletonAfterSync(
                skeletonIdentity = identity,
                skeletonState = harness.skeletonState.value,
                timelineGeometryReady = harness.timelineGeometryReady.value,
                requiredBuckets = setOf("a", "b")
            )
        )
    }

    @Test
    fun bucketReloadWithRejectedDaySiblingClosesBothGeometryReadyAndSkeletonGate() {
        val harness = coordinatorHarness()
        val revisions = mapOf("a" to 0, "b" to 0)
        val snapshot = TimelineRuntimeGenerationSnapshot(
            globalGeneration = 1L,
            bucketGenerations = mapOf("a" to 1L, "b" to 1L)
        )
        val readOrigin = assertNotNull(
            harness.coordinator.beginFullLoad(
                identity = identity,
                requiredBuckets = setOf("a", "b"),
                isSourceReady = true,
                assetRevisions = revisions,
                generationSnapshot = snapshot
            )
        )
        assertTrue(
            harness.coordinator.completeFullLoad(
                readOrigin = readOrigin,
                primaryReadSkeletons = mapOf("a" to skeleton("a", 100f), "b" to skeleton("b", 140f)),
                siblingReadSkeletons = mapOf("a" to daySkeleton("a", 130f, 100f), "b" to daySkeleton("b", 170f, 140f)),
                activeIdentity = identity,
                requiredBuckets = setOf("a", "b"),
                isSourceReady = true,
                currentGenerationSnapshot = snapshot,
                currentAssetRevisions = revisions
            )
        )
        assertTrue(harness.timelineGeometryReady.value)
        assertTrue(timelineSkeletonGateOpen(harness.skeletonState.value, identity, setOf("a", "b")))

        val reloadOrigin = assertNotNull(
            harness.coordinator.beginBucketReload(
                identity = identity,
                timeBuckets = setOf("a"),
                assetRevisions = revisions,
                generationSnapshot = snapshot
            )
        )
        val reloadCompleted = harness.coordinator.completeBucketReload(
            reloadOrigin = reloadOrigin,
            primaryReloadSkeletons = mapOf("a" to skeleton("a", 110f)),
            siblingReloadSkeletons = emptyMap(),
            activeIdentity = identity,
            requiredBuckets = setOf("a", "b"),
            isSourceReady = true,
            currentGenerationSnapshot = snapshot,
            currentAssetRevisions = revisions
        )

        assertFalse(reloadCompleted)
        assertFalse(harness.timelineGeometryReady.value)
        assertEquals(TimelineSkeletonLoadStatus.Loading, harness.skeletonState.value.status)
        assertFalse(timelineSkeletonGateOpen(harness.skeletonState.value, identity, setOf("a", "b")))
        assertTrue(
            shouldRepairTimelineSkeletonAfterSync(
                skeletonIdentity = identity,
                skeletonState = harness.skeletonState.value,
                timelineGeometryReady = harness.timelineGeometryReady.value,
                requiredBuckets = setOf("a", "b")
            )
        )
    }

    @Test
    fun failedDualGroupPrepareRequestsSyncRepairAndRecoversWithUnchangedAssetRevisions() {
        val harness = coordinatorHarness()
        val revisions = mapOf("a" to 0, "b" to 0)
        val snapshot = TimelineRuntimeGenerationSnapshot(
            globalGeneration = 1L,
            bucketGenerations = mapOf("a" to 1L, "b" to 1L)
        )

        // Initial load: MONTH succeeds, DAY fails for "b".
        val origin1 = assertNotNull(
            harness.coordinator.beginFullLoad(
                identity = identity,
                requiredBuckets = setOf("a", "b"),
                isSourceReady = true,
                assetRevisions = revisions,
                generationSnapshot = snapshot
            )
        )
        val repair1 = assertNotNull(
            harness.coordinator.captureRepairOrigin(
                readOrigin = origin1,
                missingBuckets = setOf("b"),
                activeIdentity = identity,
                assetRevisions = revisions,
                generationSnapshot = snapshot
            )
        )
        assertFalse(
            harness.coordinator.completeFullLoad(
                readOrigin = origin1,
                primaryReadSkeletons = mapOf("a" to skeleton("a", 100f), "b" to skeleton("b", 140f)),
                siblingReadSkeletons = mapOf("a" to daySkeleton("a", 130f, 100f)),
                repairOrigin = repair1,
                repairedPrimarySkeletons = mapOf("b" to skeleton("b", 140f)),
                repairedSiblingSkeletons = emptyMap(),
                activeIdentity = identity,
                requiredBuckets = setOf("a", "b"),
                isSourceReady = true,
                currentGenerationSnapshot = snapshot,
                currentAssetRevisions = revisions
            )
        )
        assertTrue(harness.coordinator.failFullLoad(identity))
        assertEquals(TimelineSkeletonLoadStatus.Failed, harness.skeletonState.value.status)
        assertFalse(harness.timelineGeometryReady.value)
        assertFalse(timelineSkeletonGateOpen(harness.skeletonState.value, identity, setOf("a", "b")))

        // No-op manual refresh (unchanged assetRevisions) still detects that skeleton repair is required.
        assertTrue(
            shouldRepairTimelineSkeletonAfterSync(
                skeletonIdentity = identity,
                skeletonState = harness.skeletonState.value,
                timelineGeometryReady = harness.timelineGeometryReady.value,
                requiredBuckets = setOf("a", "b")
            )
        )

        // Retry with unchanged revisions repairs "b" for DAY and reopens the gate.
        val origin2 = assertNotNull(
            harness.coordinator.beginFullLoad(
                identity = identity,
                requiredBuckets = setOf("a", "b"),
                isSourceReady = true,
                assetRevisions = revisions,
                generationSnapshot = snapshot
            )
        )
        val repair2 = assertNotNull(
            harness.coordinator.captureRepairOrigin(
                readOrigin = origin2,
                missingBuckets = setOf("b"),
                activeIdentity = identity,
                assetRevisions = revisions,
                generationSnapshot = snapshot
            )
        )
        assertTrue(
            harness.coordinator.completeFullLoad(
                readOrigin = origin2,
                primaryReadSkeletons = mapOf("a" to skeleton("a", 100f), "b" to skeleton("b", 140f)),
                siblingReadSkeletons = mapOf("a" to daySkeleton("a", 130f, 100f)),
                repairOrigin = repair2,
                repairedPrimarySkeletons = mapOf("b" to skeleton("b", 140f)),
                repairedSiblingSkeletons = mapOf("b" to daySkeleton("b", 170f, 140f)),
                activeIdentity = identity,
                requiredBuckets = setOf("a", "b"),
                isSourceReady = true,
                currentGenerationSnapshot = snapshot,
                currentAssetRevisions = revisions
            )
        )
        assertEquals(TimelineSkeletonLoadStatus.Loaded, harness.skeletonState.value.status)
        assertTrue(harness.timelineGeometryReady.value)
        assertTrue(timelineSkeletonGateOpen(harness.skeletonState.value, identity, setOf("a", "b")))
        assertFalse(
            shouldRepairTimelineSkeletonAfterSync(
                skeletonIdentity = identity,
                skeletonState = harness.skeletonState.value,
                timelineGeometryReady = harness.timelineGeometryReady.value,
                requiredBuckets = setOf("a", "b")
            )
        )
    }

    @Test
    fun delayedFailureCleanupAfterConcurrentPrecomputeSuccessDoesNotClobberLoadedState() {
        val harness = coordinatorHarness()
        val revisions = mapOf("a" to 0)
        val snapshot = TimelineRuntimeGenerationSnapshot(
            globalGeneration = 1L,
            bucketGenerations = mapOf("a" to 1L)
        )

        // Start a full load that later fails/aborts before completeFullLoad.
        val readOrigin = assertNotNull(
            harness.coordinator.beginFullLoad(
                identity = identity,
                requiredBuckets = setOf("a"),
                isSourceReady = true,
                assetRevisions = revisions,
                generationSnapshot = snapshot
            )
        )
        assertFalse(harness.timelineGeometryReady.value)
        assertEquals(TimelineSkeletonLoadStatus.Loading, harness.skeletonState.value.status)

        // While failure cleanup is delayed, a concurrent precompute publishes complete dual-group geometry.
        val missing = harness.coordinator.publishPrecomputedResult(
            summariesByGroup = mapOf(
                TimelineGroupSize.MONTH to listOf(TimelineBucketGeometrySummary("a", 120f, 1)),
                TimelineGroupSize.DAY to listOf(TimelineBucketGeometrySummary("a", 150f, 2))
            ),
            sectionSummariesByGroup = mapOf(
                TimelineGroupSize.MONTH to listOf(sectionFor("a", "a|month", range(0, 1, 120f))),
                TimelineGroupSize.DAY to listOf(sectionFor("a", "a|day|2026-01-01", range(0, 1, 120f)))
            ),
            skeletonsByGroup = mapOf(
                TimelineGroupSize.MONTH to mapOf("a" to skeleton("a", 120f)),
                TimelineGroupSize.DAY to mapOf("a" to daySkeleton("a", 150f, 120f))
            ),
            columnCount = identity.columnCount,
            families = setOf(MosaicTemplateFamily.FOUR_TILE),
            geometryRequest = identity.geometryRequest,
            assetRevisions = revisions,
            activeIdentity = identity,
            requiredBuckets = setOf("a"),
            isSourceReady = true
        )
        assertTrue(missing.isEmpty())
        assertTrue(harness.timelineGeometryReady.value)
        assertEquals(TimelineSkeletonLoadStatus.Loaded, harness.skeletonState.value.status)
        assertTrue(timelineSkeletonGateOpen(harness.skeletonState.value, identity, setOf("a")))

        // Delayed failure cleanup now runs under the mutex: it must be a no-op and preserve Loaded + ready.
        val markedFailed = harness.coordinator.failFullLoad(
            identity = readOrigin.identity,
            expectedGlobalGeneration = readOrigin.globalGeneration,
            currentGlobalGeneration = snapshot.globalGeneration
        )
        assertFalse(markedFailed)
        assertTrue(harness.timelineGeometryReady.value)
        assertEquals(TimelineSkeletonLoadStatus.Loaded, harness.skeletonState.value.status)
        assertTrue(timelineSkeletonGateOpen(harness.skeletonState.value, identity, setOf("a")))
    }

    @Test
    fun alignedItemsForceExactRangeHeightOnBandAndFallbackRow() {
        val ranges = listOf(range(0, 2, 120.5f), range(2, 3, 80.25f))
        val bandWithDriftedHeight = MosaicBandItem(
            gridKey = "engine-key",
            bucketIndex = 0,
            sectionLabel = "s",
            tiles = emptyList(),
            bandHeight = 120.1f,
            sourceStartIndex = 0,
            sourceCount = 2
        )
        val fallbackRowWithDriftedHeight = RowItem(
            gridKey = "fallback-key",
            bucketIndex = 0,
            sectionLabel = "s",
            photos = emptyList(),
            rowHeight = 80.0f,
            isComplete = false,
            kind = RowItemKind.MOSAIC_FALLBACK,
            sourceStartIndex = 2,
            sourceCount = 3
        )

        val aligned = timelineGeometryAlignedItems(
            "2026-01",
            "s",
            0,
            "s",
            GeometryAlignedSection(
                listOf(
                    GeometryAlignedRange(ranges[0], bandWithDriftedHeight),
                    GeometryAlignedRange(ranges[1], fallbackRowWithDriftedHeight)
                )
            )
        )

        assertEquals(120.5f, (aligned[0] as MosaicBandItem).bandHeight)
        assertEquals(80.25f, (aligned[1] as RowItem).rowHeight)
    }

    private val identity = TimelineSkeletonIdentity(
        groupSize = TimelineGroupSize.MONTH,
        columnCount = 4,
        familiesKey = mosaicFamiliesKey(setOf(MosaicTemplateFamily.FOUR_TILE)),
        geometryRequest = TimelineMosaicGeometryRequest(400f, 1000f, GRID_SPACING_DP),
        groupingZoneId = ""
    )

    private val dayIdentity = TimelineSkeletonIdentity(
        groupSize = TimelineGroupSize.DAY,
        columnCount = 4,
        familiesKey = mosaicFamiliesKey(setOf(MosaicTemplateFamily.FOUR_TILE)),
        geometryRequest = TimelineMosaicGeometryRequest(400f, 1000f, GRID_SPACING_DP),
        groupingZoneId = "UTC"
    )

    private class CoordinatorHarness {
        val skeletonState = MutableStateFlow(TimelineSkeletonState())
        val bucketGeometryStates = MutableStateFlow<Map<MosaicCacheKey, Float>>(emptyMap())
        val mosaicGeometryStates = MutableStateFlow<Map<MosaicCacheKey, TimelineMosaicSectionGeometry>>(emptyMap())
        val timelineGeometryReady = MutableStateFlow(false)
        var geometryReadyBuckets: Set<MosaicCacheKey> = emptySet()
        val coordinator = TimelinePreparedGeometryCoordinator(
            skeletonState = skeletonState,
            bucketGeometryStates = bucketGeometryStates,
            mosaicGeometryStates = mosaicGeometryStates,
            timelineGeometryReady = timelineGeometryReady,
            updateGeometryReadyBuckets = { transform ->
                geometryReadyBuckets = transform(geometryReadyBuckets)
            },
            groupingZoneIdResolver = { group -> if (group == TimelineGroupSize.DAY) "UTC" else "" }
        )
    }

    private fun coordinatorHarness() = CoordinatorHarness()

    private fun range(start: Int, count: Int, height: Float) =
        MosaicSectionGeometryRange(sourceStartIndex = start, sourceCount = count, height = height)

    private fun section(sectionKey: String, vararg ranges: MosaicSectionGeometryRange) =
        sectionFor("2026-01", sectionKey, *ranges)

    private fun sectionFor(timeBucket: String, sectionKey: String, vararg ranges: MosaicSectionGeometryRange) =
        TimelineMosaicGeometrySummary(
            timeBucket = timeBucket,
            sectionKey = sectionKey,
            placeholderHeight = ranges.sumOf { it.height.toDouble() }.toFloat(),
            displayItemCount = ranges.size,
            ranges = ranges.toList()
        )

    private fun skeleton(timeBucket: String, height: Float) =
        TimelineMosaicBucketSkeleton(
            timeBucket = timeBucket,
            assetCount = 1,
            placeholderHeight = height,
            sections = listOf(sectionFor(timeBucket, "$timeBucket|month", range(0, 1, height)))
        )

    private fun daySkeleton(timeBucket: String, bucketHeight: Float, sectionHeight: Float) =
        TimelineMosaicBucketSkeleton(
            timeBucket = timeBucket,
            assetCount = 1,
            placeholderHeight = bucketHeight,
            sections = listOf(sectionFor(timeBucket, "$timeBucket|day|2026-01-01", range(0, 1, sectionHeight)))
        )
}
