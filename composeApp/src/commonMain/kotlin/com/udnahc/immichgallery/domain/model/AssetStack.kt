package com.udnahc.immichgallery.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class AssetStack(
    val id: String,
    val primaryAssetId: String,
    val assets: List<Asset>
) {
    fun initialIndex(originatingAssetId: String): Int {
        val originatingIndex = assets.indexOfFirst { it.id == originatingAssetId }
        if (originatingIndex >= 0) return originatingIndex
        return assets.indexOfFirst { it.id == primaryAssetId }.coerceAtLeast(0)
    }

    companion object {
        fun normalized(
            id: String,
            primaryAssetId: String,
            assets: List<Asset>
        ): AssetStack {
            val deduplicated = assets.distinctBy(Asset::id)
            val primary = deduplicated.firstOrNull { it.id == primaryAssetId }
            val normalizedAssets = if (primary == null) {
                deduplicated
            } else {
                listOf(primary) + deduplicated.filterNot { it.id == primaryAssetId }
            }
            return AssetStack(
                id = id,
                primaryAssetId = primaryAssetId,
                assets = normalizedAssets
            )
        }
    }
}

sealed interface AssetStackLookup {
    data class Available(val stack: AssetStack) : AssetStackLookup
    data object NoStack : AssetStackLookup
    data object NoSupportedMembers : AssetStackLookup
}

sealed class AssetStackAccessException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause) {
    class PermissionDenied(cause: Throwable) :
        AssetStackAccessException("Stack access was denied", cause)

    class Unavailable(cause: Throwable) :
        AssetStackAccessException("Stack browsing is unavailable", cause)

    class Transient(cause: Throwable) :
        AssetStackAccessException("Stack request failed", cause)
}
