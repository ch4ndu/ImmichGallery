package com.udnahc.immichgallery.domain.model

const val DEFAULT_TARGET_ROW_HEIGHT = 150f
const val GRID_SPACING_DP = 2f

fun packIntoRows(
    assets: List<Asset>,
    bucketIndex: Int = 0,
    sectionLabel: String = "",
    availableWidth: Float,
    targetRowHeight: Float,
    spacing: Float
): List<RowItem> {
    if (availableWidth <= 0f || assets.isEmpty()) return emptyList()
    val rows = mutableListOf<RowItem>()
    var currentRow = mutableListOf<PhotoItem>()
    var currentSumAR = 0f

    for (asset in assets) {
        val photoItem = PhotoItem(
            gridKey = "p_${asset.id}",
            bucketIndex = bucketIndex,
            sectionLabel = sectionLabel,
            asset = asset
        )
        currentRow.add(photoItem)
        currentSumAR += asset.aspectRatio
        val neededWidth = targetRowHeight * currentSumAR + (currentRow.size - 1) * spacing
        if (neededWidth >= availableWidth) {
            val actualHeight = (availableWidth - (currentRow.size - 1) * spacing) / currentSumAR
            rows.add(RowItem(
                gridKey = "row_${currentRow.first().gridKey}",
                bucketIndex = bucketIndex,
                sectionLabel = sectionLabel,
                photos = currentRow.toList(),
                rowHeight = actualHeight
            ))
            currentRow = mutableListOf()
            currentSumAR = 0f
        }
    }
    if (currentRow.isNotEmpty()) {
        rows.add(RowItem(
            gridKey = "row_${currentRow.first().gridKey}",
            bucketIndex = bucketIndex,
            sectionLabel = sectionLabel,
            photos = currentRow.toList(),
            rowHeight = targetRowHeight,
            isComplete = false
        ))
    }
    return rows
}
