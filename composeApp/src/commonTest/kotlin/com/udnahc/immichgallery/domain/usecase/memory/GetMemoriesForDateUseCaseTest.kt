package com.udnahc.immichgallery.domain.usecase.memory

import com.udnahc.immichgallery.data.model.AssetResponse
import com.udnahc.immichgallery.data.model.MemoryResponse
import com.udnahc.immichgallery.data.model.OnThisDayResponse
import com.udnahc.immichgallery.domain.model.Asset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GetMemoriesForDateUseCaseTest {
    @Test
    fun propagatesDateAndSortsYearsDescendingWithStableServerOrder() = runTest {
        val requestedDates = mutableListOf<LocalDate>()
        val useCase = useCase { date ->
            requestedDates += date
            listOf(
                response("older-a", 2020, "older-a-cover"),
                response("newer-a", 2024, "newer-a-cover"),
                response("newer-b", 2024, "newer-b-cover"),
                response("oldest", 2018, "oldest-cover")
            )
        }

        val result = useCase(LocalDate(2026, 8, 9)).getOrThrow()

        assertEquals(listOf(LocalDate(2026, 8, 9)), requestedDates)
        assertEquals(listOf("newer-a", "newer-b", "older-a", "oldest"), result.map { it.id })
    }

    @Test
    fun filtersUnknownEmptyAndUnsupportedMemoriesWhilePreservingSupportedAssetOrder() = runTest {
        val useCase = useCase {
            listOf(
                response("unknown", 2024, "image").copy(type = "future_type"),
                response("empty", 2023),
                response("unsupported", 2022, "audio", assetType = "AUDIO"),
                response(
                    id = "mixed",
                    year = 2021,
                    assets = listOf(
                        assetResponse("video", "VIDEO"),
                        assetResponse("audio", "AUDIO"),
                        assetResponse("image", "IMAGE")
                    )
                )
            )
        }

        val memories = useCase(LocalDate(2026, 8, 9)).getOrThrow()

        assertEquals(listOf("mixed"), memories.map { it.id })
        assertEquals(listOf("video", "image"), memories.single().assets.map(Asset::id))
        assertEquals("video", memories.single().cover.id)
    }

    @Test
    fun duplicateMemoryAndAssetIdsKeepTheFirstStableOccurrence() = runTest {
        val useCase = useCase {
            listOf(
                response(
                    id = "memory",
                    year = 2024,
                    assets = listOf(
                        assetResponse("cover", "IMAGE"),
                        assetResponse("cover", "IMAGE"),
                        assetResponse("second", "VIDEO")
                    )
                ),
                response("memory", 2020, "ignored-duplicate-memory")
            )
        }

        val memories = useCase(LocalDate(2026, 8, 9)).getOrThrow()

        assertEquals(1, memories.size)
        assertEquals(2024, memories.single().year)
        assertEquals(listOf("cover", "second"), memories.single().assets.map(Asset::id))
    }

    @Test
    fun cancellationRemainsControlFlow() = runTest {
        val useCase = useCase { throw CancellationException("cancelled") }

        assertFailsWith<CancellationException> {
            useCase(LocalDate(2026, 8, 9))
        }
    }

    private fun useCase(
        loader: suspend (LocalDate) -> List<MemoryResponse>
    ): GetMemoriesForDateUseCase = GetMemoriesForDateUseCase(
        loadMemories = loader,
        baseUrl = { "https://example.test" }
    )

    private fun response(
        id: String,
        year: Int,
        assetId: String? = null,
        assetType: String = "IMAGE",
        assets: List<AssetResponse> = assetId?.let { listOf(assetResponse(it, assetType)) }.orEmpty()
    ): MemoryResponse = MemoryResponse(
        id = id,
        type = "on_this_day",
        memoryAt = "$year-08-09T12:00:00.000Z",
        data = OnThisDayResponse(year),
        assets = assets
    )

    private fun assetResponse(id: String, type: String): AssetResponse = AssetResponse(
        id = id,
        type = type,
        originalFileName = "$id.jpg",
        fileCreatedAt = "2026-08-09T12:00:00.000Z"
    )
}
