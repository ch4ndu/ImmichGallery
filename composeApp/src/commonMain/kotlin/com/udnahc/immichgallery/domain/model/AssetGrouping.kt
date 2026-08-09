package com.udnahc.immichgallery.domain.model

import androidx.compose.runtime.Immutable
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

@Immutable
data class AssetGroup(
    val label: String,
    val assets: List<Asset>,
    val displayLabel: AssetGroupDisplayLabel? = null
)

@Immutable
sealed interface AssetGroupDisplayLabel {
    data class Month(val year: Int, val monthNumber: Int) : AssetGroupDisplayLabel
    data class Day(val date: LocalDate) : AssetGroupDisplayLabel
    data object UnknownDate : AssetGroupDisplayLabel
}

enum class GroupSize { NONE, MONTH, DAY }

fun groupAssets(
    assets: List<Asset>,
    groupSize: GroupSize
): List<AssetGroup> {
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
    val datedGroups = grouped
        .entries
        .mapNotNull { (key, groupAssets) -> key?.let { it to groupAssets } }
        .sortedWith(compareByDescending<Pair<Pair<Int, kotlinx.datetime.Month>, List<Asset>>> { it.first.first }
            .thenByDescending { it.first.second })
        .map { (key, groupAssets) ->
            val (year, month) = key
            val monthNumber = month.ordinal + 1
            AssetGroup(
                label = "month:$year-${monthNumber.toString().padStart(2, '0')}",
                assets = groupAssets.sortedByDescending { it.createdAt },
                displayLabel = AssetGroupDisplayLabel.Month(year, monthNumber)
            )
        }
    val unknownGroup = grouped[null]
        ?.takeIf { it.isNotEmpty() }
        ?.let {
            AssetGroup(
                label = UNKNOWN_DATE_GROUP_KEY,
                assets = it.sortedByDescending { asset -> asset.createdAt },
                displayLabel = AssetGroupDisplayLabel.UnknownDate
            )
        }
    return datedGroups + listOfNotNull(unknownGroup)
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
    val datedGroups = grouped
        .entries
        .mapNotNull { (date, groupAssets) -> date?.let { it to groupAssets } }
        .sortedByDescending { it.first }
        .map { (d, groupAssets) ->
            AssetGroup(
                label = "day:$d",
                assets = groupAssets.sortedByDescending { it.createdAt },
                displayLabel = AssetGroupDisplayLabel.Day(d)
            )
        }
    val unknownGroup = grouped[null]
        ?.takeIf { it.isNotEmpty() }
        ?.let {
            AssetGroup(
                label = UNKNOWN_DATE_GROUP_KEY,
                assets = it.sortedByDescending { asset -> asset.createdAt },
                displayLabel = AssetGroupDisplayLabel.UnknownDate
            )
        }
    return datedGroups + listOfNotNull(unknownGroup)
}

private const val UNKNOWN_DATE_GROUP_KEY = "unknown-date"
