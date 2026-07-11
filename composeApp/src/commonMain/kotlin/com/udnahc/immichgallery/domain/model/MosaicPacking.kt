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

// Lower bound for Mosaic fallback row sizing when there are no valid Mosaic
// bands, or when bands are smaller than this floor. It is multiplied by the real
// Mosaic cell height. Increasing it makes fallback rows taller and less dense;
// decreasing it allows smaller rows between/around Mosaic bands.
const val MOSAIC_FALLBACK_MIN_ROW_HEIGHT_CELL_MULTIPLIER = 0.75f

// Additional fallback-row target based on the representative valid Mosaic band
// height in the current group. The fallback target is max(cell floor, median
// band height * this value). Increasing it makes fallback rows closer to
// Mosaic band height; decreasing it makes fallback rows denser.
const val MOSAIC_FALLBACK_TARGET_ROW_HEIGHT_BAND_MULTIPLIER = 1f
const val MOSAIC_FALLBACK_ROW_MEDIAN_BAND_FRACTION = 0.75f

// Small Mosaic groups cannot form a real 4-8 asset Mosaic band. Using a normal
// one-cell fallback row makes 1-4 asset buckets look like thumbnails, so empty
// assignment groups get a taller fallback band target while keeping every
// thumbnail visible with fit-style rendering.
const val MOSAIC_SMALL_GROUP_MAX_ASSETS = 4
const val MOSAIC_SMALL_GROUP_FALLBACK_ROW_HEIGHT_CELL_MULTIPLIER = 3f

// Mosaic fallback rows keep row-packing membership but render as cropped
// Mosaic-owned rows, not standard rows.
const val MOSAIC_FALLBACK_PROMOTE_WIDE_IMAGES = false

const val MOSAIC_FALLBACK_MIN_COMPLETE_ROW_PHOTOS = 2

// Supported resolved Mosaic densities. The row-height preference is converted
// into one of these column counts; the actual cell height is then width/count.
// Adding counts requires validating all template families, assignment cost, and
// display tests because every count changes the real cell-height tolerance.
val NORMAL_SCREEN_MOSAIC_COLUMN_COUNTS = 2..6
val LARGE_SCREEN_MOSAIC_COLUMN_COUNTS = 4..8
val SUPPORTED_MOSAIC_COLUMN_COUNTS =
    NORMAL_SCREEN_MOSAIC_COLUMN_COUNTS.first..LARGE_SCREEN_MOSAIC_COLUMN_COUNTS.last

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

@Immutable
data class MosaicAssignmentProgressChunk(
    val sourceStartIndex: Int,
    val sourceEndExclusive: Int,
    val assignments: List<MosaicBandAssignment>
)

private data class MosaicCandidate(
    val assignment: MosaicBandAssignment,
    val score: Float
)

private data class MosaicRenderAssignment(
    val assignment: MosaicBandAssignment,
    val item: MosaicBandItem
)

fun nearestMosaicColumnCount(
    availableWidth: Float,
    targetRowHeight: Float
): Int {
    if (availableWidth <= 0f || targetRowHeight <= 0f) return DEFAULT_GRID_COLUMN_COUNT
    // Mosaic treats the row-height preference as a density hint. The resolved
    // column count then defines the real cell height used by bands, placeholders,
    // and fallback rows. This keeps Mosaic available at dense zoom levels where
    // the requested target row height is smaller than a useful Mosaic band.
    return (availableWidth / targetRowHeight)
        .roundToInt()
        .coerceIn(SUPPORTED_MOSAIC_COLUMN_COUNTS.first, SUPPORTED_MOSAIC_COLUMN_COUNTS.last)
}

fun mosaicLayoutSpecFor(
    availableWidth: Float,
    targetRowHeight: Float
): MosaicLayoutSpec? {
    if (availableWidth <= 0f || targetRowHeight <= 0f) return null
    val columnCount = nearestMosaicColumnCount(availableWidth, targetRowHeight)
    if (columnCount !in SUPPORTED_MOSAIC_COLUMN_COUNTS) return null
    return MosaicLayoutSpec(
        columnCount = columnCount,
        availableWidth = availableWidth,
        cellHeight = availableWidth / columnCount
    )
}

fun mosaicLayoutSpecForColumnCount(
    availableWidth: Float,
    columnCount: Int
): MosaicLayoutSpec? {
    if (availableWidth <= 0f || columnCount !in SUPPORTED_MOSAIC_COLUMN_COUNTS) return null
    return MosaicLayoutSpec(
        columnCount = columnCount,
        availableWidth = availableWidth,
        cellHeight = availableWidth / columnCount
    )
}

fun mosaicAssignmentLayoutSpec(columnCount: Int): MosaicLayoutSpec? {
    if (columnCount !in SUPPORTED_MOSAIC_COLUMN_COUNTS) return null
    return MosaicLayoutSpec(
        columnCount = columnCount,
        availableWidth = columnCount * MOSAIC_CANONICAL_CELL_SIZE,
        cellHeight = MOSAIC_CANONICAL_CELL_SIZE
    )
}

fun mosaicFallbackMinRowHeight(layoutSpec: MosaicLayoutSpec): Float =
    layoutSpec.cellHeight * MOSAIC_FALLBACK_MIN_ROW_HEIGHT_CELL_MULTIPLIER

fun mosaicSmallGroupFallbackRowHeight(
    layoutSpec: MosaicLayoutSpec,
    maxRowHeight: Float = Float.MAX_VALUE
): Float =
    (layoutSpec.cellHeight * MOSAIC_SMALL_GROUP_FALLBACK_ROW_HEIGHT_CELL_MULTIPLIER)
        .coerceAtMost(maxRowHeight)

fun mosaicFallbackRowHeight(
    layoutSpec: MosaicLayoutSpec,
    assetCount: Int,
    maxRowHeight: Float = Float.MAX_VALUE
): Float =
    if (assetCount in 1..MOSAIC_SMALL_GROUP_MAX_ASSETS) {
        mosaicSmallGroupFallbackRowHeight(layoutSpec, maxRowHeight)
    } else {
        layoutSpec.cellHeight
    }

fun mosaicFallbackMinRowHeight(
    layoutSpec: MosaicLayoutSpec,
    mosaicBandHeights: List<Float>
): Float {
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
    val bandFloor =
        representativeBandHeight?.let { it * MOSAIC_FALLBACK_TARGET_ROW_HEIGHT_BAND_MULTIPLIER }
            ?: 0f
    return maxOf(cellFloor, bandFloor)
}

fun mosaicFallbackRowMinHeight(
    layoutSpec: MosaicLayoutSpec,
    assetCount: Int,
    realMosaicBandHeights: List<Float>,
    maxRowHeight: Float = Float.MAX_VALUE
): Float {
    val mixedFloor = realMosaicBandHeights
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
        ?.let { it * MOSAIC_FALLBACK_ROW_MEDIAN_BAND_FRACTION }
    val fallbackOnlyFloor = if (assetCount in 1..MOSAIC_SMALL_GROUP_MAX_ASSETS) {
        mosaicSmallGroupFallbackRowHeight(layoutSpec, maxRowHeight)
    } else {
        layoutSpec.cellHeight * MOSAIC_FALLBACK_ROW_MEDIAN_BAND_FRACTION
    }
    return (mixedFloor ?: fallbackOnlyFloor).coerceAtMost(maxRowHeight)
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
     * one Mosaic band from the next 4-8 assets. If no template/permutation is
     * acceptable, advance by one asset and try again. Timeline Mosaic no longer
     * uses standard row packing as a scan heuristic, so difficult buckets may
     * do more candidate checks but can still find a valid band just after a bad
     * window.
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
            sourceIndex += 1
        }
    }
    return assignments
}

suspend fun buildMosaicAssignmentsWithProgress(
    assets: List<Asset>,
    layoutSpec: MosaicLayoutSpec,
    spacing: Float,
    enabledFamilies: Set<MosaicTemplateFamily> = MosaicTemplateFamily.defaultSet(),
    bandHeightLimit: Float = MOSAIC_CANONICAL_CELL_SIZE * MOSAIC_MAX_BAND_HEIGHT_TARGET_MULTIPLIER,
    progressBandBatchSize: Int = TIMELINE_MOSAIC_PROGRESS_BAND_BATCH_SIZE,
    maxRowHeight: Float,
    resumeCheckpoint: MosaicAssignmentCheckpoint? = null,
    onProgressChunk: suspend (MosaicAssignmentProgressChunk) -> Unit,
    onCheckpoint: suspend (MosaicAssignmentCheckpoint) -> Unit = {},
    shouldContinue: () -> Unit = {}
): List<MosaicBandAssignment> {
    if (assets.size < MIN_MOSAIC_ASSETS ||
        layoutSpec.columnCount !in SUPPORTED_MOSAIC_COLUMN_COUNTS ||
        enabledFamilies.isEmpty()
    ) return emptyList()
    val checkpoint = resumeCheckpoint?.takeIf { it.isValidFor(assets) }
    val assignments = checkpoint?.assignments?.toMutableList() ?: mutableListOf()
    var sourceIndex = checkpoint?.sourceIndex ?: 0
    var bandIndex = checkpoint?.bandIndex ?: 0
    var chunkStartIndex = checkpoint?.chunkStartIndex ?: 0
    var lastEmittedBandCount = checkpoint?.lastEmittedBandCount ?: 0
    val width = layoutSpec.columnCount * MOSAIC_CANONICAL_CELL_SIZE
    fun currentCheckpoint(): MosaicAssignmentCheckpoint =
        MosaicAssignmentCheckpoint(
            assignments = assignments.toList(),
            sourceIndex = sourceIndex,
            bandIndex = bandIndex,
            chunkStartIndex = chunkStartIndex,
            lastEmittedBandCount = lastEmittedBandCount
        )
    try {
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
                val batchSize = progressBandBatchSize.coerceAtLeast(1)
                val pendingBandCount = assignments.size - lastEmittedBandCount
                if (pendingBandCount >= batchSize && pendingBandCount % batchSize == 0) {
                    val chunkAssignments = assignments.drop(lastEmittedBandCount)
                    val chunkEndIndex =
                        candidate.assignment.sourceStartIndex + candidate.assignment.sourceCount
                    if (isStableProgressChunk(
                            assets = assets,
                            sourceStartIndex = chunkStartIndex,
                            sourceEndExclusive = chunkEndIndex,
                            assignments = chunkAssignments,
                            layoutSpec = layoutSpec,
                            spacing = spacing,
                            maxRowHeight = maxRowHeight
                        )
                    ) {
                        val emittedStartIndex = chunkStartIndex
                        chunkStartIndex = chunkEndIndex
                        lastEmittedBandCount = assignments.size
                        onCheckpoint(currentCheckpoint())
                        onProgressChunk(
                            MosaicAssignmentProgressChunk(
                                sourceStartIndex = emittedStartIndex,
                                sourceEndExclusive = chunkEndIndex,
                                assignments = chunkAssignments
                            )
                        )
                    }
                }
            } else {
                sourceIndex += 1
            }
        }
        val remainingAssignments = assignments.drop(lastEmittedBandCount)
        val remainingEndIndex = remainingAssignments.lastOrNull()
            ?.let { it.sourceStartIndex + it.sourceCount }
        if (remainingAssignments.isNotEmpty() && remainingEndIndex != null && remainingEndIndex > chunkStartIndex) {
            val emittedStartIndex = chunkStartIndex
            chunkStartIndex = remainingEndIndex
            lastEmittedBandCount = assignments.size
            onCheckpoint(currentCheckpoint())
            onProgressChunk(
                MosaicAssignmentProgressChunk(
                    sourceStartIndex = emittedStartIndex,
                    sourceEndExclusive = remainingEndIndex,
                    assignments = remainingAssignments
                )
            )
        }
        return assignments
    } catch (e: Exception) {
        onCheckpoint(currentCheckpoint())
        throw e
    }
}

private fun MosaicAssignmentCheckpoint.isValidFor(assets: List<Asset>): Boolean {
    if (sourceIndex < 0 ||
        bandIndex < 0 ||
        chunkStartIndex < 0 ||
        lastEmittedBandCount < 0 ||
        sourceIndex > assets.size ||
        chunkStartIndex > sourceIndex ||
        lastEmittedBandCount > assignments.size
    ) {
        return false
    }
    if (assignments.isEmpty()) return bandIndex == 0
    val sortedAssignments = assignments.sortedBy { it.sourceStartIndex }
    if (assignments != sortedAssignments || !validateMosaicAssignments(
            assignments = assignments,
            assets = assets,
            enabledFamilies = MosaicTemplateFamily.entries.toSet()
        )
    ) return false
    var previousEnd = 0
    assignments.forEach { assignment ->
        if (assignment.sourceStartIndex < previousEnd) return false
        if (assignment.sourceStartIndex < 0 || assignment.sourceCount <= 0) return false
        val endExclusive = assignment.sourceStartIndex + assignment.sourceCount
        if (endExclusive > assets.size) return false
        val expectedIds =
            assets.subList(assignment.sourceStartIndex, endExclusive).map { it.id }.toSet()
        val actualIds = assignment.tiles.map { it.assetId }.toSet()
        if (expectedIds != actualIds || actualIds.size != assignment.sourceCount) return false
        previousEnd = endExclusive
    }
    val maxBandIndex = assignments.maxOf { it.bandIndex }
    val maxEnd = assignments.maxOf { it.sourceStartIndex + it.sourceCount }
    return bandIndex == maxBandIndex + 1 && sourceIndex >= maxEnd
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
    @Suppress("UNUSED_VARIABLE")
    val legacyFallbackPolicy = promoteWideImages to minCompleteRowPhotos
    if (assets.isEmpty() || layoutSpec.availableWidth <= 0f) return emptyList()
    /*
     * Rendering is intentionally more defensive than assignment. Assignments
     * can be produced by previous async work and then consumed after dimensions,
     * families, or asset metadata have changed. Each assignment is replayed into
     * real display geometry and validated before it is allowed to reserve source
     * assets. Invalid bands become Mosaic fallback rows so Mosaic-enabled
     * screens never render standard justified RowItem fallback.
     */
    val byStart = assignments
        .mapNotNull { assignment ->
            if (!assignment.isValidForSourceAssets(assets)) return@mapNotNull null
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
    val fallbackTargetRowHeight = if (byStart.isEmpty()) {
        mosaicFallbackRowHeight(
            layoutSpec = layoutSpec,
            assetCount = assets.size,
            maxRowHeight = maxRowHeight
        )
    } else {
        mosaicFallbackMinRowHeight(layoutSpec, byStart.values.map { it.item.bandHeight })
    }
    val fallbackMinRowHeight = mosaicFallbackRowMinHeight(
        layoutSpec = layoutSpec,
        assetCount = assets.size,
        realMosaicBandHeights = byStart.values.map { it.item.bandHeight },
        maxRowHeight = maxRowHeight
    )
    val items = mutableListOf<PhotoGridDisplayItem>()
    var sourceIndex = 0
    /*
     * The final display list must cover every source asset exactly once, in
     * source order. A valid Mosaic at the current index wins; otherwise the gap
     * before the next valid Mosaic is rendered as full-width fallback Mosaic
     * bands. This keeps all photos visible without standard row packing.
     */
    while (sourceIndex < assets.size) {
        val mosaic = byStart[sourceIndex]
        if (mosaic != null) {
            items.add(mosaic.item)
            sourceIndex += mosaic.assignment.sourceCount
        } else {
            val nextMosaicStart = byStart.keys
                .filter { it > sourceIndex }
                .minOrNull() ?: assets.size
            val fallbackRows = buildFallbackMosaicRows(
                assets = assets,
                sourceStartIndex = sourceIndex,
                sourceEndIndex = nextMosaicStart,
                bucketIndex = bucketIndex,
                sectionLabel = sectionLabel,
                spacing = spacing,
                availableWidth = layoutSpec.availableWidth,
                targetRowHeight = maxOf(fallbackTargetRowHeight, fallbackMinRowHeight),
                maxRowHeight = maxRowHeight,
                minCompleteRowHeight = fallbackMinRowHeight
            )
            if (fallbackRows.isEmpty()) {
                sourceIndex = assets.size
            } else {
                items.addAll(fallbackRows)
                sourceIndex += fallbackRows.sumOf { it.sourceCount }.coerceAtLeast(1)
            }
        }
    }
    return items
}

fun validateMosaicAssignments(
    assignments: List<MosaicBandAssignment>,
    assets: List<Asset>,
    enabledFamilies: Set<MosaicTemplateFamily>
): Boolean {
    if (assignments.isEmpty()) return true
    var previousEnd = 0
    assignments.forEachIndexed { index, assignment ->
        val template = MOSAIC_TEMPLATES.firstOrNull { it.id == assignment.templateId }
            ?: return false
        if (template.family !in enabledFamilies || template.slotCount != assignment.sourceCount) {
            return false
        }
        if (assignment.bandIndex != index || assignment.sourceStartIndex < previousEnd) return false
        if (!assignment.isValidForSourceAssets(assets)) return false
        previousEnd = assignment.sourceStartIndex + assignment.sourceCount
    }
    return true
}

private fun MosaicBandAssignment.isValidForSourceAssets(assets: List<Asset>): Boolean {
    if (sourceStartIndex < 0 || sourceCount !in MIN_MOSAIC_ASSETS..MAX_MOSAIC_ASSETS) return false
    val endExclusive = sourceStartIndex + sourceCount
    if (endExclusive > assets.size || tiles.size != sourceCount) return false
    val expectedIds = assets.subList(sourceStartIndex, endExclusive).map { it.id }.toSet()
    val actualIds = tiles.map { it.assetId }.toSet()
    if (actualIds.size != sourceCount || actualIds != expectedIds) return false
    return tiles.map { it.visualOrder }.sorted() == (0 until sourceCount).toList()
}

private fun isStableProgressChunk(
    assets: List<Asset>,
    sourceStartIndex: Int,
    sourceEndExclusive: Int,
    assignments: List<MosaicBandAssignment>,
    layoutSpec: MosaicLayoutSpec,
    spacing: Float,
    maxRowHeight: Float
): Boolean {
    if (sourceEndExclusive <= sourceStartIndex || assignments.isEmpty()) return false
    val chunkAssets = assets.subList(sourceStartIndex, sourceEndExclusive)
    val shiftedAssignments = assignments.map { assignment ->
        assignment.copy(sourceStartIndex = assignment.sourceStartIndex - sourceStartIndex)
    }
    val displayItems = buildPhotoGridItemsWithMosaic(
        assets = chunkAssets,
        assignments = shiftedAssignments,
        bucketIndex = 0,
        sectionLabel = "progress",
        layoutSpec = layoutSpec,
        spacing = spacing,
        maxRowHeight = maxRowHeight,
        promoteWideImages = MOSAIC_FALLBACK_PROMOTE_WIDE_IMAGES,
        minCompleteRowPhotos = MOSAIC_FALLBACK_MIN_COMPLETE_ROW_PHOTOS
    )
    return displayItems.isNotEmpty() &&
            displayItems.all { item ->
                item is MosaicBandItem && item.kind == MosaicBandKind.REAL
            }
}

fun buildFallbackMosaicRows(
    assets: List<Asset>,
    sourceStartIndex: Int,
    sourceEndIndex: Int,
    bucketIndex: Int,
    sectionLabel: String,
    spacing: Float,
    availableWidth: Float,
    targetRowHeight: Float,
    maxRowHeight: Float,
    minCompleteRowHeight: Float
): List<RowItem> {
    if (sourceEndIndex <= sourceStartIndex) return emptyList()
    return packIntoRows(
        assets = assets.subList(sourceStartIndex, sourceEndIndex),
        bucketIndex = bucketIndex,
        sectionLabel = sectionLabel,
        availableWidth = availableWidth,
        targetRowHeight = targetRowHeight,
        spacing = spacing,
        maxRowHeight = maxRowHeight,
        promoteWideImages = MOSAIC_FALLBACK_PROMOTE_WIDE_IMAGES,
        minCompleteRowPhotos = MOSAIC_FALLBACK_MIN_COMPLETE_ROW_PHOTOS,
        kind = RowItemKind.MOSAIC_FALLBACK,
        sourceStartIndexOffset = sourceStartIndex,
        minCompleteRowHeight = minCompleteRowHeight,
        rowKeyPrefix = "mosaic_fallback_row"
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
     * Try larger bands first because a good 7/8-photo Mosaic usually gives more
     * visual variety and reduces fallback churn. We still score every enabled
     * template/permutation for every supported count and return the lowest score
     * overall, so a poor large candidate can lose to a cleaner smaller band.
     */
    for (count in maxCount downTo MIN_MOSAIC_ASSETS) {
        shouldContinue()
        val templates =
            MOSAIC_TEMPLATES.filter { it.slotCount == count && it.family in enabledFamilies }
        if (templates.isEmpty()) continue
        val window = assets.subList(sourceStartIndex, sourceStartIndex + count)
        for (permutation in mosaicCandidatePermutations(window, count)) {
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

internal fun mosaicCandidatePermutations(
    assets: List<Asset>,
    count: Int
): List<IntArray> {
    if (count <= EXHAUSTIVE_MOSAIC_PERMUTATION_MAX_ASSETS) return permutations(count)
    if (assets.size < count) return emptyList()
    val window = assets.take(count)
    val sourceOrder = IntArray(count) { it }
    val candidates = mutableListOf<IntArray>()

    fun addCandidate(values: IntArray) {
        if (values.size != count || values.toSet().size != count) return
        if (candidates.none { it.contentEquals(values) }) {
            candidates.add(values)
        }
    }

    addCandidate(sourceOrder)
    addCandidate(IntArray(count) { count - 1 - it })
    addCandidate(
        window.indices
            .sortedBy { window[it].aspectRatio }
            .toIntArray()
    )
    addCandidate(
        window.indices
            .sortedByDescending { window[it].aspectRatio }
            .toIntArray()
    )

    val featureCandidates = (
            window.indices.sortedByDescending { window[it].aspectRatio }.take(2) +
                    window.indices.sortedBy { window[it].aspectRatio }.take(2)
            ).distinct()
    featureCandidates.forEach { featureIndex ->
        addCandidate(
            (listOf(featureIndex) + window.indices.filter { it != featureIndex })
                .toIntArray()
        )
    }
    return candidates
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
    val tileAssets =
        orderedTiles.map { assignment -> assetsById[assignment.assetId] ?: return null }
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
        bandHeight = bandHeight,
        sourceStartIndex = sourceStartIndex,
        sourceCount = sourceCount,
        kind = MosaicBandKind.REAL
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

private fun permutations(size: Int): List<IntArray> {
    val result = mutableListOf<IntArray>()
    fun permute(
        values: IntArray,
        index: Int
    ) {
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

private const val MIN_MOSAIC_ASSETS = 4
private const val MAX_MOSAIC_ASSETS = 8
private const val EXHAUSTIVE_MOSAIC_PERMUTATION_MAX_ASSETS = 6
private const val MIN_ASPECT_RATIO = 0.1f
private const val MIN_TILE_SIZE = 42f
private const val AREA_BALANCE_WEIGHT = 0.08f
private const val GEOMETRY_TOLERANCE = 0.5f
