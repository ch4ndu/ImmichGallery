package com.udnahc.immichgallery.ui.screen.memories

import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.AssetStackLookup
import com.udnahc.immichgallery.domain.model.AssetType
import com.udnahc.immichgallery.domain.model.Memory
import com.udnahc.immichgallery.domain.model.MemoryAccessException
import com.udnahc.immichgallery.ui.model.MemoriesUiMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class MemoriesViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun initialLoadUsesCurrentDateInPositiveUtcOffset() = runTest(dispatcher.scheduler) {
        val requestedDates = mutableListOf<LocalDate>()
        val viewModel = viewModel(
            now = { Instant.parse("2026-08-09T10:30:00Z") },
            timeZone = { TimeZone.of("+14:00") },
            loadMemories = { date ->
                requestedDates += date
                Result.success(listOf(memory("memory", 2024)))
            }
        )

        advanceUntilIdle()

        assertEquals(listOf(LocalDate(2026, 8, 10)), requestedDates)
        assertEquals(LocalDate(2026, 8, 10), viewModel.state.value.requestedDate)
        assertFalse(viewModel.state.value.isLoading)
    }

    @Test
    fun initialLoadUsesCurrentDateInNegativeUtcOffset() = runTest(dispatcher.scheduler) {
        val requestedDates = mutableListOf<LocalDate>()
        viewModel(
            now = { Instant.parse("2026-08-10T04:30:00Z") },
            timeZone = { TimeZone.of("-10:00") },
            loadMemories = { date ->
                requestedDates += date
                Result.success(emptyList())
            }
        )

        advanceUntilIdle()

        assertEquals(listOf(LocalDate(2026, 8, 9)), requestedDates)
    }

    @Test
    fun retryRecomputesLocalDateAfterMidnight() = runTest(dispatcher.scheduler) {
        var currentInstant = Instant.parse("2026-08-09T23:59:00Z")
        val requestedDates = mutableListOf<LocalDate>()
        val viewModel = viewModel(
            now = { currentInstant },
            timeZone = { TimeZone.UTC },
            loadMemories = { date ->
                requestedDates += date
                Result.success(emptyList())
            }
        )
        advanceUntilIdle()

        currentInstant = Instant.parse("2026-08-10T00:01:00Z")
        viewModel.retry()
        advanceUntilIdle()

        assertEquals(
            listOf(LocalDate(2026, 8, 9), LocalDate(2026, 8, 10)),
            requestedDates
        )
    }

    @Test
    fun emptyPermissionUnavailableAndTransientResultsRemainTyped() = runTest(dispatcher.scheduler) {
        val empty = viewModel(loadMemories = { Result.success(emptyList()) })
        val permission = viewModel(
            loadMemories = {
                Result.failure(MemoryAccessException.PermissionDenied(IllegalStateException("denied")))
            }
        )
        val unavailable = viewModel(
            loadMemories = {
                Result.failure(MemoryAccessException.Unavailable(IllegalStateException("missing")))
            }
        )
        val transient = viewModel(
            loadMemories = { Result.failure(IllegalStateException("offline")) }
        )

        advanceUntilIdle()

        assertEquals(emptyList(), empty.state.value.memories)
        assertNull(empty.state.value.error)
        assertEquals(MemoriesUiMessage.PermissionDenied, permission.state.value.error)
        assertEquals(MemoriesUiMessage.Unavailable, unavailable.state.value.error)
        assertEquals(MemoriesUiMessage.LoadFailed, transient.state.value.error)
    }

    @Test
    fun retryRejectsStaleNonCooperativeResponse() = runTest(dispatcher.scheduler) {
        val firstResult = CompletableDeferred<Result<List<Memory>>>()
        var calls = 0
        val viewModel = viewModel(
            loadMemories = {
                calls++
                if (calls == 1) {
                    try {
                        firstResult.await()
                    } catch (_: CancellationException) {
                        Result.success(listOf(memory("stale", 2020)))
                    }
                } else {
                    Result.success(listOf(memory("fresh", 2024)))
                }
            }
        )
        runCurrent()

        viewModel.retry()
        advanceUntilIdle()

        assertEquals(listOf("fresh"), viewModel.state.value.memories.map(Memory::id))
    }

    @Test
    fun selectedMemoryCanBeReplacedAndRetainsItsMixedMediaOrder() = runTest(dispatcher.scheduler) {
        val first = memory("first", 2024)
        val second = memory(
            id = "second",
            year = 2020,
            assets = listOf(asset("video", AssetType.VIDEO), asset("image", AssetType.IMAGE))
        )
        val viewModel = viewModel(
            loadMemories = { Result.success(listOf(first, second)) }
        )
        advanceUntilIdle()

        viewModel.selectMemory(first.id)
        viewModel.selectMemory(second.id)

        val selected = viewModel.state.value.memories
            .first { it.id == viewModel.state.value.selectedMemoryId }
        assertEquals("second", selected.id)
        assertEquals(listOf("video", "image"), selected.assets.map(Asset::id))
        assertEquals("video", selected.cover.id)
    }

    @Test
    fun viewerCallbacksUseTheSelectedMemoryAssetWithoutChangingSelection() = runTest(dispatcher.scheduler) {
        val stackRequests = mutableListOf<String>()
        val viewModel = viewModel(
            loadMemories = { Result.success(listOf(memory("memory", 2024))) },
            loadAssetStack = { assetId ->
                stackRequests += assetId
                Result.success(AssetStackLookup.NoStack)
            }
        )
        advanceUntilIdle()
        viewModel.selectMemory("memory")

        viewModel.getAssetStack("memory-cover")

        assertEquals(listOf("memory-cover"), stackRequests)
        assertEquals("memory", viewModel.state.value.selectedMemoryId)
    }

    private fun viewModel(
        loadMemories: suspend (LocalDate) -> Result<List<Memory>> = { Result.success(emptyList()) },
        loadAssetStack: suspend (String) -> Result<AssetStackLookup> = {
            Result.failure(IllegalStateException("not used"))
        },
        now: () -> Instant = { Instant.parse("2026-08-09T12:00:00Z") },
        timeZone: () -> TimeZone = { TimeZone.UTC }
    ): MemoriesViewModel = MemoriesViewModel(
        loadMemories = loadMemories,
        apiKey = "api-key",
        loadAssetDetail = { Result.failure(IllegalStateException("not used")) },
        loadAssetStack = loadAssetStack,
        now = now,
        systemTimeZone = timeZone,
        requestDispatcher = dispatcher
    )

    private fun memory(
        id: String,
        year: Int,
        assets: List<Asset> = listOf(asset("$id-cover", AssetType.IMAGE))
    ): Memory = Memory(
        id = id,
        year = year,
        memoryAt = "$year-08-09T12:00:00.000Z",
        assets = assets
    )

    private fun asset(id: String, type: AssetType): Asset = Asset(
        id = id,
        type = type,
        fileName = "$id.jpg",
        createdAt = "2026-08-09T12:00:00.000Z",
        thumbnailUrl = "https://example.test/$id/thumb",
        originalUrl = "https://example.test/$id/original",
        videoPlaybackUrl = "https://example.test/$id/video"
    )
}
