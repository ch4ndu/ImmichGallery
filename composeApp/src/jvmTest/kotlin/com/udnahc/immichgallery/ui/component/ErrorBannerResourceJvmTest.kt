package com.udnahc.immichgallery.ui.component

import immichgallery.composeapp.generated.resources.Res
import immichgallery.composeapp.generated.resources.date_group_day
import immichgallery.composeapp.generated.resources.date_month_january
import immichgallery.composeapp.generated.resources.error_banner_last_updated
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals

class ErrorBannerResourceJvmTest {
    @Test
    fun statusAndDateOrderComeFromResources() = runTest {
        val date = getString(
            Res.string.date_group_day,
            1,
            getString(Res.string.date_month_january),
            1970
        )

        assertEquals(
            "Cannot connect. Last updated 1 January 1970",
            getString(
                Res.string.error_banner_last_updated,
                "Cannot connect",
                date
            )
        )
    }
}
