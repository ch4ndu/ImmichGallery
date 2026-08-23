package com.udnahc.immichgallery.di

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(DelicateCoilApi::class)
class CoilSingletonImageLoaderJvmTest {
    @Test
    fun desktopUsesStableImageLoaderContext() {
        val context = PlatformContext.INSTANCE

        assertSame(context, stableImageLoaderContext(context))
    }

    @Test
    fun controllerReplacesActualCoilSingletonSlot() = runTest {
        val context = PlatformContext.INSTANCE
        val preExisting = ImageLoader.Builder(context).build()
        val initial = ImageLoader.Builder(context).build()
        val replacement = ImageLoader.Builder(context).build()
        val restored = ImageLoader.Builder(context).build()
        val controller = createCoilImageCacheController()
        controller.close()
        SingletonImageLoader.setUnsafe(preExisting)
        try {
            controller.registerInitial(
                createInitial = { ManagedImageLoader(initial) },
                createReplacement = { ManagedImageLoader(replacement) }
            )
            assertSame(initial, SingletonImageLoader.get(context))

            controller.rotateAndClearAround {}

            assertSame(replacement, SingletonImageLoader.get(context))
        } finally {
            controller.close()
            preExisting.shutdown()
            preExisting.diskCache?.shutdown()
            SingletonImageLoader.setUnsafe(restored)
        }
    }

    @Test
    fun managedLoaderClosesLoaderDiskCacheAndOwnedResourcesInOrder() {
        val context = PlatformContext.INSTANCE
        val imageLoader = ImageLoader.Builder(context).build()
        val events = mutableListOf<String>()
        val managed = ManagedImageLoader(
            imageLoader = imageLoader,
            stopImageLoader = { events += "loader" },
            closeDiskCache = { events += "disk" },
            closeOwnedResources = { events += "network" }
        )

        managed.shutdown()

        assertEquals(listOf("loader", "disk", "network"), events)
        imageLoader.shutdown()
        imageLoader.diskCache?.shutdown()
    }
}
