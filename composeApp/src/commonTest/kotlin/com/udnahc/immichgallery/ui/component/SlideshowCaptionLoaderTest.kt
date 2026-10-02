package com.udnahc.immichgallery.ui.component

import com.udnahc.immichgallery.domain.model.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SlideshowCaptionLoaderTest {
    @Test fun onlyCurrentEnabledSlideFetchesAndCompletedVisitsReuseViewerCache() = runTest {
        val reads = mutableListOf<String>()
        val loader = SlideshowCaptionLoader(this, StandardTestDispatcher(testScheduler)) {
            reads += it
            Result.success(detail(it))
        }
        loader.select(request("a", 1), enabled = false)
        runCurrent()
        assertTrue(reads.isEmpty())
        loader.select(request("a", 1), true)
        loader.select(request("a", 1), true)
        runCurrent()
        assertEquals(listOf("a"), reads)
        assertEquals("City, Country", loader.state.value?.location)
        assertEquals("a description", loader.state.value?.description)
        loader.select(request("b", 2), true)
        assertNull(loader.state.value)
        runCurrent()
        loader.select(request("a", 3), true)
        assertEquals("a description", loader.state.value?.description)
        assertEquals(listOf("a", "b"), reads)
        loader.close()
        assertNull(loader.state.value)
        loader.select(request("c", 4), true)
        runCurrent()
        assertEquals(listOf("a", "b"), reads)
    }

    @Test fun latePreviousMetadataCannotReplaceCurrentOrSurviveDisablingCaptions() = runTest {
        val pendingA = CompletableDeferred<Result<AssetDetail>>()
        val pendingB = CompletableDeferred<Result<AssetDetail>>()
        val loader = SlideshowCaptionLoader(this, StandardTestDispatcher(testScheduler)) { id ->
            withContext(NonCancellable) { if (id == "a") pendingA.await() else pendingB.await() }
        }
        loader.select(request("a", 1), true)
        runCurrent()
        loader.select(request("b", 2), true)
        runCurrent()
        pendingB.complete(Result.success(detail("b")))
        runCurrent()
        assertEquals("b description", loader.state.value?.description)
        pendingA.complete(Result.success(detail("a")))
        runCurrent()
        assertEquals("b description", loader.state.value?.description)
        loader.select(request("b", 2), false)
        assertNull(loader.state.value)
        loader.close()
    }

    @Test fun metadataFailureLeavesNoPreviousCaptionAndDoesNotPreventNextRead() = runTest {
        val loader = SlideshowCaptionLoader(this, StandardTestDispatcher(testScheduler)) { id ->
            if (id == "b") Result.failure(IllegalStateException("unavailable")) else Result.success(detail(id))
        }
        loader.select(request("a", 1), true)
        runCurrent()
        assertNotNull(loader.state.value)
        loader.select(request("b", 2), true)
        runCurrent()
        assertNull(loader.state.value)
        loader.select(request("c", 3), true)
        runCurrent()
        assertEquals("c description", loader.state.value?.description)
        loader.close()
    }

    private fun request(id: String, generation: Long) = SlideshowMediaRequest(generation, SlideshowSlot.Asset(id), id, "original/$id")
    private fun detail(id: String) = AssetDetail(id, "$id.jpg", "2026-10-01T00:00:00Z",
        null, null, null, null, null, null, null, null, null, "City", "", "Country",
        null, null, null, "  $id description  ", emptyList())
}
