package com.udnahc.immichgallery.ui.component

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

class ErrorBannerTest {
    @Test
    fun lastSyncedDateUsesTheRequestedTimeZone() {
        assertEquals(
            LocalDate(1970, 1, 1),
            lastSyncedLocalDate(epochMillis = 0L, timeZone = TimeZone.UTC)
        )
    }

}
