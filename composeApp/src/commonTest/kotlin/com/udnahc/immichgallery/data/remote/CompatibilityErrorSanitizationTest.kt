package com.udnahc.immichgallery.data.remote

import com.udnahc.immichgallery.data.model.SearchResultAssets
import com.udnahc.immichgallery.data.model.nextPageNumberOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class CompatibilityErrorSanitizationTest {
    @Test
    fun invalidSearchTokenIsNotIncludedInFailureMessage() {
        val secretToken = "https://private.example/next?token=secret"

        val error = assertFailsWith<IllegalStateException> {
            SearchResultAssets(nextPage = secretToken).nextPageNumberOrNull()
        }

        assertEquals("Invalid Immich nextPage token", error.message)
        assertFalse(error.message.orEmpty().contains(secretToken))
    }

    @Test
    fun timelineCompatibilityFailureContainsOnlyStableMetadata() {
        val error = TimelineApiCompatibilityException(
            serverVersion = "v1.142.0",
            endpoint = "/api/timeline/bucket",
            category = TimelineCompatibilityCategory.SHAPE
        )

        assertEquals(
            "Immich v1.142.0 returned an incompatible Timeline SHAPE response " +
                "from /api/timeline/bucket",
            error.message
        )
        assertFalse(error.message.orEmpty().contains("http"))
        assertEquals(null, error.cause)
    }
}
