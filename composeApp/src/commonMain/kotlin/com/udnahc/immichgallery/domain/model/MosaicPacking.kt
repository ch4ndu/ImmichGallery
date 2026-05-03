package com.udnahc.immichgallery.domain.model

import androidx.compose.runtime.Immutable
import kotlin.math.abs
import kotlin.math.roundToInt

const val MOSAIC_CANONICAL_CELL_SIZE = 100f
const val MOSAIC_MAX_BAND_HEIGHT_TARGET_MULTIPLIER = 5f
const val MOSAIC_FALLBACK_MIN_ROW_HEIGHT_CELL_MULTIPLIER = 0.75f
const val MOSAIC_FALLBACK_TARGET_ROW_HEIGHT_BAND_MULTIPLIER = 0.5f
val SUPPORTED_MOSAIC_COLUMN_COUNTS = 2..6

@Immutable
data class MosaicLayoutSpec(
    val columnCount: Int,
    val availableWidth: Float,
    val cellHeight: Float
)

@Immutable
data class MosaicTileAssignment(
    val assetId: String,
    val visualOrder: Int
)

@Immutable
data class MosaicBandAssignment(
    val bandIndex: Int,
    val sourceStartIndex: Int,
    val sourceCount: Int,
    val templateId: String,
    val tiles: List<MosaicTileAssignment>
)

private data class MosaicCandidate(
    val assignment: MosaicBandAssignment,
    val score: Float
)

private data class MosaicRenderAssignment(
    val assignment: MosaicBandAssignment,
    val item: MosaicBandItem
)

private data class TileRect(
    val slot: Int,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float
)

private sealed interface MosaicNode {
    fun widthForHeight(height: Float, aspects: FloatArray, spacing: Float): Float
    fun heightForWidth(width: Float, aspects: FloatArray, spacing: Float): Float
    fun layout(x: Float, y: Float, width: Float, aspects: FloatArray, spacing: Float): List<TileRect>
}

private data class LeafNode(val slot: Int) : MosaicNode {
    override fun widthForHeight(height: Float, aspects: FloatArray, spacing: Float): Float =
        height * aspects[slot]

    override fun heightForWidth(width: Float, aspects: FloatArray, spacing: Float): Float =
        width / aspects[slot]

    override fun layout(x: Float, y: Float, width: Float, aspects: FloatArray, spacing: Float): List<TileRect> {
        val height = heightForWidth(width, aspects, spacing)
        return listOf(TileRect(slot, x, y, width, height))
    }
}

private data class HorizontalNode(val children: List<MosaicNode>) : MosaicNode {
    override fun widthForHeight(height: Float, aspects: FloatArray, spacing: Float): Float =
        children.sumOf { it.widthForHeight(height, aspects, spacing).toDouble() }.toFloat() +
            spacing * (children.size - 1)

    override fun heightForWidth(width: Float, aspects: FloatArray, spacing: Float): Float {
        val usableWidth = width.coerceAtLeast(0f)
        var low = 0f
        var high = usableWidth.coerceAtLeast(1f)
        repeat(BINARY_SEARCH_STEPS) {
            val mid = (low + high) / 2f
            if (widthForHeight(mid, aspects, spacing) > usableWidth) {
                high = mid
            } else {
                low = mid
            }
        }
        return low
    }

    override fun layout(x: Float, y: Float, width: Float, aspects: FloatArray, spacing: Float): List<TileRect> {
        val height = heightForWidth(width, aspects, spacing)
        val result = mutableListOf<TileRect>()
        var cursorX = x
        for (child in children) {
            val childWidth = child.widthForHeight(height, aspects, spacing)
            result.addAll(child.layout(cursorX, y, childWidth, aspects, spacing))
            cursorX += childWidth + spacing
        }
        return result
    }
}

private data class VerticalNode(val children: List<MosaicNode>) : MosaicNode {
    override fun widthForHeight(height: Float, aspects: FloatArray, spacing: Float): Float {
        val usableHeight = (height - spacing * (children.size - 1)).coerceAtLeast(0f)
        val inverseAspectSum = children.sumOf { (1f / it.approxAspect(aspects)).toDouble() }.toFloat()
        return if (inverseAspectSum > 0f) usableHeight / inverseAspectSum else 0f
    }

    override fun heightForWidth(width: Float, aspects: FloatArray, spacing: Float): Float =
        children.sumOf { it.heightForWidth(width, aspects, spacing).toDouble() }.toFloat() +
            spacing * (children.size - 1)

    override fun layout(x: Float, y: Float, width: Float, aspects: FloatArray, spacing: Float): List<TileRect> {
        val result = mutableListOf<TileRect>()
        var cursorY = y
        for (child in children) {
            val childHeight = child.heightForWidth(width, aspects, spacing)
            result.addAll(child.layout(x, cursorY, width, aspects, spacing))
            cursorY += childHeight + spacing
        }
        return result
    }
}

private fun MosaicNode.approxAspect(aspects: FloatArray): Float =
    when (this) {
        is LeafNode -> aspects[slot]
        is HorizontalNode -> children.sumOf { it.approxAspect(aspects).toDouble() }.toFloat()
        is VerticalNode -> {
            val inverse = children.sumOf { (1f / it.approxAspect(aspects)).toDouble() }.toFloat()
            if (inverse > 0f) 1f / inverse else 1f
        }
    }

private data class MosaicTemplate(
    val id: String,
    val family: MosaicTemplateFamily,
    val slotCount: Int,
    val root: MosaicNode
)

fun nearestMosaicColumnCount(availableWidth: Float, targetRowHeight: Float): Int {
    if (availableWidth <= 0f || targetRowHeight <= 0f) return DEFAULT_GRID_COLUMN_COUNT
    return (availableWidth / targetRowHeight)
        .roundToInt()
        .coerceIn(SUPPORTED_MOSAIC_COLUMN_COUNTS.first, SUPPORTED_MOSAIC_COLUMN_COUNTS.last)
}

fun mosaicLayoutSpecFor(availableWidth: Float, targetRowHeight: Float): MosaicLayoutSpec? {
    if (availableWidth <= 0f || targetRowHeight <= 0f) return null
    val columnCount = nearestMosaicColumnCount(availableWidth, targetRowHeight)
    if (columnCount !in SUPPORTED_MOSAIC_COLUMN_COUNTS) return null
    return MosaicLayoutSpec(
        columnCount = columnCount,
        availableWidth = availableWidth,
        cellHeight = availableWidth / columnCount
    )
}

fun mosaicFallbackMinRowHeight(layoutSpec: MosaicLayoutSpec): Float =
    layoutSpec.cellHeight * MOSAIC_FALLBACK_MIN_ROW_HEIGHT_CELL_MULTIPLIER

fun mosaicFallbackMinRowHeight(layoutSpec: MosaicLayoutSpec, mosaicBandHeights: List<Float>): Float {
    val cellFloor = mosaicFallbackMinRowHeight(layoutSpec)
    val representativeBandHeight = mosaicBandHeights
        .filter { it > 0f }
        .sorted()
        .let { heights ->
            when {
                heights.isEmpty() -> null
                heights.size % 2 == 1 -> heights[heights.size / 2]
                else -> {
                    val upper = heights.size / 2
                    (heights[upper - 1] + heights[upper]) / 2f
                }
            }
        }
    val bandFloor = representativeBandHeight?.let { it * MOSAIC_FALLBACK_TARGET_ROW_HEIGHT_BAND_MULTIPLIER } ?: 0f
    return maxOf(cellFloor, bandFloor)
}

fun buildMosaicAssignments(
    assets: List<Asset>,
    layoutSpec: MosaicLayoutSpec,
    spacing: Float,
    enabledFamilies: Set<MosaicTemplateFamily> = MosaicTemplateFamily.defaultSet(),
    bandHeightLimit: Float = MOSAIC_CANONICAL_CELL_SIZE * MOSAIC_MAX_BAND_HEIGHT_TARGET_MULTIPLIER,
    shouldContinue: () -> Unit = {}
): List<MosaicBandAssignment> {
    if (assets.size < MIN_MOSAIC_ASSETS ||
        layoutSpec.columnCount !in SUPPORTED_MOSAIC_COLUMN_COUNTS ||
        enabledFamilies.isEmpty()
    ) return emptyList()
    val assignments = mutableListOf<MosaicBandAssignment>()
    var sourceIndex = 0
    var bandIndex = 0
    val width = layoutSpec.columnCount * MOSAIC_CANONICAL_CELL_SIZE
    while (sourceIndex <= assets.size - MIN_MOSAIC_ASSETS) {
        shouldContinue()
        val candidate = bestCandidate(
            assets = assets,
            sourceStartIndex = sourceIndex,
            bandIndex = bandIndex,
            width = width,
            spacing = spacing,
            bandHeightLimit = bandHeightLimit,
            enabledFamilies = enabledFamilies,
            shouldContinue = shouldContinue
        )
        if (candidate != null) {
            assignments.add(candidate.assignment)
            sourceIndex += candidate.assignment.sourceCount
            bandIndex++
        } else {
            val fallbackCount = firstJustifiedRowCount(
                assets.subList(sourceIndex, assets.size),
                width,
                spacing
            )
            sourceIndex += fallbackCount.coerceAtLeast(1)
        }
    }
    return assignments
}

fun buildPhotoGridItemsWithMosaic(
    assets: List<Asset>,
    assignments: List<MosaicBandAssignment>,
    bucketIndex: Int,
    sectionLabel: String,
    layoutSpec: MosaicLayoutSpec,
    spacing: Float,
    maxRowHeight: Float,
    promoteWideImages: Boolean = false,
    minCompleteRowPhotos: Int = 2
): List<PhotoGridDisplayItem> {
    if (assets.isEmpty() || layoutSpec.availableWidth <= 0f) return emptyList()
    val byStart = assignments
        .mapNotNull { assignment ->
            val item = assignment.toDisplayItem(
                assets = assets,
                bucketIndex = bucketIndex,
                sectionLabel = sectionLabel,
                layoutSpec = layoutSpec,
                spacing = spacing
            )
            if (item != null && item.isValidFor(layoutSpec)) {
                MosaicRenderAssignment(assignment = assignment, item = item)
            } else {
                null
            }
        }
        .associateBy { it.assignment.sourceStartIndex }
        .toMutableMap()
    val fallbackTargetRowHeight = mosaicFallbackMinRowHeight(layoutSpec, byStart.values.map { it.item.bandHeight })
    val items = mutableListOf<PhotoGridDisplayItem>()
    var sourceIndex = 0
    while (sourceIndex < assets.size) {
        val mosaic = byStart[sourceIndex]
        if (mosaic != null) {
            items.add(mosaic.item)
            sourceIndex += mosaic.assignment.sourceCount
        } else {
            val rows = packMosaicFallbackRows(
                assets = assets,
                sourceStartIndex = sourceIndex,
                validMosaicsByStart = byStart,
                bucketIndex = bucketIndex,
                sectionLabel = sectionLabel,
                layoutSpec = layoutSpec,
                spacing = spacing,
                maxRowHeight = maxRowHeight,
                promoteWideImages = promoteWideImages,
                minCompleteRowPhotos = minCompleteRowPhotos,
                targetRowHeight = fallbackTargetRowHeight
            )
            if (rows.isEmpty()) {
                sourceIndex = assets.size
            } else {
                items.addAll(rows)
                sourceIndex += rows.sumOf { it.photos.size }.coerceAtLeast(1)
            }
        }
    }
    return items
}

private fun packMosaicFallbackRows(
    assets: List<Asset>,
    sourceStartIndex: Int,
    validMosaicsByStart: MutableMap<Int, MosaicRenderAssignment>,
    bucketIndex: Int,
    sectionLabel: String,
    layoutSpec: MosaicLayoutSpec,
    spacing: Float,
    maxRowHeight: Float,
    promoteWideImages: Boolean,
    minCompleteRowPhotos: Int,
    targetRowHeight: Float
): List<RowItem> {
    var sourceEndIndex = validMosaicsByStart.keys
        .filter { it > sourceStartIndex }
        .minOrNull() ?: assets.size
    var rows = packMosaicFallbackRows(
        assets = assets,
        sourceStartIndex = sourceStartIndex,
        sourceEndIndex = sourceEndIndex,
        bucketIndex = bucketIndex,
        sectionLabel = sectionLabel,
        layoutSpec = layoutSpec,
        spacing = spacing,
        maxRowHeight = maxRowHeight,
        promoteWideImages = promoteWideImages,
        minCompleteRowPhotos = minCompleteRowPhotos,
        targetRowHeight = targetRowHeight
    )

    while (sourceEndIndex < assets.size && rows.lastOrNull()?.isComplete == false) {
        val demotedMosaic = validMosaicsByStart.remove(sourceEndIndex)
        val demotedEndIndex = sourceEndIndex + (demotedMosaic?.assignment?.sourceCount ?: 1)
        sourceEndIndex = validMosaicsByStart.keys
            .filter { it > demotedEndIndex }
            .minOrNull() ?: assets.size
        rows = packMosaicFallbackRows(
            assets = assets,
            sourceStartIndex = sourceStartIndex,
            sourceEndIndex = sourceEndIndex,
            bucketIndex = bucketIndex,
            sectionLabel = sectionLabel,
            layoutSpec = layoutSpec,
            spacing = spacing,
            maxRowHeight = maxRowHeight,
            promoteWideImages = promoteWideImages,
            minCompleteRowPhotos = minCompleteRowPhotos,
            targetRowHeight = targetRowHeight
        )
    }

    return rows
}

private fun packMosaicFallbackRows(
    assets: List<Asset>,
    sourceStartIndex: Int,
    sourceEndIndex: Int,
    bucketIndex: Int,
    sectionLabel: String,
    layoutSpec: MosaicLayoutSpec,
    spacing: Float,
    maxRowHeight: Float,
    promoteWideImages: Boolean,
    minCompleteRowPhotos: Int,
    targetRowHeight: Float
): List<RowItem> {
    if (sourceEndIndex <= sourceStartIndex) return emptyList()
    return packIntoRows(
        assets = assets.subList(sourceStartIndex, sourceEndIndex),
        bucketIndex = bucketIndex,
        sectionLabel = sectionLabel,
        availableWidth = layoutSpec.availableWidth,
        targetRowHeight = targetRowHeight,
        spacing = spacing,
        maxRowHeight = maxRowHeight,
        promoteWideImages = promoteWideImages,
        minCompleteRowPhotos = minCompleteRowPhotos
    )
}

private fun bestCandidate(
    assets: List<Asset>,
    sourceStartIndex: Int,
    bandIndex: Int,
    width: Float,
    spacing: Float,
    bandHeightLimit: Float,
    enabledFamilies: Set<MosaicTemplateFamily>,
    shouldContinue: () -> Unit
): MosaicCandidate? {
    var best: MosaicCandidate? = null
    val maxCount = minOf(MAX_MOSAIC_ASSETS, assets.size - sourceStartIndex)
    for (count in maxCount downTo MIN_MOSAIC_ASSETS) {
        shouldContinue()
        val templates = MOSAIC_TEMPLATES.filter { it.slotCount == count && it.family in enabledFamilies }
        if (templates.isEmpty()) continue
        val window = assets.subList(sourceStartIndex, sourceStartIndex + count)
        for (permutation in permutations(count)) {
            shouldContinue()
            val aspects = FloatArray(count) { slot ->
                window[permutation[slot]].aspectRatio.coerceAtLeast(MIN_ASPECT_RATIO)
            }
            for (template in templates) {
                shouldContinue()
                val rects = template.root.layout(0f, 0f, width, aspects, spacing)
                val height = rects.maxOfOrNull { it.y + it.height } ?: 0f
                val score = scoreCandidate(rects, height, bandHeightLimit, width)
                if (score != null && (best == null || score < best.score)) {
                    best = MosaicCandidate(
                        assignment = MosaicBandAssignment(
                            bandIndex = bandIndex,
                            sourceStartIndex = sourceStartIndex,
                            sourceCount = count,
                            templateId = template.id,
                            tiles = rects
                                .sortedBy { it.slot }
                                .map { rect ->
                                    MosaicTileAssignment(
                                        assetId = window[permutation[rect.slot]].id,
                                        visualOrder = rect.slot
                                    )
                                }
                        ),
                        score = score
                    )
                }
            }
        }
    }
    return best
}

private fun scoreCandidate(
    rects: List<TileRect>,
    height: Float,
    bandHeightLimit: Float,
    width: Float
): Float? {
    if (height <= 0f || height > bandHeightLimit) return null
    if (rects.any { it.width < MIN_TILE_SIZE || it.height < MIN_TILE_SIZE }) return null
    val area = rects.map { it.width * it.height }
    val minArea = area.minOrNull() ?: return null
    val maxArea = area.maxOrNull() ?: return null
    val areaBalancePenalty = maxArea / minArea
    val heightPenalty = abs(height - MOSAIC_CANONICAL_CELL_SIZE * 2.5f) / MOSAIC_CANONICAL_CELL_SIZE
    val fillPenalty = abs((rects.maxOf { it.x + it.width }) - width) / width
    return heightPenalty + areaBalancePenalty * AREA_BALANCE_WEIGHT + fillPenalty
}

private fun MosaicBandAssignment.toDisplayItem(
    assets: List<Asset>,
    bucketIndex: Int,
    sectionLabel: String,
    layoutSpec: MosaicLayoutSpec,
    spacing: Float
): MosaicBandItem? {
    val template = MOSAIC_TEMPLATES.firstOrNull { it.id == templateId } ?: return null
    val assetsById = assets.associateBy { it.id }
    val orderedTiles = tiles.sortedBy { it.visualOrder }
    if (orderedTiles.size != template.slotCount) return null
    val tileAssets = orderedTiles.map { assignment -> assetsById[assignment.assetId] ?: return null }
    val aspects = FloatArray(tileAssets.size) { index ->
        tileAssets[index].aspectRatio.coerceAtLeast(MIN_ASPECT_RATIO)
    }
    val rects = template.root.layout(0f, 0f, layoutSpec.availableWidth, aspects, spacing)
    val bandHeight = rects.maxOfOrNull { it.y + it.height } ?: return null
    val displayTiles = rects.map { rect ->
        val asset = tileAssets.getOrNull(rect.slot) ?: return null
        MosaicTile(
            photo = PhotoItem(
                gridKey = "p_${asset.id}",
                bucketIndex = bucketIndex,
                sectionLabel = sectionLabel,
                asset = asset
            ),
            x = rect.x,
            y = rect.y,
            width = rect.width,
            height = rect.height,
            visualOrder = rect.slot
        )
    }.sortedBy { it.visualOrder }
    return MosaicBandItem(
        gridKey = "mosaic_${bucketIndex}_${sectionLabel}_${bandIndex}",
        bucketIndex = bucketIndex,
        sectionLabel = sectionLabel,
        tiles = displayTiles,
        bandHeight = bandHeight
    )
}

private fun MosaicBandItem.isValidFor(layoutSpec: MosaicLayoutSpec): Boolean {
    if (bandHeight <= 0f || bandHeight > layoutSpec.cellHeight * MOSAIC_MAX_BAND_HEIGHT_TARGET_MULTIPLIER) return false
    if (tiles.any { it.width < MIN_TILE_SIZE || it.height < MIN_TILE_SIZE }) return false
    val filledWidth = tiles.maxOfOrNull { it.x + it.width } ?: return false
    if (abs(filledWidth - layoutSpec.availableWidth) > GEOMETRY_TOLERANCE) return false
    for (i in tiles.indices) {
        for (j in i + 1 until tiles.size) {
            if (tiles[i].overlaps(tiles[j])) return false
        }
    }
    return true
}

private fun MosaicTile.overlaps(other: MosaicTile): Boolean =
    x + width > other.x + GEOMETRY_TOLERANCE &&
        other.x + other.width > x + GEOMETRY_TOLERANCE &&
        y + height > other.y + GEOMETRY_TOLERANCE &&
        other.y + other.height > y + GEOMETRY_TOLERANCE

private fun firstJustifiedRowCount(assets: List<Asset>, width: Float, spacing: Float): Int =
    packIntoRows(
        assets = assets,
        availableWidth = width,
        targetRowHeight = MOSAIC_CANONICAL_CELL_SIZE,
        spacing = spacing
    ).firstOrNull()?.photos?.size ?: 1

private fun permutations(size: Int): List<IntArray> {
    val result = mutableListOf<IntArray>()
    fun permute(values: IntArray, index: Int) {
        if (index == values.size) {
            result.add(values.copyOf())
            return
        }
        for (i in index until values.size) {
            val tmp = values[index]
            values[index] = values[i]
            values[i] = tmp
            permute(values, index + 1)
            values[i] = values[index]
            values[index] = tmp
        }
    }
    permute(IntArray(size) { it }, 0)
    return result
}

private fun leaf(slot: Int): MosaicNode = LeafNode(slot)
private fun h(vararg children: MosaicNode): MosaicNode = HorizontalNode(children.toList())
private fun v(vararg children: MosaicNode): MosaicNode = VerticalNode(children.toList())

private val MOSAIC_TEMPLATES = listOf(
    MosaicTemplate("4_feature_left_stack_3", MosaicTemplateFamily.FOUR_TILE, 4, h(leaf(0), v(leaf(1), leaf(2), leaf(3)))),
    MosaicTemplate("4_feature_right_stack_3", MosaicTemplateFamily.FOUR_TILE, 4, h(v(leaf(1), leaf(2), leaf(3)), leaf(0))),
    MosaicTemplate("4_top_feature_bottom_row", MosaicTemplateFamily.FOUR_TILE, 4, v(leaf(0), h(leaf(1), leaf(2), leaf(3)))),
    MosaicTemplate("4_bottom_feature_top_row", MosaicTemplateFamily.FOUR_TILE, 4, v(h(leaf(1), leaf(2), leaf(3)), leaf(0))),
    MosaicTemplate("5_feature_left_quad", MosaicTemplateFamily.FIVE_TILE, 5, h(leaf(0), v(h(leaf(1), leaf(2)), h(leaf(3), leaf(4))))),
    MosaicTemplate("5_feature_right_quad", MosaicTemplateFamily.FIVE_TILE, 5, h(v(h(leaf(1), leaf(2)), h(leaf(3), leaf(4))), leaf(0))),
    MosaicTemplate("5_top_feature_cluster", MosaicTemplateFamily.FIVE_TILE, 5, v(leaf(0), h(v(leaf(1), leaf(2)), v(leaf(3), leaf(4))))),
    MosaicTemplate("5_bottom_feature_cluster", MosaicTemplateFamily.FIVE_TILE, 5, v(h(v(leaf(1), leaf(2)), v(leaf(3), leaf(4))), leaf(0))),
    MosaicTemplate("6_feature_left_mixed", MosaicTemplateFamily.SIX_TILE, 6, h(leaf(0), v(h(leaf(1), leaf(2)), h(leaf(3), leaf(4)), leaf(5)))),
    MosaicTemplate("6_feature_right_mixed", MosaicTemplateFamily.SIX_TILE, 6, h(v(h(leaf(1), leaf(2)), h(leaf(3), leaf(4)), leaf(5)), leaf(0))),
    MosaicTemplate("6_three_columns_pairs", MosaicTemplateFamily.SIX_TILE, 6, h(v(leaf(0), leaf(1)), v(leaf(2), leaf(3)), v(leaf(4), leaf(5)))),
    MosaicTemplate("6_two_rows_triples", MosaicTemplateFamily.SIX_TILE, 6, v(h(leaf(0), leaf(1), leaf(2)), h(leaf(3), leaf(4), leaf(5))))
)

private const val MIN_MOSAIC_ASSETS = 4
private const val MAX_MOSAIC_ASSETS = 6
private const val BINARY_SEARCH_STEPS = 24
private const val MIN_ASPECT_RATIO = 0.1f
private const val MIN_TILE_SIZE = 42f
private const val AREA_BALANCE_WEIGHT = 0.08f
private const val GEOMETRY_TOLERANCE = 0.5f
