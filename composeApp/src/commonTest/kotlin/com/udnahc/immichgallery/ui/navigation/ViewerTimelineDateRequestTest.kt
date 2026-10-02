package com.udnahc.immichgallery.ui.navigation

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ViewerTimelineDateRequestTest {
    private val date = LocalDate(2024, 3, 10)

    @Test
    fun repeatedIdenticalDatesCreateDistinctRequests() {
        val requests = ViewerTimelineDateRequests(initiallyActive = true)
        requests.submit(date, requests.sessionEpoch)
        val first = assertNotNull(requests.pending)

        requests.submit(date, requests.sessionEpoch)
        val second = assertNotNull(requests.pending)

        assertEquals(first.date, second.date)
        assertNotEquals(first.id, second.id)
        assertFalse(requests.isCurrent(first.id))
        assertTrue(requests.isCurrent(second.id))
    }

    @Test
    fun staleAcknowledgementCannotClearReplacement() {
        val requests = ViewerTimelineDateRequests(initiallyActive = true)
        requests.submit(date, requests.sessionEpoch)
        val first = assertNotNull(requests.pending)
        requests.submit(LocalDate(2023, 1, 1), requests.sessionEpoch)
        val replacement = requests.pending

        requests.acknowledge(first.id)

        assertEquals(replacement, requests.pending)
    }

    @Test
    fun logoutInvalidatesBothPendingRequestsAndCapturedCallbacks() {
        val requests = ViewerTimelineDateRequests(initiallyActive = true)
        val epoch = requests.sessionEpoch
        requests.submit(date, epoch)
        val first = assertNotNull(requests.pending)

        requests.endSession()

        assertNull(requests.pending)
        assertFalse(requests.isCurrent(first.id))
        assertFalse(requests.submit(date, epoch))
        assertFalse(requests.submit(date, requests.sessionEpoch))
    }

    @Test
    fun failedLogoutResumesOnlyNewEpochCallbacks() {
        val requests = ViewerTimelineDateRequests(initiallyActive = true)
        val oldEpoch = requests.sessionEpoch
        requests.submit(date, oldEpoch)
        val oldId = assertNotNull(requests.pending).id
        requests.endSession()

        requests.beginSession()

        assertFalse(requests.submit(date, oldEpoch))
        assertTrue(requests.submit(date, requests.sessionEpoch))
        assertNotEquals(oldId, assertNotNull(requests.pending).id)
    }

    @Test
    fun loginIsRequiredForInitiallyInactiveNavigation() {
        val requests = ViewerTimelineDateRequests(initiallyActive = false)
        assertFalse(requests.submit(date, requests.sessionEpoch))

        requests.beginSession()

        assertTrue(requests.submit(date, requests.sessionEpoch))
    }

    @Test
    fun unavailableActionsRetainThePendingRequest() {
        val requests = readyRequest()
        val pending = requests.pending

        assertFalse(deliver(requests, showDate = null))

        assertEquals(pending, requests.pending)
    }

    @Test
    fun rejectedStaleActionsDoNotAcknowledgeTheRequest() {
        val requests = readyRequest()
        val pending = requests.pending

        assertFalse(deliver(requests, showDate = { false }))

        assertEquals(pending, requests.pending)
    }

    @Test
    fun acceptedRequestIsDeliveredOnceDespiteQueuedOldSnapshot() {
        val requests = readyRequest()
        val snapshot = requests.pending
        var deliveries = 0
        val showDate: (LocalDate) -> Boolean = { deliveries++; true }

        assertTrue(deliver(requests, request = snapshot, showDate = showDate))
        assertFalse(deliver(requests, request = snapshot, showDate = showDate))

        assertEquals(1, deliveries)
        assertNull(requests.pending)
    }

    @Test
    fun deliveryWaitsForSelectedTimelineUncoveredViewerAndNoLogout() {
        val requests = readyRequest()
        var deliveries = 0
        val showDate: (LocalDate) -> Boolean = { deliveries++; true }

        assertFalse(deliver(requests, timelineSelected = false, showDate = showDate))
        assertFalse(deliver(requests, viewerActive = true, showDate = showDate))
        assertFalse(deliver(requests, loggingOut = true, showDate = showDate))
        assertEquals(0, deliveries)
        assertNotNull(requests.pending)
        assertTrue(deliver(requests, showDate = showDate))
        assertEquals(1, deliveries)
    }

    @Test
    fun mainBehindDetailReceivesNoRequestAndCannotConsumeRootPending() {
        val requests = readyRequest()
        val pending = requests.pending

        assertFalse(deliver(requests, request = null, showDate = { error("Main is not current") }))

        assertEquals(pending, requests.pending)
    }

    @Test
    fun logoutRejectsAnEffectAlreadyQueuedWithOldScreenState() {
        val requests = readyRequest()
        val queued = requests.pending
        requests.endSession()

        assertFalse(deliver(
            requests,
            request = queued,
            loggingOut = false,
            showDate = { error("A queued effect must recheck current root ownership") }
        ))
    }

    @Test
    fun replacementRejectsAnEffectAlreadyQueuedForOlderDate() {
        val requests = readyRequest()
        val queued = requests.pending
        requests.submit(LocalDate(2024, 4, 10), requests.sessionEpoch)
        val replacement = requests.pending

        assertFalse(deliver(requests, request = queued, showDate = { error("Superseded") }))

        assertEquals(replacement, requests.pending)
    }

    @Test
    fun replacementDuringAcceptanceSurvivesOldAcknowledgement() {
        val requests = readyRequest()
        val nextDate = LocalDate(2024, 4, 10)

        assertTrue(deliver(requests, showDate = {
            requests.submit(nextDate, requests.sessionEpoch)
            true
        }))

        assertEquals(nextDate, assertNotNull(requests.pending).date)
    }

    private fun readyRequest(): ViewerTimelineDateRequests =
        ViewerTimelineDateRequests(initiallyActive = true).also { it.submit(date, it.sessionEpoch) }

    private fun deliver(
        requests: ViewerTimelineDateRequests,
        request: ViewerTimelineDateRequest? = requests.pending,
        timelineSelected: Boolean = true,
        viewerActive: Boolean = false,
        loggingOut: Boolean = false,
        showDate: ((LocalDate) -> Boolean)?
    ): Boolean = deliverViewerTimelineDateRequest(
        request = request,
        timelineSelected = timelineSelected,
        viewerActive = viewerActive,
        loggingOut = loggingOut,
        isCurrent = requests::isCurrent,
        showDate = showDate,
        onHandled = requests::acknowledge
    )
}
