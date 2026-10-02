package com.udnahc.immichgallery.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import com.udnahc.immichgallery.ui.screen.timeline.TimelineDateNavigationActions
import kotlinx.datetime.LocalDate

data class ViewerTimelineDateRequest(val id: Long, val date: LocalDate)

/** Ephemeral root-navigation state; never retained across a login session. */
internal class ViewerTimelineDateRequests(initiallyActive: Boolean) {
    var pending by mutableStateOf<ViewerTimelineDateRequest?>(null)
        private set
    var sessionEpoch by mutableLongStateOf(0L)
        private set
    private var acceptingRequests = initiallyActive
    private var nextRequestId = 0L

    fun submit(date: LocalDate, originatingEpoch: Long): Boolean {
        if (!acceptingRequests || originatingEpoch != sessionEpoch) return false
        pending = ViewerTimelineDateRequest(++nextRequestId, date)
        return true
    }

    fun acknowledge(id: Long) {
        if (pending?.id == id) pending = null
    }

    fun isCurrent(id: Long): Boolean = acceptingRequests && pending?.id == id

    fun endSession() {
        sessionEpoch++
        acceptingRequests = false
        pending = null
    }

    fun beginSession() {
        sessionEpoch++
        acceptingRequests = true
        pending = null
    }
}

internal fun showViewerDateInTimeline(
    navController: NavHostController,
    requests: ViewerTimelineDateRequests,
    originatingEpoch: Long,
    date: LocalDate
) {
    if (requests.submit(date, originatingEpoch) &&
        navController.currentDestination?.hasRoute(MainRoute::class) != true
    ) {
        navController.popBackStack(MainRoute, inclusive = false)
    }
}

@Composable
internal fun ViewerTimelineDateRequestEffects(
    navController: NavHostController,
    request: ViewerTimelineDateRequest?,
    timelineSelected: Boolean,
    viewerActive: Boolean,
    loggingOut: Boolean,
    actions: TimelineDateNavigationActions?,
    isCurrent: (Long) -> Boolean,
    onHandled: (Long) -> Unit
) {
    LaunchedEffect(request?.id, timelineSelected, loggingOut) {
        if (request != null && isCurrent(request.id) && !timelineSelected && !loggingOut) {
            navController.selectMainTab(TimelineRoute)
        }
    }
    LaunchedEffect(request?.id, timelineSelected, viewerActive, loggingOut, actions) {
        deliverViewerTimelineDateRequest(
            request = request,
            timelineSelected = timelineSelected,
            viewerActive = viewerActive,
            loggingOut = loggingOut,
            isCurrent = isCurrent,
            showDate = actions?.let { it::showDate },
            onHandled = onHandled
        )
    }
}

internal fun deliverViewerTimelineDateRequest(
    request: ViewerTimelineDateRequest?,
    timelineSelected: Boolean,
    viewerActive: Boolean,
    loggingOut: Boolean,
    isCurrent: (Long) -> Boolean,
    showDate: ((LocalDate) -> Boolean)?,
    onHandled: (Long) -> Unit
): Boolean {
    if (request == null || !timelineSelected || viewerActive || loggingOut) return false
    if (!isCurrent(request.id)) return false
    if (showDate?.invoke(request.date) != true) return false
    onHandled(request.id)
    return true
}
