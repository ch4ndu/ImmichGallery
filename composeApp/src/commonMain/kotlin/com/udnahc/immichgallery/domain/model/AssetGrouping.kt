package com.udnahc.immichgallery.domain.model

import androidx.compose.runtime.Immutable
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@Immutable
data class AssetGroup(val label: String, val assets: List<Asset>)

enum class GroupSize { NONE, MONTH, DAY }

fun groupAssets(assets: List<Asset>, groupSize: GroupSize): List<AssetGroup> {
    if (assets.isEmpty()) return emptyList()
    return when (groupSize) {
        GroupSize.NONE -> listOf(AssetGroup("", assets))
        GroupSize.MONTH -> computeMonthGroups(assets)
        GroupSize.DAY -> computeDayGroups(assets)
    }
}

private fun computeMonthGroups(assets: List<Asset>): List<AssetGroup> {
    val tz = TimeZone.currentSystemDefault()
    val grouped = assets.groupBy { asset ->
        try {
            val local = Instant.parse(asset.createdAt).toLocalDateTime(tz)
            local.year to local.month
        } catch (_: Exception) {
            try {
                val date = LocalDate.parse(asset.createdAt.take(10))
                date.year to date.month
            } catch (_: Exception) {
                null
            }
        }
    }
    return grouped
        .entries
        .mapNotNull { (key, groupAssets) -> key?.let { it to groupAssets } }
        .sortedWith(compareByDescending<Pair<Pair<Int, kotlinx.datetime.Month>, List<Asset>>> { it.first.first }
            .thenByDescending { it.first.second })
        .map { (key, groupAssets) ->
            val (year, month) = key
            val monthName = month.name.lowercase().replaceFirstChar { it.uppercase() }
            AssetGroup(
                label = "$monthName $year",
                assets = groupAssets.sortedByDescending { it.createdAt }
            )
        }
}

private fun computeDayGroups(assets: List<Asset>): List<AssetGroup> {
    val tz = TimeZone.currentSystemDefault()
    val grouped = assets.groupBy { asset ->
        try {
            Instant.parse(asset.createdAt).toLocalDateTime(tz).date
        } catch (_: Exception) {
            try {
                LocalDate.parse(asset.createdAt.take(10))
            } catch (_: Exception) {
                null
            }
        }
    }
    return grouped
        .entries
        .mapNotNull { (date, groupAssets) -> date?.let { it to groupAssets } }
        .sortedByDescending { it.first }
        .map { (d, groupAssets) ->
            val monthName = d.month.name.lowercase().replaceFirstChar { it.uppercase() }
            AssetGroup(
                label = "${d.dayOfMonth} $monthName ${d.year}",
                assets = groupAssets.sortedByDescending { it.createdAt }
            )
        }
}
