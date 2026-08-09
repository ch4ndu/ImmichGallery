package com.udnahc.immichgallery.ui.component

import kotlin.test.Test
import kotlin.test.assertEquals

class StaticPhotoOverlayTest {
    @Test
    fun remainingAssetCountExcludesTheSettledAsset() {
        assertEquals(4, remainingAssetCount(settledPage = 0, totalAssets = 5))
        assertEquals(2, remainingAssetCount(settledPage = 2, totalAssets = 5))
        assertEquals(0, remainingAssetCount(settledPage = 4, totalAssets = 5))
        assertEquals(0, remainingAssetCount(settledPage = 8, totalAssets = 5))
        assertEquals(0, remainingAssetCount(settledPage = 0, totalAssets = 0))
    }
}
