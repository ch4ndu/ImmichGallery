package com.udnahc.immichgallery.domain.usecase.memory

import com.udnahc.immichgallery.data.model.MemoryResponse
import com.udnahc.immichgallery.data.repository.MemoryRepository
import com.udnahc.immichgallery.data.repository.ServerConfigRepository
import com.udnahc.immichgallery.domain.model.Memory
import com.udnahc.immichgallery.domain.model.toDomainOrNull
import com.udnahc.immichgallery.runSuspendCatching
import kotlinx.datetime.LocalDate

class GetMemoriesForDateUseCase internal constructor(
    private val loadMemories: suspend (LocalDate) -> List<MemoryResponse>,
    private val baseUrl: () -> String
) {
    constructor(
        repository: MemoryRepository,
        serverConfigRepository: ServerConfigRepository
    ) : this(
        loadMemories = repository::getMemories,
        baseUrl = { serverConfigRepository.getServerUrl().trimEnd('/') }
    )

    suspend operator fun invoke(forDate: LocalDate): Result<List<Memory>> = runSuspendCatching {
        val currentBaseUrl = baseUrl()
        loadMemories(forDate)
            .mapNotNull { it.toDomainOrNull(currentBaseUrl) }
            .distinctBy(Memory::id)
            .sortedWith(compareByDescending<Memory> { it.year })
    }
}
