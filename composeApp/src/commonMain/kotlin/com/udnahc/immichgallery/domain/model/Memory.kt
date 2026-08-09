package com.udnahc.immichgallery.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class Memory(
    val id: String,
    val year: Int,
    val memoryAt: String,
    val assets: List<Asset>
) {
    init {
        require(assets.isNotEmpty()) { "A memory must contain a supported asset" }
    }

    val cover: Asset
        get() = assets.first()
}

sealed class MemoryAccessException(
    message: String,
    cause: Throwable
) : Exception(message, cause) {
    class PermissionDenied(cause: Throwable) :
        MemoryAccessException("Memory access was denied", cause)

    class Unavailable(cause: Throwable) :
        MemoryAccessException("Memories are unavailable", cause)

    class Transient(cause: Throwable) :
        MemoryAccessException("Memory request failed", cause)
}
