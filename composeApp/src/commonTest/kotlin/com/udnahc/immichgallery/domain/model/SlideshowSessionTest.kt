package com.udnahc.immichgallery.domain.model

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SlideshowSessionTest {
    private val assets = listOf("a", "b", "c", "d").map { SlideshowCandidate(SlideshowSlot.Asset(it)) }

    @Test
    fun shuffleVisitsEveryItemOnceAndDoesNotRepeatAtCycleBoundary() {
        for (seed in 0..20) {
            val session = SlideshowSession(assets, order = SlideshowOrder.RANDOM, random = Random(seed))
            var cycle = listOf(session.current) + List(assets.size - 1) { session.next() }
            assertEquals(assets.toSet(), cycle.toSet())
            repeat(5) {
                val previousLast = cycle.last()
                cycle = List(assets.size) { session.next() }
                assertNotEquals(previousLast, cycle.first())
                assertEquals(assets.toSet(), cycle.toSet())
            }
        }
    }

    @Test
    fun previousAndNextReplayDisplayedHistoryBeforeChoosingNewItems() {
        val session = SlideshowSession(assets, order = SlideshowOrder.RANDOM, random = Random(9))
        val first = session.current
        val second = session.next()
        val third = session.next()
        val upcoming = assertNotNull(session.upcoming())
        val fourth = session.candidate(upcoming)

        assertEquals(second, session.previous())
        assertEquals(first, session.previous())
        assertFalse(session.canGoPrevious)
        assertEquals(first, session.previous())
        assertNull(session.next(upcoming))
        assertEquals(second, session.next())
        assertEquals(third, session.next())
        assertEquals(fourth, session.next())
        assertEquals(assets.toSet(), setOf(first, second, third, fourth))
    }

    @Test
    fun materializingUpcomingBucketRetainsTargetCycleAndHistory() {
        val unresolved = List(6) { SlideshowCandidate(SlideshowSlot.BucketOffset("2026-10", it)) }
        val session = SlideshowSession(unresolved, order = SlideshowOrder.RANDOM, random = Random(4))
        val control = SlideshowSession(unresolved, order = SlideshowOrder.RANDOM, random = Random(4))
        session.next()
        control.next()
        val second = session.current
        val upcoming = assertNotNull(session.upcoming())
        val resolved = unresolved.mapIndexed { index, item -> item.copy(assetId = "asset-$index") }

        session.reconcileSource(resolved)

        assertEquals(upcoming, session.upcoming())
        assertTrue(session.isCurrent(upcoming))
        assertNotNull(session.candidate(upcoming)?.assetId)
        assertEquals(second?.slot, session.current?.slot)
        assertNotNull(session.current?.assetId)
        assertEquals(control.previous()?.slot, session.previous()?.slot)
        assertEquals(control.next()?.slot, session.next()?.slot)
        repeat(12) { assertEquals(control.next()?.slot, session.next()?.slot) }
    }

    @Test
    fun replayingHistoryAcrossCycleBoundaryDoesNotConsumeNewCycleBag() {
        val session = SlideshowSession(assets, order = SlideshowOrder.RANDOM, random = Random(6))
        val control = SlideshowSession(assets, order = SlideshowOrder.RANDOM, random = Random(6))
        repeat(assets.size) {
            session.next()
            control.next()
        }
        repeat(3) { session.previous() }
        repeat(3) { session.next() }
        repeat(assets.size * 3) { assertEquals(control.next(), session.next()) }
    }

    @Test
    fun sourceReorderingKeepsAssetIdentityAndRejectsOldUpcomingTarget() {
        val slots = List(3) { SlideshowSlot.BucketOffset("2026-10", it) }
        val initial = slots.zip(listOf("a", "b", "c")) { slot, id -> SlideshowCandidate(slot, id) }
        val session = SlideshowSession(initial, initialSlot = slots[1], order = SlideshowOrder.RANDOM)
        val stale = assertNotNull(session.upcoming())
        session.reconcileSource(slots.zip(listOf("b", "c", "a")) { slot, id -> SlideshowCandidate(slot, id) })

        assertEquals("b", session.current?.assetId)
        assertEquals(slots[0], session.current?.slot)
        assertEquals(0, session.currentPage)
        assertFalse(session.isCurrent(stale))
        assertNull(session.next(stale))
        assertNotEquals("b", session.next()?.assetId)
    }

    @Test
    fun sourceGrowthAndRemovalKeepSurvivingCurrentAndUsableHistory() {
        val session = SlideshowSession(assets.take(3))
        session.next()
        session.next()
        session.reconcileSource(assets)
        assertEquals("c", session.current?.assetId)
        assertEquals("b", session.previous()?.assetId)
        assertEquals("c", session.next()?.assetId)
        assertEquals("d", session.next()?.assetId)
        session.reconcileSource(assets.take(3))
        assertEquals("c", session.current?.assetId)
        assertEquals("a", session.next()?.assetId)
        session.reconcileSource(emptyList())
        assertNull(session.current)
        assertNull(session.upcoming())
        assertNull(session.next())
        session.reconcileSource(assets)
        assertEquals("a", session.current?.assetId)
    }

    @Test
    fun explicitOrderRevisionInvalidatesUnresolvedTargets() {
        val candidate = SlideshowCandidate(SlideshowSlot.BucketOffset("2026-10", 0))
        val session = SlideshowSession(listOf(candidate))
        val stale = assertNotNull(session.upcoming())
        session.reconcileSource(listOf(candidate), sourceRevision = 1)
        assertNull(session.candidate(stale))
        assertNotEquals(stale, session.upcoming())
    }

    @Test
    fun removingRandomCurrentRetainsPreviousAndForwardDisplayedHistory() {
        val session = SlideshowSession(assets, order = SlideshowOrder.RANDOM, random = Random(7))
        assets.drop(1).forEach { session.select(it.slot) }
        val stale = assertNotNull(session.upcoming())

        session.reconcileSource(assets.take(3))

        assertEquals("c", session.current?.assetId)
        assertTrue(session.canGoPrevious)
        assertFalse(session.isCurrent(stale))
        assertNull(session.next(stale))
        assertEquals("b", session.previous()?.assetId)
        assertEquals("a", session.previous()?.assetId)
        assertEquals("b", session.next()?.assetId)
        assertEquals("c", session.next()?.assetId)
        assertNotEquals("d", session.next()?.assetId)
        session.reconcileSource(emptyList())
        assertNull(session.current)
        assertFalse(session.canGoPrevious)
        assertNull(session.next())
    }

    @Test
    fun undisplayedReplacementIsInsertedBetweenSurvivingPastAndForwardHistory() {
        val session = SlideshowSession(assets, order = SlideshowOrder.RANDOM, random = Random(7))
        session.select(assets[3].slot)
        session.select(assets[1].slot)
        assertEquals("d", session.previous()?.assetId)
        val replacement = SlideshowCandidate(SlideshowSlot.Asset("e"))

        session.reconcileSource(assets.take(3) + replacement)

        assertEquals("e", session.current?.assetId)
        assertEquals("a", session.previous()?.assetId)
        assertEquals("e", session.next()?.assetId)
        assertEquals("b", session.next()?.assetId)
    }

    @Test
    fun sequentialWrapAndManualSelectionPreserveHistory() {
        val session = SlideshowSession(assets)
        assertEquals("d", session.previous()?.assetId)
        assertEquals("a", session.next()?.assetId)
        assertEquals("b", session.next()?.assetId)
        session.select(assets[3].slot)
        assertEquals("b", session.previous()?.assetId)
        assertEquals("d", session.next()?.assetId)
        assertEquals("a", session.next()?.assetId)
    }

    @Test
    fun emptyAndSingleSourcesHaveNoInvalidSelections() {
        for (order in SlideshowOrder.entries) {
            val empty = SlideshowSession(emptyList(), order = order)
            assertNull(empty.current)
            assertNull(empty.upcoming())
            assertNull(empty.previous())
            val single = SlideshowSession(assets.take(1), order = order)
            repeat(5) { assertEquals(assets[0], single.next()) }
            assertEquals(assets[0], single.previous())
        }
    }
    @Test
    fun uncommittedInitialSelectionStaysOutOfHistoryAcrossSourceReconciliation() {
        val lazy = SlideshowCandidate(SlideshowSlot.BucketOffset("lazy", 0))
        val session = SlideshowSession(assets + lazy, lazy.slot, SlideshowOrder.RANDOM, recordInitial = false)
        session.reconcileSource(assets + lazy)
        assertNull(session.current)
        assertFalse(session.canGoPrevious)
        session.reconcileSource(assets.reversed() + lazy.copy(assetId = "resolved"))
        assertNull(session.current)
        session.select(lazy.slot)
        assertEquals("resolved", session.current?.assetId)
        assertFalse(session.canGoPrevious)
    }

    @Test
    fun reservedBackwardCandidatesDoNotBecomeVisitsUntilCommitted() {
        val session = SlideshowSession(assets)
        val rejected = assertNotNull(session.previousTarget())
        assertEquals("d", session.candidate(rejected)?.assetId)
        assertEquals("a", session.current?.assetId)
        session.exclude(rejected.slot)
        val selected = assertNotNull(session.previousTarget(before = rejected.slot))
        assertEquals("c", session.previous(selected)?.assetId)
        assertEquals("a", session.next()?.assetId)
        assertEquals("c", session.previous()?.assetId)
    }

}
