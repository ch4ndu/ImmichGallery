package com.udnahc.immichgallery.ui.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ViewerFullscreenTest {
    @Test
    fun slideshowRestoresEveryExactEntryPlacement() {
        ViewerWindowPlacement.entries.forEach { entry ->
            val window = FakeWindow(entry)
            val session = window.controller.createSession()

            val show = assertNotNull(session.enterSlideshow())
            assertEquals(ViewerWindowPlacement.Fullscreen, window.placement)
            show.release()
            assertEquals(entry, window.placement)
            session.close()
            assertEquals(entry, window.placement)
        }
    }

    @Test
    fun manualToggleRestoresFloatingAndMaximizedPlacement() {
        listOf(ViewerWindowPlacement.Floating, ViewerWindowPlacement.Maximized).forEach { entry ->
            val window = FakeWindow(entry)
            val session = window.controller.createSession()

            assertTrue(session.toggle())
            assertTrue(session.isFullscreen)
            assertTrue(session.exitFullscreen())
            assertEquals(entry, window.placement)
            assertFalse(session.exitFullscreen())
            session.close()
            assertEquals(entry, window.placement)
        }
    }

    @Test
    fun closingManualFullscreenOwnerRestoresItsEntry() {
        val window = FakeWindow(ViewerWindowPlacement.Maximized)
        val session = window.controller.createSession()
        session.toggle()

        session.close()

        assertEquals(ViewerWindowPlacement.Maximized, window.placement)
    }

    @Test
    fun initiallyFullscreenViewerRestoresFullscreenAfterManualExit() {
        val window = FakeWindow(ViewerWindowPlacement.Fullscreen)
        val session = window.controller.createSession()

        assertTrue(session.exitFullscreen())
        assertEquals(ViewerWindowPlacement.Floating, window.placement)
        session.close()
        assertEquals(ViewerWindowPlacement.Fullscreen, window.placement)
    }

    @Test
    fun stoppingShowPreservesPreviouslyEnteredManualFullscreen() {
        val window = FakeWindow(ViewerWindowPlacement.Maximized)
        val session = window.controller.createSession()
        session.toggle()
        val show = assertNotNull(session.enterSlideshow())

        show.release()

        assertTrue(session.isFullscreen)
        session.close()
        assertEquals(ViewerWindowPlacement.Maximized, window.placement)
    }

    @Test
    fun manualChangesDuringShowDoNotReplaceItsRestoreTarget() {
        val window = FakeWindow(ViewerWindowPlacement.Maximized)
        val session = window.controller.createSession()
        val show = assertNotNull(session.enterSlideshow())
        session.toggle()
        window.placement = ViewerWindowPlacement.Floating
        session.toggle()

        show.release()

        assertEquals(ViewerWindowPlacement.Maximized, window.placement)
    }

    @Test
    fun stoppingShowAlsoRestoresManualFullscreenExitTarget() {
        val window = FakeWindow(ViewerWindowPlacement.Maximized)
        val session = window.controller.createSession()
        session.toggle()
        val show = assertNotNull(session.enterSlideshow())
        session.toggle()
        window.placement = ViewerWindowPlacement.Floating
        session.toggle()

        show.release()
        assertTrue(session.isFullscreen)
        session.toggle()

        assertEquals(ViewerWindowPlacement.Maximized, window.placement)
    }

    @Test
    fun fullscreenExitDuringShowUsesShowEntryAfterEarlierManualToggle() {
        val window = FakeWindow(ViewerWindowPlacement.Floating)
        val session = window.controller.createSession()
        session.toggle()
        session.toggle()
        window.placement = ViewerWindowPlacement.Maximized
        session.enterSlideshow()

        session.exitFullscreen()

        assertEquals(ViewerWindowPlacement.Maximized, window.placement)
    }

    @Test
    fun nestedShowRestoresParentFullscreenBeforeParentRestoresItsEntry() {
        val window = FakeWindow(ViewerWindowPlacement.Maximized)
        val parent = window.controller.createSession()
        val parentShow = assertNotNull(parent.enterSlideshow())
        val child = window.controller.createSession(parent)
        val childShow = assertNotNull(child.enterSlideshow())

        childShow.release()
        child.close()
        assertTrue(parent.isFullscreen)
        parentShow.release()
        assertEquals(ViewerWindowPlacement.Maximized, window.placement)
        parent.close()
        assertEquals(ViewerWindowPlacement.Maximized, window.placement)
    }

    @Test
    fun nestedManualToggleCannotOverwriteParentManualRestoreTarget() {
        val window = FakeWindow(ViewerWindowPlacement.Floating)
        val parent = window.controller.createSession()
        parent.toggle()
        val child = window.controller.createSession(parent)
        child.toggle()
        window.placement = ViewerWindowPlacement.Maximized
        child.toggle()

        child.close()
        assertTrue(parent.isFullscreen)
        parent.toggle()

        assertEquals(ViewerWindowPlacement.Floating, window.placement)
    }

    @Test
    fun coveredParentCannotToggleOrAcquireAnotherShow() {
        val window = FakeWindow(ViewerWindowPlacement.Floating)
        val parent = window.controller.createSession()
        val child = window.controller.createSession(parent)

        assertFalse(parent.toggle())
        assertNull(parent.enterSlideshow())
        assertEquals(0, window.writes.size)
        child.close()
        assertTrue(parent.toggle())
    }

    @Test
    fun outOfOrderShowReleaseWaitsForNestedOwner() {
        val window = FakeWindow(ViewerWindowPlacement.Maximized)
        val parent = window.controller.createSession()
        val parentShow = assertNotNull(parent.enterSlideshow())
        val child = window.controller.createSession(parent)
        val childShow = assertNotNull(child.enterSlideshow())

        parentShow.release()
        assertTrue(parent.isFullscreen)
        childShow.release()
        assertTrue(parent.isFullscreen)
        child.close()

        assertEquals(ViewerWindowPlacement.Maximized, window.placement)
    }

    @Test
    fun ownerCleanupRestoresOnceAndInvalidatesNestedCallbacks() {
        val window = FakeWindow(ViewerWindowPlacement.Maximized)
        val parent = window.controller.createSession()
        val parentShow = assertNotNull(parent.enterSlideshow())
        val child = window.controller.createSession(parent)
        val childShow = assertNotNull(child.enterSlideshow())
        child.toggle()
        window.writes.clear()

        parent.close()

        assertEquals(listOf(ViewerWindowPlacement.Maximized), window.writes)
        parentShow.release()
        childShow.release()
        child.close()
        parent.close()
        assertFalse(child.toggle())
        assertNull(parent.enterSlideshow())
        assertFalse(window.controller.createSession(parent).toggle())
        assertEquals(listOf(ViewerWindowPlacement.Maximized), window.writes)
    }

    @Test
    fun repeatedAcquisitionAndStaleReleaseCannotAffectNewShow() {
        val window = FakeWindow(ViewerWindowPlacement.Floating)
        val session = window.controller.createSession()
        val first = assertNotNull(session.enterSlideshow())
        assertSame(first, session.enterSlideshow())
        first.release()
        val second = assertNotNull(session.enterSlideshow())

        first.release()
        assertTrue(session.isFullscreen)
        second.release()
        assertEquals(ViewerWindowPlacement.Floating, window.placement)
    }

    @Test
    fun windowHostDisposalRestoresAndRejectsAllFutureOwners() {
        val window = FakeWindow(ViewerWindowPlacement.Maximized)
        val session = window.controller.createSession()
        session.enterSlideshow()

        window.controller.close()

        assertEquals(ViewerWindowPlacement.Maximized, window.placement)
        assertFalse(session.toggle())
        assertNull(window.controller.createSession().enterSlideshow())
    }

    @Test
    fun unusedViewerDoesNotUndoNativeWindowChanges() {
        val window = FakeWindow(ViewerWindowPlacement.Floating)
        val session = window.controller.createSession()
        window.placement = ViewerWindowPlacement.Maximized

        session.close()

        assertEquals(ViewerWindowPlacement.Maximized, window.placement)
        assertTrue(window.writes.isEmpty())
    }

    @Test
    fun abandonedCompositionCannotKeepParentCovered() {
        val window = FakeWindow(ViewerWindowPlacement.Floating)
        val parent = window.controller.createSession()
        val child = window.controller.createSession(parent)

        child.onAbandoned()

        assertTrue(parent.toggle())
        assertFalse(child.toggle())
    }

    @Test
    fun forgottenCompositionRestoresOwnedPlacement() {
        val window = FakeWindow(ViewerWindowPlacement.Maximized)
        val session = window.controller.createSession()
        session.enterSlideshow()

        session.onForgotten()

        assertEquals(ViewerWindowPlacement.Maximized, window.placement)
        assertFalse(session.toggle())
    }

    @Test
    fun unsupportedHostHasNoPlacementActions() {
        val window = FakeWindow(ViewerWindowPlacement.Floating, supported = false)
        val session = window.controller.createSession()

        assertFalse(session.isSupported)
        assertFalse(session.isFullscreen)
        assertFalse(session.toggle())
        assertFalse(session.exitFullscreen())
        assertNull(session.enterSlideshow())
        session.close()
        assertTrue(window.writes.isEmpty())
    }

    @Test
    fun windowVisibilitySignalIsReadIndependentlyFromFullscreen() {
        val window = FakeWindow(ViewerWindowPlacement.Maximized)
        val session = window.controller.createSession()
        session.toggle()
        assertTrue(window.controller.isWindowVisibleAndNotMinimized)

        window.visibleAndNotMinimized = false

        assertFalse(window.controller.isWindowVisibleAndNotMinimized)
        assertTrue(session.isFullscreen)
    }

    private class FakeWindow(
        var placement: ViewerWindowPlacement,
        supported: Boolean = true,
    ) {
        var visibleAndNotMinimized = true
        val writes = mutableListOf<ViewerWindowPlacement>()
        val controller = ViewerFullscreenController(
            readPlacement = { placement },
            writePlacement = { placement = it; writes += it },
            readWindowVisibility = { visibleAndNotMinimized },
            isSupported = supported,
        )
    }
}
