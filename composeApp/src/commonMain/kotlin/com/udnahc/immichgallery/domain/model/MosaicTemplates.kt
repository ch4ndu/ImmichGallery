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
    fun widthForHeight(
        height: Float,
        aspects: FloatArray,
        spacing: Float
    ): Float

    fun heightForWidth(
        width: Float,
        aspects: FloatArray,
        spacing: Float
    ): Float

    fun layout(
        x: Float,
        y: Float,
        width: Float,
        aspects: FloatArray,
        spacing: Float
    ): List<TileRect>
}

internal data class LeafNode(val slot: Int) : MosaicNode {
    override fun widthForHeight(
        height: Float,
        aspects: FloatArray,
        spacing: Float
    ): Float =
        height * aspects[slot]

    override fun heightForWidth(
        width: Float,
        aspects: FloatArray,
        spacing: Float
    ): Float =
        width / aspects[slot]

    override fun layout(
        x: Float,
        y: Float,
        width: Float,
        aspects: FloatArray,
        spacing: Float
    ): List<TileRect> {
        val height = heightForWidth(width, aspects, spacing)
        return listOf(TileRect(slot, x, y, width, height))
    }
}

internal data class HorizontalNode(val children: List<MosaicNode>) : MosaicNode {
    override fun widthForHeight(
        height: Float,
        aspects: FloatArray,
        spacing: Float
    ): Float =
        children.sumOf { it.widthForHeight(height, aspects, spacing).toDouble() }.toFloat() +
                spacing * (children.size - 1)

    override fun heightForWidth(
        width: Float,
        aspects: FloatArray,
        spacing: Float
    ): Float {
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

    override fun layout(
        x: Float,
        y: Float,
        width: Float,
        aspects: FloatArray,
        spacing: Float
    ): List<TileRect> {
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

internal data class VerticalNode(val children: List<MosaicNode>) : MosaicNode {
    override fun widthForHeight(
        height: Float,
        aspects: FloatArray,
        spacing: Float
    ): Float {
        val usableHeight = (height - spacing * (children.size - 1)).coerceAtLeast(0f)
        val inverseAspectSum =
            children.sumOf { (1f / it.approxAspect(aspects)).toDouble() }.toFloat()
        return if (inverseAspectSum > 0f) usableHeight / inverseAspectSum else 0f
    }

    override fun heightForWidth(
        width: Float,
        aspects: FloatArray,
        spacing: Float
    ): Float =
        children.sumOf { it.heightForWidth(width, aspects, spacing).toDouble() }.toFloat() +
                spacing * (children.size - 1)

    override fun layout(
        x: Float,
        y: Float,
        width: Float,
        aspects: FloatArray,
        spacing: Float
    ): List<TileRect> {
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

private const val BINARY_SEARCH_STEPS = 24
