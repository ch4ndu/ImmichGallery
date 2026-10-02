package com.udnahc.immichgallery.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf

enum class ViewerWindowPlacement { Floating, Maximized, Fullscreen }

/** Window-owned placement bridge. All actions run on the UI dispatcher. */
class ViewerFullscreenController(
    private val readPlacement: () -> ViewerWindowPlacement,
    private val writePlacement: (ViewerWindowPlacement) -> Unit,
    private val readWindowVisibility: () -> Boolean,
    val isSupported: Boolean = true,
) {
    private val sessions = mutableListOf<ViewerFullscreenSession>()
    private val leases = mutableListOf<ViewerFullscreenLease>()
    private var isClosed = false
    private var lastNonFullscreenPlacement = readPlacement().takeUnless {
        it == ViewerWindowPlacement.Fullscreen
    } ?: ViewerWindowPlacement.Floating

    val isFullscreen: Boolean get() = isSupported && readPlacement() == ViewerWindowPlacement.Fullscreen

    /** This is window visibility only, not application foreground/focus state. */
    val isWindowVisibleAndNotMinimized: Boolean get() = readWindowVisibility()

    fun createSession(parent: ViewerFullscreenSession? = null): ViewerFullscreenSession {
        require(parent == null || parent.controller === this)
        return ViewerFullscreenSession(this, parent).also { session ->
            session.isClosed = isClosed || !isSupported || parent?.isClosed == true
            if (!session.isClosed) sessions += session
        }
    }

    internal fun toggle(session: ViewerFullscreenSession): Boolean {
        if (!canChangePlacement(session)) return false
        ensureViewerLease(session)
        val current = readPlacement()
        if (current == ViewerWindowPlacement.Fullscreen) {
            val restore = session.manualRestorePlacement
                ?: leases.lastOrNull { it.entryPlacement != ViewerWindowPlacement.Fullscreen }?.entryPlacement
                ?: lastNonFullscreenPlacement
            writePlacement(restore)
        } else {
            lastNonFullscreenPlacement = current
            session.manualRestorePlacement = current
            writePlacement(ViewerWindowPlacement.Fullscreen)
        }
        return true
    }

    internal fun enterSlideshow(session: ViewerFullscreenSession): ViewerFullscreenLease? {
        if (!canChangePlacement(session)) return null
        session.slideshowLease?.takeUnless { it.isReleased }?.let { return it }
        ensureViewerLease(session)
        return acquireLease(session).also { lease ->
            session.slideshowLease = lease
            if (lease.entryPlacement != ViewerWindowPlacement.Fullscreen) {
                session.manualRestorePlacement = lease.entryPlacement
            }
            writePlacement(ViewerWindowPlacement.Fullscreen)
        }
    }

    internal fun release(lease: ViewerFullscreenLease) {
        if (lease.isReleased) return
        lease.isReleased = true
        restoreReleasedLeases()
    }

    internal fun close(session: ViewerFullscreenSession) {
        if (session.isClosed) return
        // Closing an owning viewer also invalidates every nested viewer callback.
        sessions.filter { it.isWithin(session) }.forEach { it.isClosed = true }
        sessions.removeAll { it.isClosed }
        leases.filter { it.session.isClosed }.forEach { it.isReleased = true }
        restoreReleasedLeases()
    }

    fun close() {
        isClosed = true
        sessions.forEach { it.isClosed = true }
        sessions.clear()
        leases.forEach { it.isReleased = true }
        restoreReleasedLeases()
    }

    private fun canChangePlacement(session: ViewerFullscreenSession): Boolean =
        isSupported && !session.isClosed && sessions.lastOrNull() === session

    private fun ensureViewerLease(session: ViewerFullscreenSession) {
        if (session.viewerLease == null) session.viewerLease = acquireLease(session)
    }

    private fun acquireLease(session: ViewerFullscreenSession): ViewerFullscreenLease {
        val placement = readPlacement()
        if (placement != ViewerWindowPlacement.Fullscreen) lastNonFullscreenPlacement = placement
        return ViewerFullscreenLease(this, session, placement, session.manualRestorePlacement).also { leases += it }
    }

    private fun restoreReleasedLeases() {
        var restore: ViewerWindowPlacement? = null
        // A covered parent's release waits for the nested owner's release. Never
        // overwrite either entry snapshot or briefly apply intermediate placements.
        while (leases.lastOrNull()?.isReleased == true) {
            val lease = leases.removeAt(leases.lastIndex)
            restore = lease.entryPlacement
            lease.session.manualRestorePlacement = lease.entryManualRestorePlacement
        }
        restore?.let { placement ->
            if (placement != ViewerWindowPlacement.Fullscreen) lastNonFullscreenPlacement = placement
            writePlacement(placement)
        }
    }
}

class ViewerFullscreenSession internal constructor(
    internal val controller: ViewerFullscreenController,
    private val parent: ViewerFullscreenSession?,
) : RememberObserver {
    internal var isClosed = false
    internal var viewerLease: ViewerFullscreenLease? = null
    internal var slideshowLease: ViewerFullscreenLease? = null
    internal var manualRestorePlacement: ViewerWindowPlacement? = null

    val isSupported: Boolean get() = controller.isSupported
    val isFullscreen: Boolean get() = controller.isFullscreen

    fun toggle(): Boolean = controller.toggle(this)

    /** Returns false when Escape should continue to the viewer dismissal action. */
    fun exitFullscreen(): Boolean = isFullscreen && controller.toggle(this)

    /** Retain this lease across pause/resume; release it when this show stops. */
    fun enterSlideshow(): ViewerFullscreenLease? = controller.enterSlideshow(this)

    fun close() = controller.close(this)

    override fun onRemembered() = Unit

    override fun onForgotten() = close()

    override fun onAbandoned() = close()

    internal fun isWithin(ancestor: ViewerFullscreenSession): Boolean =
        this === ancestor || parent?.isWithin(ancestor) == true
}

class ViewerFullscreenLease internal constructor(
    private val controller: ViewerFullscreenController,
    internal val session: ViewerFullscreenSession,
    internal val entryPlacement: ViewerWindowPlacement,
    internal val entryManualRestorePlacement: ViewerWindowPlacement?,
) {
    internal var isReleased = false

    fun release() = controller.release(this)
}

val LocalViewerFullscreen = staticCompositionLocalOf {
    ViewerFullscreenController(
        readPlacement = { ViewerWindowPlacement.Floating },
        writePlacement = {},
        readWindowVisibility = { true },
        isSupported = false,
    )
}

/** Provide the owning session around nested stack content to preserve its parent. */
val LocalViewerFullscreenSession = staticCompositionLocalOf<ViewerFullscreenSession?> { null }

@Composable
fun rememberViewerFullscreenSession(): ViewerFullscreenSession {
    val controller = LocalViewerFullscreen.current
    val parent = LocalViewerFullscreenSession.current
    return remember(controller, parent) { controller.createSession(parent) }
}
