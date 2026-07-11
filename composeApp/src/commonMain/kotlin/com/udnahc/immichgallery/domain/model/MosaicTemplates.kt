package com.udnahc.immichgallery.domain.model

internal data class TileRect(
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
internal sealed interface MosaicNode {
    fun affine(
        aspects: FloatArray,
        spacing: Float
    ): MosaicAffine?

    fun widthForHeight(
        height: Float,
        aspects: FloatArray,
        spacing: Float
    ): Float = affine(aspects, spacing)?.widthForHeight(height) ?: Float.NaN

    fun heightForWidth(
        width: Float,
        aspects: FloatArray,
        spacing: Float
    ): Float = affine(aspects, spacing)?.heightForWidth(width) ?: Float.NaN

    fun layout(
        x: Float,
        y: Float,
        width: Float,
        aspects: FloatArray,
        spacing: Float
    ): List<TileRect>
}

internal data class MosaicAffine(
    val slope: Float,
    val intercept: Float
) {
    fun widthForHeight(height: Float): Float = slope * height + intercept

    fun heightForWidth(width: Float): Float = (width - intercept) / slope

    fun isValid(): Boolean = slope.isFinite() && slope > 0f && intercept.isFinite()
}

internal data class LeafNode(val slot: Int) : MosaicNode {
    override fun affine(
        aspects: FloatArray,
        spacing: Float
    ): MosaicAffine? {
        val aspect = aspects.getOrNull(slot) ?: return null
        return MosaicAffine(aspect, 0f).takeIf { it.isValid() }
    }

    override fun layout(
        x: Float,
        y: Float,
        width: Float,
        aspects: FloatArray,
        spacing: Float
    ): List<TileRect> {
        val height = heightForWidth(width, aspects, spacing)
        if (width <= 0f || height <= 0f || !width.isFinite() || !height.isFinite()) return emptyList()
        return listOf(TileRect(slot, x, y, width, height))
    }
}

internal data class HorizontalNode(val children: List<MosaicNode>) : MosaicNode {
    override fun affine(
        aspects: FloatArray,
        spacing: Float
    ): MosaicAffine? {
        if (children.isEmpty() || spacing < 0f || !spacing.isFinite()) return null
        val childAffines = children.map { it.affine(aspects, spacing) ?: return null }
        return MosaicAffine(
            slope = childAffines.sumOf { it.slope.toDouble() }.toFloat(),
            intercept = childAffines.sumOf { it.intercept.toDouble() }.toFloat() +
                    spacing * (children.size - 1)
        ).takeIf { it.isValid() }
    }

    override fun layout(
        x: Float,
        y: Float,
        width: Float,
        aspects: FloatArray,
        spacing: Float
    ): List<TileRect> {
        if (width <= 0f || !width.isFinite()) return emptyList()
        val height = heightForWidth(width, aspects, spacing)
        if (height <= 0f || !height.isFinite()) return emptyList()
        val result = mutableListOf<TileRect>()
        var cursorX = x
        for (child in children) {
            val childWidth = child.widthForHeight(height, aspects, spacing)
            if (childWidth <= 0f || !childWidth.isFinite()) return emptyList()
            result.addAll(child.layout(cursorX, y, childWidth, aspects, spacing))
            cursorX += childWidth + spacing
        }
        return result
    }
}

internal data class VerticalNode(val children: List<MosaicNode>) : MosaicNode {
    override fun affine(
        aspects: FloatArray,
        spacing: Float
    ): MosaicAffine? {
        if (children.isEmpty() || spacing < 0f || !spacing.isFinite()) return null
        val childAffines = children.map { it.affine(aspects, spacing) ?: return null }
        val inverseSlopeSum = childAffines.sumOf { (1f / it.slope).toDouble() }.toFloat()
        if (inverseSlopeSum <= 0f || !inverseSlopeSum.isFinite()) return null
        val heightIntercept = childAffines.sumOf {
            (-it.intercept / it.slope).toDouble()
        }.toFloat() + spacing * (children.size - 1)
        return MosaicAffine(
            slope = 1f / inverseSlopeSum,
            intercept = -heightIntercept / inverseSlopeSum
        ).takeIf { it.isValid() }
    }

    override fun layout(
        x: Float,
        y: Float,
        width: Float,
        aspects: FloatArray,
        spacing: Float
    ): List<TileRect> {
        if (width <= 0f || !width.isFinite()) return emptyList()
        val result = mutableListOf<TileRect>()
        var cursorY = y
        for (child in children) {
            val childHeight = child.heightForWidth(width, aspects, spacing)
            if (childHeight <= 0f || !childHeight.isFinite()) return emptyList()
            result.addAll(child.layout(x, cursorY, width, aspects, spacing))
            cursorY += childHeight + spacing
        }
        return result
    }
}

internal data class MosaicTemplate(
    val id: String,
    val family: MosaicTemplateFamily,
    val slotCount: Int,
    val root: MosaicNode
)

private fun leaf(slot: Int): MosaicNode = LeafNode(slot)
private fun h(vararg children: MosaicNode): MosaicNode = HorizontalNode(children.toList())
private fun v(vararg children: MosaicNode): MosaicNode = VerticalNode(children.toList())

internal val MOSAIC_TEMPLATES = listOf(
    MosaicTemplate(
        "4_feature_left_stack_3",
        MosaicTemplateFamily.FOUR_TILE,
        4,
        h(leaf(0), v(leaf(1), leaf(2), leaf(3)))
    ),
    MosaicTemplate(
        "4_feature_right_stack_3",
        MosaicTemplateFamily.FOUR_TILE,
        4,
        h(v(leaf(1), leaf(2), leaf(3)), leaf(0))
    ),
    MosaicTemplate(
        "4_top_feature_bottom_row",
        MosaicTemplateFamily.FOUR_TILE,
        4,
        v(leaf(0), h(leaf(1), leaf(2), leaf(3)))
    ),
    MosaicTemplate(
        "4_bottom_feature_top_row",
        MosaicTemplateFamily.FOUR_TILE,
        4,
        v(h(leaf(1), leaf(2), leaf(3)), leaf(0))
    ),
    MosaicTemplate(
        "5_feature_left_quad",
        MosaicTemplateFamily.FIVE_TILE,
        5,
        h(leaf(0), v(h(leaf(1), leaf(2)), h(leaf(3), leaf(4))))
    ),
    MosaicTemplate(
        "5_feature_right_quad",
        MosaicTemplateFamily.FIVE_TILE,
        5,
        h(v(h(leaf(1), leaf(2)), h(leaf(3), leaf(4))), leaf(0))
    ),
    MosaicTemplate(
        "5_top_feature_cluster",
        MosaicTemplateFamily.FIVE_TILE,
        5,
        v(leaf(0), h(v(leaf(1), leaf(2)), v(leaf(3), leaf(4))))
    ),
    MosaicTemplate(
        "5_bottom_feature_cluster",
        MosaicTemplateFamily.FIVE_TILE,
        5,
        v(h(v(leaf(1), leaf(2)), v(leaf(3), leaf(4))), leaf(0))
    ),
    MosaicTemplate(
        "6_feature_left_mixed",
        MosaicTemplateFamily.SIX_TILE,
        6,
        h(leaf(0), v(h(leaf(1), leaf(2)), h(leaf(3), leaf(4)), leaf(5)))
    ),
    MosaicTemplate(
        "6_feature_right_mixed",
        MosaicTemplateFamily.SIX_TILE,
        6,
        h(v(h(leaf(1), leaf(2)), h(leaf(3), leaf(4)), leaf(5)), leaf(0))
    ),
    MosaicTemplate(
        "6_three_columns_pairs",
        MosaicTemplateFamily.SIX_TILE,
        6,
        h(v(leaf(0), leaf(1)), v(leaf(2), leaf(3)), v(leaf(4), leaf(5)))
    ),
    MosaicTemplate(
        "6_two_rows_triples",
        MosaicTemplateFamily.SIX_TILE,
        6,
        v(h(leaf(0), leaf(1), leaf(2)), h(leaf(3), leaf(4), leaf(5)))
    ),
    MosaicTemplate(
        "7_feature_left_cluster",
        MosaicTemplateFamily.SEVEN_TILE,
        7,
        h(leaf(0), v(h(leaf(1), leaf(2)), h(leaf(3), leaf(4)), h(leaf(5), leaf(6))))
    ),
    MosaicTemplate(
        "7_feature_right_cluster",
        MosaicTemplateFamily.SEVEN_TILE,
        7,
        h(v(h(leaf(1), leaf(2)), h(leaf(3), leaf(4)), h(leaf(5), leaf(6))), leaf(0))
    ),
    MosaicTemplate(
        "7_top_feature_cluster",
        MosaicTemplateFamily.SEVEN_TILE,
        7,
        v(leaf(0), h(v(leaf(1), leaf(2)), v(leaf(3), leaf(4)), v(leaf(5), leaf(6))))
    ),
    MosaicTemplate(
        "7_bottom_feature_cluster",
        MosaicTemplateFamily.SEVEN_TILE,
        7,
        v(h(v(leaf(1), leaf(2)), v(leaf(3), leaf(4)), v(leaf(5), leaf(6))), leaf(0))
    ),
    MosaicTemplate(
        "8_two_rows_quads",
        MosaicTemplateFamily.EIGHT_TILE,
        8,
        v(h(leaf(0), leaf(1), leaf(2), leaf(3)), h(leaf(4), leaf(5), leaf(6), leaf(7)))
    ),
    MosaicTemplate(
        "8_four_columns_pairs",
        MosaicTemplateFamily.EIGHT_TILE,
        8,
        h(v(leaf(0), leaf(1)), v(leaf(2), leaf(3)), v(leaf(4), leaf(5)), v(leaf(6), leaf(7)))
    ),
    MosaicTemplate(
        "8_feature_left_cluster",
        MosaicTemplateFamily.EIGHT_TILE,
        8,
        h(leaf(0), v(h(leaf(1), leaf(2), leaf(3)), h(leaf(4), leaf(5), leaf(6), leaf(7))))
    ),
    MosaicTemplate(
        "8_feature_right_cluster",
        MosaicTemplateFamily.EIGHT_TILE,
        8,
        h(v(h(leaf(1), leaf(2), leaf(3)), h(leaf(4), leaf(5), leaf(6), leaf(7))), leaf(0))
    )
)

internal val MOSAIC_TEMPLATES_BY_ID: Map<String, MosaicTemplate> =
    MOSAIC_TEMPLATES.associateBy { it.id }

internal val MOSAIC_TEMPLATES_BY_COUNT_AND_FAMILY: Map<Pair<Int, MosaicTemplateFamily>, List<MosaicTemplate>> =
    MOSAIC_TEMPLATES.groupBy { it.slotCount to it.family }

internal val MOSAIC_TEMPLATE_FAMILY_BY_COUNT: Map<Int, MosaicTemplateFamily> =
    MosaicTemplateFamily.entries.associateBy { it.tileCount }
