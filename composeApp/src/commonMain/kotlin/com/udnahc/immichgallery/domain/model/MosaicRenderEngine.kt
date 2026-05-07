package com.udnahc.immichgallery.domain.model

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlin.math.ceil
import kotlin.time.measureTimedValue
import org.lighthousegames.logging.logging

enum class MosaicOwnerScope {
    TIMELINE_BUCKET,
    ALBUM,
    PERSON
}

@Immutable
data class MosaicOwnerKey(
    val scope: MosaicOwnerScope,
    val id: String
) {
    val stableKey: String = "${scope.name}/$id"
}

@Immutable
data class MosaicKeyScope(
    val owner: MosaicOwnerKey,
    val sectionKey: String,
    val columnCount: Int,
    val familiesKey: String,
    val contentFingerprint: String,
    val generation: Long = 0L
) {
    val stableSectionKey: String =
        "${owner.stableKey}|$sectionKey|c=$columnCount|f=$familiesKey|fp=$contentFingerprint"
}

@Immutable
data class ProgressChunk(
    val keyScope: MosaicKeyScope,
    val sectionLabel: String,
    val sourceStartIndex: Int,
    val sourceEndExclusive: Int,
    val assignments: List<MosaicBandAssignment>
)

@Immutable
@Serializable
data class MosaicSectionGeometryBand(
    val sourceStartIndex: Int,
    val sourceCount: Int,
    val height: Float
)

@Immutable
data class SectionGeometry(
    val keyScope: MosaicKeyScope,
    val placeholderHeight: Float,
    val displayItemCount: Int,
    val bands: List<MosaicSectionGeometryBand> = emptyList()
)

@Immutable
data class AggregateGeometry(
    val owner: MosaicOwnerKey,
    val key: String,
    val placeholderHeight: Float,
    val displayItemCount: Int
)

@Immutable
data class SectionReady(
    val keyScope: MosaicKeyScope,
    val assignments: List<MosaicBandAssignment>,
    val displayItems: List<PhotoGridDisplayItem>,
    val geometry: SectionGeometry
)

@Immutable
data class SectionFailed(
    val keyScope: MosaicKeyScope,
    val cause: Throwable
)

sealed interface MosaicSectionResult {
    val keyScope: MosaicKeyScope

    data class Ready(val value: SectionReady) : MosaicSectionResult {
        override val keyScope: MosaicKeyScope = value.keyScope
    }

    data class Failed(val value: SectionFailed) : MosaicSectionResult {
        override val keyScope: MosaicKeyScope = value.keyScope
    }
}

sealed interface MosaicSectionState {
    data object Pending : MosaicSectionState
    data class Partial(val chunks: List<ProgressChunk>) : MosaicSectionState
    data class Ready(
        val assignments: List<MosaicBandAssignment>,
        val displayBands: List<MosaicDisplayBandRecord> = emptyList()
    ) : MosaicSectionState
    data object Failed : MosaicSectionState
}

fun MosaicSectionState?.readySupersedesPartial(
    assignments: List<MosaicBandAssignment>,
    displayBands: List<MosaicDisplayBandRecord> = emptyList()
): MosaicSectionState.Ready =
    MosaicSectionState.Ready(assignments = assignments, displayBands = displayBands)

fun MosaicSectionState?.failedUnlessReady(): MosaicSectionState =
    if (this is MosaicSectionState.Ready) this else MosaicSectionState.Failed

data class MosaicSectionRequest(
    val keyScope: MosaicKeyScope,
    val assets: List<Asset>,
    val bucketIndex: Int,
    val sectionLabel: String,
    val assignmentLayoutSpec: MosaicLayoutSpec,
    val displayLayoutSpec: MosaicLayoutSpec,
    val spacing: Float,
    val maxRowHeight: Float,
    val enabledFamilies: Set<MosaicTemplateFamily> = MosaicTemplateFamily.defaultSet(),
    val progressBandBatchSize: Int = TIMELINE_MOSAIC_PROGRESS_BAND_BATCH_SIZE
)

@Immutable
data class MosaicAssignmentCheckpoint(
    val assignments: List<MosaicBandAssignment>,
    val sourceIndex: Int,
    val bandIndex: Int,
    val chunkStartIndex: Int,
    val lastEmittedBandCount: Int
)

interface MosaicSectionComputer {
    suspend fun computeSection(
        request: MosaicSectionRequest,
        resumeCheckpoint: MosaicAssignmentCheckpoint? = null,
        onProgressChunk: suspend (ProgressChunk) -> Unit = {},
        onCheckpoint: suspend (MosaicAssignmentCheckpoint) -> Unit = {},
        shouldContinue: () -> Unit = {}
    ): MosaicSectionResult

    fun projectSection(
        assets: List<Asset>,
        assignments: List<MosaicBandAssignment>,
        bucketIndex: Int,
        sectionLabel: String,
        layoutSpec: MosaicLayoutSpec,
        spacing: Float,
        maxRowHeight: Float
    ): List<PhotoGridDisplayItem>

    fun projectReadySection(
        assets: List<Asset>,
        assignments: List<MosaicBandAssignment>,
        bucketIndex: Int,
        sectionLabel: String,
        layoutSpec: MosaicLayoutSpec,
        spacing: Float,
        maxRowHeight: Float
    ): List<PhotoGridDisplayItem>

    fun projectPartialSection(
        assets: List<Asset>,
        chunks: List<ProgressChunk>,
        bucketIndex: Int,
        sectionLabel: String,
        layoutSpec: MosaicLayoutSpec,
        spacing: Float,
        maxRowHeight: Float
    ): List<PhotoGridDisplayItem>

    fun projectPartialSectionWithPlaceholders(
        assets: List<Asset>,
        chunks: List<ProgressChunk>,
        bucketIndex: Int,
        sectionLabel: String,
        layoutSpec: MosaicLayoutSpec,
        spacing: Float,
        maxRowHeight: Float
    ): List<PhotoGridDisplayItem>

    fun projectPartialSectionWithGeometry(
        assets: List<Asset>,
        chunks: List<ProgressChunk>,
        geometryBands: List<MosaicSectionGeometryBand>,
        bucketIndex: Int,
        sectionLabel: String,
        layoutSpec: MosaicLayoutSpec,
        spacing: Float,
        maxRowHeight: Float
    ): List<PhotoGridDisplayItem>?

    fun computeSectionGeometry(
        keyScope: MosaicKeyScope,
        displayItems: List<PhotoGridDisplayItem>,
        spacing: Float
    ): SectionGeometry

    fun computeAggregateGeometry(
        owner: MosaicOwnerKey,
        key: String,
        sectionGeometries: List<SectionGeometry>,
        headerCount: Int = 0,
        headerEstimatedHeight: Float = 0f,
        spacing: Float = GRID_SPACING_DP
    ): AggregateGeometry
}

/**
 * Domain-owned Mosaic facade for Timeline, Album Detail, and Person Detail.
 * Screen and repository code should depend on this facade so assignment,
 * progressive chunk, fallback, and geometry behavior stay identical.
 */
class MosaicRenderEngine : MosaicSectionComputer {
    private val log = logging("MosaicRenderEngine")

    override suspend fun computeSection(
        request: MosaicSectionRequest,
        resumeCheckpoint: MosaicAssignmentCheckpoint?,
        onProgressChunk: suspend (ProgressChunk) -> Unit,
        onCheckpoint: suspend (MosaicAssignmentCheckpoint) -> Unit,
        shouldContinue: () -> Unit
    ): MosaicSectionResult {
        log.d {
            "Mosaic section compute started key=${request.keyScope.stableSectionKey} " +
                "assets=${request.assets.size} resumed=${resumeCheckpoint != null}"
        }
        return try {
            val timed = measureTimedValue {
                buildMosaicAssignmentsWithProgress(
                    assets = request.assets,
                    layoutSpec = request.assignmentLayoutSpec,
                    spacing = request.spacing,
                    enabledFamilies = request.enabledFamilies,
                    progressBandBatchSize = request.progressBandBatchSize,
                    maxRowHeight = request.maxRowHeight,
                    resumeCheckpoint = resumeCheckpoint,
                    onProgressChunk = { chunk ->
                        onProgressChunk(
                            ProgressChunk(
                                keyScope = request.keyScope,
                                sectionLabel = request.sectionLabel,
                                sourceStartIndex = chunk.sourceStartIndex,
                                sourceEndExclusive = chunk.sourceEndExclusive,
                                assignments = chunk.assignments
                            )
                        )
                    },
                    onCheckpoint = onCheckpoint,
                    shouldContinue = shouldContinue
                )
            }
            val displayItems = projectReadySection(
                assets = request.assets,
                assignments = timed.value,
                bucketIndex = request.bucketIndex,
                sectionLabel = request.sectionLabel,
                layoutSpec = request.displayLayoutSpec,
                spacing = request.spacing,
                maxRowHeight = request.maxRowHeight
            )
            val geometry = computeSectionGeometry(request.keyScope, displayItems, request.spacing)
            log.d {
                "Mosaic section compute completed key=${request.keyScope.stableSectionKey} " +
                    "bands=${timed.value.size} items=${displayItems.size} " +
                    "height=${geometry.placeholderHeight} duration=${timed.duration}"
            }
            MosaicSectionResult.Ready(
                SectionReady(
                    keyScope = request.keyScope,
                    assignments = timed.value,
                    displayItems = displayItems,
                    geometry = geometry
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e(e) { "Mosaic section compute failed key=${request.keyScope.stableSectionKey}" }
            MosaicSectionResult.Failed(SectionFailed(request.keyScope, e))
        }
    }

    override fun projectSection(
        assets: List<Asset>,
        assignments: List<MosaicBandAssignment>,
        bucketIndex: Int,
        sectionLabel: String,
        layoutSpec: MosaicLayoutSpec,
        spacing: Float,
        maxRowHeight: Float
    ): List<PhotoGridDisplayItem> =
        projectReadySection(
            assets = assets,
            assignments = assignments,
            bucketIndex = bucketIndex,
            sectionLabel = sectionLabel,
            layoutSpec = layoutSpec,
            spacing = spacing,
            maxRowHeight = maxRowHeight
        )

    override fun projectReadySection(
        assets: List<Asset>,
        assignments: List<MosaicBandAssignment>,
        bucketIndex: Int,
        sectionLabel: String,
        layoutSpec: MosaicLayoutSpec,
        spacing: Float,
        maxRowHeight: Float
    ): List<PhotoGridDisplayItem> =
        buildPhotoGridItemsWithMosaic(
            assets = assets,
            assignments = assignments,
            bucketIndex = bucketIndex,
            sectionLabel = sectionLabel,
            layoutSpec = layoutSpec,
            spacing = spacing,
            maxRowHeight = maxRowHeight,
            promoteWideImages = MOSAIC_FALLBACK_PROMOTE_WIDE_IMAGES,
            minCompleteRowPhotos = MOSAIC_FALLBACK_MIN_COMPLETE_ROW_PHOTOS
        )

    override fun projectPartialSection(
        assets: List<Asset>,
        chunks: List<ProgressChunk>,
        bucketIndex: Int,
        sectionLabel: String,
        layoutSpec: MosaicLayoutSpec,
        spacing: Float,
        maxRowHeight: Float
    ): List<PhotoGridDisplayItem> {
        if (assets.isEmpty()) return emptyList()
        val items = mutableListOf<PhotoGridDisplayItem>()
        var cursor = 0
        chunks.sortedBy { it.sourceStartIndex }.forEach { chunk ->
            val chunkStart = chunk.sourceStartIndex.coerceIn(0, assets.size)
            val chunkEnd = chunk.sourceEndExclusive.coerceIn(chunkStart, assets.size)
            if (chunkStart > cursor) {
                items.addAll(projectFallbackSectionRange(assets, cursor, chunkStart, bucketIndex, sectionLabel, layoutSpec, spacing, maxRowHeight))
            }
            if (chunkEnd > chunkStart) {
                val shiftedAssignments = chunk.assignments.map { assignment ->
                    assignment.copy(sourceStartIndex = assignment.sourceStartIndex - chunkStart)
                }
                items.addAll(
                    projectSection(
                        assets = assets.subList(chunkStart, chunkEnd),
                        assignments = shiftedAssignments,
                        bucketIndex = bucketIndex,
                        sectionLabel = sectionLabel,
                        layoutSpec = layoutSpec,
                        spacing = spacing,
                        maxRowHeight = maxRowHeight
                    ).withAbsoluteMosaicSourceKeys(chunkStart, bucketIndex, sectionLabel)
                )
            }
            cursor = chunkEnd
        }
        if (cursor < assets.size) {
            items.addAll(projectFallbackSectionRange(assets, cursor, assets.size, bucketIndex, sectionLabel, layoutSpec, spacing, maxRowHeight))
        }
        return items
    }

    override fun projectPartialSectionWithPlaceholders(
        assets: List<Asset>,
        chunks: List<ProgressChunk>,
        bucketIndex: Int,
        sectionLabel: String,
        layoutSpec: MosaicLayoutSpec,
        spacing: Float,
        maxRowHeight: Float
    ): List<PhotoGridDisplayItem> {
        if (assets.isEmpty()) return emptyList()
        val items = mutableListOf<PhotoGridDisplayItem>()
        var cursor = 0
        chunks.sortedBy { it.sourceStartIndex }.forEach { chunk ->
            val chunkStart = chunk.sourceStartIndex.coerceIn(0, assets.size)
            val chunkEnd = chunk.sourceEndExclusive.coerceIn(chunkStart, assets.size)
            if (chunkStart > cursor) {
                items.addAll(projectPlaceholderSectionRange(cursor, chunkStart, bucketIndex, sectionLabel, layoutSpec, spacing))
            }
            if (chunkEnd > chunkStart) {
                val shiftedAssignments = chunk.assignments.map { assignment ->
                    assignment.copy(sourceStartIndex = assignment.sourceStartIndex - chunkStart)
                }
                val projected = projectSection(
                    assets = assets.subList(chunkStart, chunkEnd),
                    assignments = shiftedAssignments,
                    bucketIndex = bucketIndex,
                    sectionLabel = sectionLabel,
                    layoutSpec = layoutSpec,
                    spacing = spacing,
                    maxRowHeight = maxRowHeight
                ).withAbsoluteMosaicSourceKeys(chunkStart, bucketIndex, sectionLabel)
                val bands = projected.filterIsInstance<MosaicBandItem>()
                if (projected.all { it is MosaicBandItem && it.kind == MosaicBandKind.REAL } &&
                    bands.coversSourceRange(chunkStart, chunkEnd)
                ) {
                    items.addAll(projected)
                } else {
                    items.addAll(projectPlaceholderSectionRange(chunkStart, chunkEnd, bucketIndex, sectionLabel, layoutSpec, spacing))
                }
            }
            cursor = maxOf(cursor, chunkEnd)
        }
        if (cursor < assets.size) {
            items.addAll(projectPlaceholderSectionRange(cursor, assets.size, bucketIndex, sectionLabel, layoutSpec, spacing))
        }
        return items
    }

    override fun projectPartialSectionWithGeometry(
        assets: List<Asset>,
        chunks: List<ProgressChunk>,
        geometryBands: List<MosaicSectionGeometryBand>,
        bucketIndex: Int,
        sectionLabel: String,
        layoutSpec: MosaicLayoutSpec,
        spacing: Float,
        maxRowHeight: Float
    ): List<PhotoGridDisplayItem>? {
        if (assets.isEmpty()) return emptyList()
        if (!geometryBands.coversGeometrySourceRange(0, assets.size)) return null
        val items = mutableListOf<PhotoGridDisplayItem>()
        var cursor = 0
        chunks.sortedBy { it.sourceStartIndex }.forEach { chunk ->
            val chunkStart = chunk.sourceStartIndex.coerceIn(0, assets.size)
            val chunkEnd = chunk.sourceEndExclusive.coerceIn(chunkStart, assets.size)
            if (chunkStart < cursor) return null
            if (chunkStart > cursor) {
                items.addAll(projectGeometryPlaceholderRange(geometryBands, cursor, chunkStart, bucketIndex, sectionLabel, spacing) ?: return null)
            }
            if (chunkEnd > chunkStart) {
                val expectedHeight = geometryBands.heightForSourceRange(chunkStart, chunkEnd, spacing) ?: return null
                val shiftedAssignments = chunk.assignments.map { assignment ->
                    assignment.copy(sourceStartIndex = assignment.sourceStartIndex - chunkStart)
                }
                val projected = projectSection(
                    assets = assets.subList(chunkStart, chunkEnd),
                    assignments = shiftedAssignments,
                    bucketIndex = bucketIndex,
                    sectionLabel = sectionLabel,
                    layoutSpec = layoutSpec,
                    spacing = spacing,
                    maxRowHeight = maxRowHeight
                ).withAbsoluteMosaicSourceKeys(chunkStart, bucketIndex, sectionLabel)
                if (projected.all { it is MosaicBandItem && it.kind == MosaicBandKind.REAL } &&
                    projected.filterIsInstance<MosaicBandItem>().coversSourceRange(chunkStart, chunkEnd) &&
                    heightsMatch(estimatePhotoGridDisplayItemsHeight(projected, spacing), expectedHeight)
                ) {
                    items.addAll(projected)
                } else {
                    items.addAll(projectGeometryPlaceholderRange(geometryBands, chunkStart, chunkEnd, bucketIndex, sectionLabel, spacing) ?: return null)
                }
            }
            cursor = chunkEnd
        }
        if (cursor < assets.size) {
            items.addAll(projectGeometryPlaceholderRange(geometryBands, cursor, assets.size, bucketIndex, sectionLabel, spacing) ?: return null)
        }
        val expectedTotalHeight = geometryBands.heightForSourceRange(0, assets.size, spacing) ?: return null
        return items.takeIf { heightsMatch(estimatePhotoGridDisplayItemsHeight(it, spacing), expectedTotalHeight) }
    }

    fun projectFallbackSectionRange(
        assets: List<Asset>,
        sourceStartIndex: Int,
        sourceEndExclusive: Int,
        bucketIndex: Int,
        sectionLabel: String,
        layoutSpec: MosaicLayoutSpec,
        spacing: Float,
        maxRowHeight: Float
    ): List<MosaicBandItem> =
        buildFallbackMosaicBands(
            assets = assets,
            sourceStartIndex = sourceStartIndex,
            sourceEndIndex = sourceEndExclusive,
            bucketIndex = bucketIndex,
            sectionLabel = sectionLabel,
            layoutSpec = layoutSpec,
            spacing = spacing,
            bandHeight = mosaicFallbackRowHeight(
                layoutSpec = layoutSpec,
                assetCount = sourceEndExclusive - sourceStartIndex,
                maxRowHeight = maxRowHeight
            )
        )

    override fun computeSectionGeometry(
        keyScope: MosaicKeyScope,
        displayItems: List<PhotoGridDisplayItem>,
        spacing: Float
    ): SectionGeometry =
        SectionGeometry(
            keyScope = keyScope,
            placeholderHeight = estimatePhotoGridDisplayItemsHeight(displayItems, spacing),
            displayItemCount = displayItems.size,
            bands = displayItems.filterIsInstance<MosaicBandItem>()
                .filter { it.kind == MosaicBandKind.REAL }
                .map { band ->
                    MosaicSectionGeometryBand(
                        sourceStartIndex = band.sourceStartIndex,
                        sourceCount = band.sourceCount,
                        height = band.bandHeight
                    )
                }
        )

    override fun computeAggregateGeometry(
        owner: MosaicOwnerKey,
        key: String,
        sectionGeometries: List<SectionGeometry>,
        headerCount: Int,
        headerEstimatedHeight: Float,
        spacing: Float
    ): AggregateGeometry {
        val height = sectionGeometries.sumOf { it.placeholderHeight.toDouble() }.toFloat() +
            (headerEstimatedHeight * headerCount) +
            (spacing * (sectionGeometries.size + headerCount - 1).coerceAtLeast(0))
        return AggregateGeometry(
            owner = owner,
            key = key,
            placeholderHeight = height.coerceAtLeast(0f),
            displayItemCount = sectionGeometries.sumOf { it.displayItemCount } + headerCount
        )
    }
}

private const val GEOMETRY_HEIGHT_TOLERANCE = 0.5f

private fun heightsMatch(actual: Float, expected: Float): Boolean =
    kotlin.math.abs(actual - expected) <= GEOMETRY_HEIGHT_TOLERANCE

private fun List<MosaicSectionGeometryBand>.coversGeometrySourceRange(
    sourceStartIndex: Int,
    sourceEndExclusive: Int
): Boolean {
    if (sourceStartIndex == sourceEndExclusive) return true
    var cursor = sourceStartIndex
    filter { it.sourceStartIndex >= sourceStartIndex && it.sourceStartIndex < sourceEndExclusive }
        .sortedBy { it.sourceStartIndex }
        .forEach { band ->
            if (band.sourceCount <= 0 || band.height <= 0f) return false
            if (band.sourceStartIndex != cursor) return false
            cursor += band.sourceCount
            if (cursor > sourceEndExclusive) return false
        }
    return cursor == sourceEndExclusive
}

private fun List<MosaicSectionGeometryBand>.heightForSourceRange(
    sourceStartIndex: Int,
    sourceEndExclusive: Int,
    spacing: Float
): Float? {
    if (!coversGeometrySourceRange(sourceStartIndex, sourceEndExclusive)) return null
    val bands = filter { it.sourceStartIndex >= sourceStartIndex && it.sourceStartIndex < sourceEndExclusive }
        .sortedBy { it.sourceStartIndex }
    if (bands.isEmpty()) return 0f
    return bands.sumOf { it.height.toDouble() }.toFloat() +
        spacing * (bands.size - 1).coerceAtLeast(0)
}

private fun projectGeometryPlaceholderRange(
    geometryBands: List<MosaicSectionGeometryBand>,
    sourceStartIndex: Int,
    sourceEndExclusive: Int,
    bucketIndex: Int,
    sectionLabel: String,
    spacing: Float
): List<PlaceholderItem>? {
    val height = geometryBands.heightForSourceRange(sourceStartIndex, sourceEndExclusive, spacing) ?: return null
    return buildPhotoGridPlaceholderItemsForHeight(
        bucketIndex = bucketIndex,
        sectionLabel = sectionLabel,
        estimatedHeight = height,
        externalSpacing = spacing
    )
}

private fun projectPlaceholderSectionRange(
    sourceStartIndex: Int,
    sourceEndExclusive: Int,
    bucketIndex: Int,
    sectionLabel: String,
    layoutSpec: MosaicLayoutSpec,
    spacing: Float
): List<PlaceholderItem> {
    val sourceCount = sourceEndExclusive - sourceStartIndex
    if (sourceCount <= 0) return emptyList()
    val rows = ceil(sourceCount.toFloat() / layoutSpec.columnCount.coerceAtLeast(1)).toInt().coerceAtLeast(1)
    val estimatedHeight = (rows * layoutSpec.cellHeight + spacing * (rows - 1).coerceAtLeast(0))
        .coerceAtLeast(layoutSpec.cellHeight)
    return buildPhotoGridPlaceholderItemsForHeight(
        bucketIndex = bucketIndex,
        sectionLabel = sectionLabel,
        estimatedHeight = estimatedHeight,
        externalSpacing = spacing
    ).mapIndexed { index, item ->
        item.copy(
            gridKey = "pl_mosaic_gap_${bucketIndex}_${sectionLabel.hashCode()}_${sourceStartIndex}_${sourceEndExclusive}_$index"
        )
    }
}

private fun List<PhotoGridDisplayItem>.withAbsoluteMosaicSourceKeys(
    chunkStartIndex: Int,
    bucketIndex: Int,
    sectionLabel: String
): List<PhotoGridDisplayItem> =
    map { item ->
        if (item !is MosaicBandItem) return@map item
        val absoluteSourceStart = item.sourceStartIndex + chunkStartIndex
        item.copy(
            gridKey = "mosaic_partial_${bucketIndex}_${sectionLabel}_${absoluteSourceStart}",
            sourceStartIndex = absoluteSourceStart
        )
    }

private fun List<MosaicBandItem>.coversSourceRange(
    sourceStartIndex: Int,
    sourceEndExclusive: Int
): Boolean {
    if (isEmpty()) return false
    var cursor = sourceStartIndex
    sortedBy { it.sourceStartIndex }.forEach { band ->
        if (band.sourceStartIndex != cursor || band.sourceCount <= 0) return false
        cursor += band.sourceCount
    }
    return cursor == sourceEndExclusive
}
