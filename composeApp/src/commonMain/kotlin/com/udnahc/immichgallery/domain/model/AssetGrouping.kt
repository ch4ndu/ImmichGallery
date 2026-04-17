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
        .filterKeys { it != null }
        .entries
        .sortedWith(compareByDescending<Map.Entry<Pair<Int, kotlinx.datetime.Month>?, List<Asset>>> { it.key!!.first }
            .thenByDescending { it.key!!.second })
        .map { (key, groupAssets) ->
            val (year, month) = key!!
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
        .filterKeys { it != null }
        .entries
        .sortedByDescending { it.key }
        .map { (date, groupAssets) ->
            val d = date!!
            val monthName = d.month.name.lowercase().replaceFirstChar { it.uppercase() }
            AssetGroup(
                label = "${d.dayOfMonth} $monthName ${d.year}",
                assets = groupAssets.sortedByDescending { it.createdAt }
            )
        }
}
