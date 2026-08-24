package com.udnahc.immichgallery.ui.component

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AssetPageContentTest {
    @Test
    fun stillImageLoadStateIsScopedByUrlAndRetryNonce() {
        val initial = stillImageLoadStateForUrl(current = null, url = "url-1")

        assertEquals(StillImageLoadState(url = "url-1"), initial)

        val failed = initial.markError(initial.requestIdentity)
        assertTrue(failed.hasError)

        // The stable StillAssetPage owner uses the same URL-keyed state when
        // switching between drag and steady rendering branches.
        assertEquals(failed, stillImageLoadStateForUrl(failed, "url-1"))

        val retry = failed.retry("url-1")
        assertEquals(1, retry.retryNonce)
        assertFalse(retry.hasError)
        assertEquals(
            retry,
            retry.markError(initial.requestIdentity),
            "A callback from the prior retry nonce must not overwrite the active request"
        )
        assertTrue(retry.markError(retry.requestIdentity).hasError)

        val sameUrl = stillImageLoadStateForUrl(retry, "url-1")
        assertEquals(retry, sameUrl)

        val newUrl = stillImageLoadStateForUrl(retry, "url-2")
        assertEquals(StillImageLoadState(url = "url-2"), newUrl)
    }
}
