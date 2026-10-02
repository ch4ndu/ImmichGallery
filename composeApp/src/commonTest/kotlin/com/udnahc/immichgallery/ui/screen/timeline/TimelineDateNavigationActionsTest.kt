package com.udnahc.immichgallery.ui.screen.timeline

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TimelineDateNavigationActionsTest {
    @Test
    fun loadingEmptySourceWaitsButReadyEmptySourceAcceptsExternalDates() {
        assertFalse(timelineDateActionsAvailable(totalPages = 0, isSourceReady = false, isLoading = false))
        assertFalse(timelineDateActionsAvailable(totalPages = 0, isSourceReady = true, isLoading = true))
        assertTrue(timelineDateActionsAvailable(totalPages = 0, isSourceReady = true, isLoading = false))
        assertTrue(timelineDateActionsAvailable(totalPages = 5, isSourceReady = true, isLoading = true))
    }

    @Test
    fun readyEmptyActionsAcceptDateWithoutOfferingDateMenu() {
        val dates = mutableListOf<LocalDate>()
        val actions = TimelineDateNavigationActions(
            openPicker = {},
            backToToday = {},
            canOpenDateMenu = false,
            onShowDate = dates::add
        )
        val date = LocalDate(2024, 3, 10)

        assertFalse(actions.showDate(date))
        actions.register()

        assertFalse(actions.canOpenDateMenu)
        assertTrue(actions.showDate(date))
        assertEquals(listOf(date), dates)
    }

    @Test
    fun unregisteredActionsRejectLateDatesAndCancelOnce() {
        val dates = mutableListOf<LocalDate>()
        var cancellations = 0
        val actions = TimelineDateNavigationActions(
            openPicker = {},
            backToToday = {},
            onShowDate = dates::add,
            cancel = { cancellations++ }
        )
        val date = LocalDate(2024, 3, 10)
        actions.register()
        actions.showDate(date)

        actions.unregister()
        actions.unregister()

        assertFalse(actions.showDate(date))
        assertEquals(listOf(date), dates)
        assertEquals(1, cancellations)
    }

    @Test
    fun newRegistrationDoesNotReactivateDisplacedActionObject() {
        var deliveries = 0
        fun actions() = TimelineDateNavigationActions({}, {}, onShowDate = { deliveries++ })
        val old = actions()
        old.register()
        old.unregister()
        val current = actions()
        current.register()
        val date = LocalDate(2024, 3, 10)

        assertFalse(old.showDate(date))
        assertTrue(current.showDate(date))
        assertEquals(1, deliveries)
    }
}
