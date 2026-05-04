package com.udnahc.immichgallery.domain.model

import androidx.compose.runtime.Immutable
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * The Mosaic engine has two coordinate spaces:
 * - assignment/scoring uses a normalized width based on MOSAIC_CANONICAL_CELL_SIZE
 * - rendering uses the real measured grid width from MosaicLayoutSpec
 *
 * Keep constants near the top because most Mosaic UX changes are tuning changes.
 * When changing any of these values, update the Mosaic tests and docs/ai notes
 * that describe the user-visible fallback policy.
 */

// Normalized "one column" size used while scoring Mosaic candidates. This does
// not directly set UI pixels; it keeps scoring stable across viewport widths.
// Raising it without adjusting thresholds makes min tile checks and target
// height scoring more permissive in assignment space.
const val MOSAIC_CANONICAL_CELL_SIZE = 100f

// Maximum displayed Mosaic band height as a multiple of the resolved real cell
// height (availableWidth / columnCount). Bands taller than this are rejected at
// render time and their photos fall back to justified rows. Increasing it keeps
// more tall Mosaic bands; decreasing it makes Mosaic fail over more often.
const val MOSAIC_MAX_BAND_HEIGHT_TARGET_MULTIPLIER = 5f

// Lower bound for Mosaic fallback row packing when there are no valid Mosaic
// bands, or when bands are smaller than this floor. It is multiplied by the real
// Mosaic cell height. Increasing it makes fallback rows taller and less dense;
// decreasing it allows small rows between/around Mosaic bands.
const val MOSAIC_FALLBACK_MIN_ROW_HEIGHT_CELL_MULTIPLIER = 0.75f

// Additional fallback-row target based on the representative valid Mosaic band
// height in the current group. The fallback target is max(cell floor, median
// band height * this value). Increasing it makes normal justified rows closer
// to Mosaic band height; decreasing it makes fallback rows denser.
const val MOSAIC_FALLBACK_TARGET_ROW_HEIGHT_BAND_MULTIPLIER = 1f

// Explicit Mosaic fallback policy: do not use RowPacking's one-photo wide-image
// promotion. Complete Mosaic fallback rows may still span the full grid width,
// but only through normal justified packing, not a promoted single-photo row.
const val MOSAIC_FALLBACK_PROMOTE_WIDE_IMAGES = false

// Explicit Mosaic fallback policy: require at least this many photos before a
// row can become a complete justified row. This prevents a wide photo from
// becoming a complete one-photo full-width row inside Mosaic fallback. A final
// leftover row at the current group boundary may still be incomplete.
const val MOSAIC_FALLBACK_MIN_COMPLETE_ROW_PHOTOS = 2

// Supported resolved Mosaic densities. The row-height preference is converted
// into one of these column counts; the actual cell height is then width/count.
// Adding counts requires validating all template families, assignment cost, and
// display tests because every count changes the real cell-height tolerance.
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

/*
 * Mosaic templates are tiny layout trees. A leaf represents one photo slot,
 * a horizontal node puts child groups next to each other at the same height,
 * and a vertical node stacks child groups at the same width.
 *
 * The important invariant is that every node can answer both directions:
 * - "If this node is H tall, how wide is it?"
 * - "If this node must fit W wide, how tall is it?"
 *
 * That lets assignment scoring run in a normalized coordinate space and lets
 * rendering later replay the same template against the real grid width without
 * storing fragile pixel rectangles in cache/state.
 */
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
        // Width is monotonic with height for horizontal groups, but there is no
        // closed form once children contain nested vertical groups. Binary
        // search gives stable enough geometry without introducing a solver.
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
    // Mosaic treats the row-height preference as a density hint. The resolved
    // column count then defines the real cell height used by bands, placeholders,
    // and fallback rows. This keeps Mosaic available at dense zoom levels where
    // the requested target row height is smaller than a useful Mosaic band.
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
    // Fallback rows sit between Mosaic bands. If the bands in this group are
    // large, using only the cell-height floor can make normal justified rows
    // look accidental and tiny. The median valid band height gives a stable
    // local reference without letting one extreme band dominate the fallback.
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
    /*
     * Assignment is a source-order scan. At each source index we try to build
     * one Mosaic band from the next 4-6 assets. If no template/permutation is
     * acceptable, we skip roughly one justified row and try again. Skipping by a
     * row instead of a single photo prevents repeatedly testing almost the same
     * failing window in large buckets.
     *
     * The assignments intentionally store asset IDs, template IDs, source
     * indexes, and visual order only. Pixel geometry is recomputed in
     * toDisplayItem() for the current real width so stale assignments can be
     * reused across screen updates that preserve the same Mosaic density.
     */
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
    promoteWideImages: Boolean = MOSAIC_FALLBACK_PROMOTE_WIDE_IMAGES,
    minCompleteRowPhotos: Int = MOSAIC_FALLBACK_MIN_COMPLETE_ROW_PHOTOS
): List<PhotoGridDisplayItem> {
    if (assets.isEmpty() || layoutSpec.availableWidth <= 0f) return emptyList()
    /*
     * Rendering is intentionally more defensive than assignment. Assignments
     * can be produced by previous async work and then consumed after dimensions,
     * families, or asset metadata have changed. Each assignment is replayed into
     * real display geometry and validated before it is allowed to reserve source
     * assets. Invalid bands simply become fallback rows.
     */
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
    /*
     * The final display list must cover every source asset exactly once, in
     * source order. A valid Mosaic at the current index wins; otherwise the gap
     * before the next valid Mosaic is packed as justified fallback rows.
     */
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

/*
 * Mosaic fallback rows are intentionally normal justified rows: complete rows
 * span the grid width, but wide-image promotion cannot create a one-photo row.
 *
 * The subtle rule is about group boundaries. An incomplete fallback row is fine
 * only at the end of the current asset group, because the next header/bucket is
 * a natural visual break. If an incomplete fallback row would appear before a
 * later Mosaic band in the same group, the row would look randomly stranded.
 * In that case we demote the next Mosaic band back into fallback input and
 * retry packing a larger gap.
 */
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
    // Start by protecting the next valid Mosaic boundary. The retry loop below
    // relaxes that boundary only when it would otherwise create a non-final
    // incomplete row.
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
        // The current gap cannot finish cleanly before the next Mosaic. Remove
        // that Mosaic from the render map and include its source assets in the
        // fallback gap so the next pass can complete a justified row.
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
    /*
     * Try larger bands first because a good 6-photo Mosaic usually gives more
     * visual variety and reduces fallback churn. We still score every enabled
     * template/permutation for every supported count and return the lowest score
     * overall, so a poor 6-photo candidate can lose to a cleaner 4-photo band.
     */
    for (count in maxCount downTo MIN_MOSAIC_ASSETS) {
        shouldContinue()
        val templates = MOSAIC_TEMPLATES.filter { it.slotCount == count && it.family in enabledFamilies }
        if (templates.isEmpty()) continue
        val window = assets.subList(sourceStartIndex, sourceStartIndex + count)
        for (permutation in permutations(count)) {
            shouldContinue()
            // permutation[slot] maps a template slot to a source asset. This is
            // how the same template can choose which photo becomes the feature
            // tile without changing source ownership.
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
    // Reject unusable geometry before comparing aesthetics. These thresholds
    // protect rendering from huge bands, tiny tap targets, and degenerate
    // templates produced by extreme aspect ratios.
    if (height <= 0f || height > bandHeightLimit) return null
    if (rects.any { it.width < MIN_TILE_SIZE || it.height < MIN_TILE_SIZE }) return null
    val area = rects.map { it.width * it.height }
    val minArea = area.minOrNull() ?: return null
    val maxArea = area.maxOrNull() ?: return null
    val areaBalancePenalty = maxArea / minArea
    // The target is intentionally approximate. Mosaic should avoid both tiny
    // strips and huge posters, but the later display validation allows bands up
    // to 5x cell height because some real photo sets need that tolerance.
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
    // Rebuild display tiles from the template slots, not from source order.
    // visualOrder is the stable slot order used by the renderer and shared
    // element keys; source order is preserved by sourceStartIndex/sourceCount.
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
    // Validation is the final guardrail before a Mosaic band reserves assets in
    // the display list. Any failure falls back to justified rows, which is safer
    // than rendering overlapping tiles or a band far larger than nearby content.
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
    // Used only during assignment scanning to jump past a bad Mosaic window.
    // The real fallback rows are packed later with the current Mosaic fallback
    // policy and the real display width.
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
