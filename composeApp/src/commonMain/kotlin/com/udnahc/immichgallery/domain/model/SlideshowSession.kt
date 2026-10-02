package com.udnahc.immichgallery.domain.model

import kotlin.random.Random

/** A Timeline slot keeps this token when its bucket is materialized. */
sealed interface SlideshowSlot {
    data class Asset(val assetId: String) : SlideshowSlot
    data class BucketOffset(val timeBucket: String, val localOffset: Int) : SlideshowSlot
}

data class SlideshowCandidate(
    val slot: SlideshowSlot,
    val assetId: String? = (slot as? SlideshowSlot.Asset)?.assetId
)

@ConsistentCopyVisibility
data class SlideshowTarget internal constructor(
    val slot: SlideshowSlot,
    internal val sourceGeneration: Long,
    internal val navigationGeneration: Long
)

/**
 * Pure viewer-lifetime order/history state. Confine mutations to one controller owner.
 * Build large candidate lists and shuffle cycles on Default, without resolving lazy assets.
 */
class SlideshowSession(
    candidates: List<SlideshowCandidate>,
    initialSlot: SlideshowSlot? = candidates.firstOrNull()?.slot,
    val order: SlideshowOrder = SlideshowOrder.SEQUENTIAL,
    private val random: Random = Random.Default,
    sourceRevision: Long = 0
) {
    private var source = candidates.toList()
    private var revision = sourceRevision
    private var sourceGeneration = 0L
    private var navigationGeneration = 0L
    private val history = mutableListOf<SlideshowCandidate>()
    private var historyIndex = -1
    private var remaining = mutableListOf<SlideshowSlot>()
    private var pending: SlideshowTarget? = null

    val current: SlideshowCandidate? get() = history.getOrNull(historyIndex)
    val currentPage: Int? get() = current?.let { selected -> source.indexOfFirst { it.slot == selected.slot } }
    val canGoPrevious: Boolean get() = historyIndex > 0 || (order == SlideshowOrder.SEQUENTIAL && source.size > 1)
    val size: Int get() = source.size

    init {
        (source.firstOrNull { it.slot == initialSlot } ?: source.firstOrNull())?.let {
            history += it
            historyIndex = 0
        }
        resetRemaining()
    }

    /** Calling repeatedly keeps one target until navigation or a real source change. */
    fun upcoming(): SlideshowTarget? {
        pending?.let { return it }
        val nextSlot = history.getOrNull(historyIndex + 1)?.slot ?: when (order) {
            SlideshowOrder.SEQUENTIAL -> {
                val page = currentPage ?: return null
                source.getOrNull((page + 1) % source.size)?.slot
            }

            SlideshowOrder.RANDOM -> {
                if (remaining.isEmpty()) {
                    remaining = source.map { it.slot }.shuffled(random).toMutableList()
                    if (remaining.size > 1 && remaining.first() == current?.slot) {
                        val first = remaining[0]
                        remaining[0] = remaining[1]
                        remaining[1] = first
                    }
                }
                remaining.firstOrNull()
            }
        } ?: return null
        return SlideshowTarget(nextSlot, sourceGeneration, navigationGeneration).also { pending = it }
    }

    /** Resolve at use time: a prefetched unresolved slot can now have an asset ID. */
    fun candidate(target: SlideshowTarget): SlideshowCandidate? =
        if (isCurrent(target)) source.firstOrNull { it.slot == target.slot } else null

    fun page(target: SlideshowTarget): Int? =
        candidate(target)?.let { selected -> source.indexOfFirst { it.slot == selected.slot } }

    fun isCurrent(target: SlideshowTarget): Boolean =
        target.sourceGeneration == sourceGeneration &&
                target.navigationGeneration == navigationGeneration && target == pending

    fun next(target: SlideshowTarget? = upcoming()): SlideshowCandidate? {
        val selected = target?.let(::candidate) ?: return null
        if (historyIndex < history.lastIndex) {
            historyIndex += 1
        } else {
            history += selected
            historyIndex = history.lastIndex
            remaining.remove(selected.slot)
        }
        invalidateTarget()
        return current
    }

    fun previous(): SlideshowCandidate? {
        if (historyIndex > 0) {
            historyIndex -= 1
        } else if (order == SlideshowOrder.SEQUENTIAL && source.size > 1) {
            val page = currentPage ?: return null
            history.add(0, source[(page - 1 + source.size) % source.size])
        } else {
            return current
        }
        invalidateTarget()
        return current
    }

    /** A user-selected page starts a new forward path without losing Previous history. */
    fun select(slot: SlideshowSlot): SlideshowCandidate? {
        val selected = source.firstOrNull { it.slot == slot } ?: return null
        if (selected.slot == current?.slot) return current
        while (history.lastIndex > historyIndex) history.removeAt(history.lastIndex)
        history += selected
        historyIndex = history.lastIndex
        remaining.remove(slot)
        invalidateTarget()
        return current
    }

    /**
     * Materialization only enriches identity. sourceRevision must change when the owner
     * knows unresolved membership/order changed without a visible token/asset-ID change.
     * Never use a readiness/loading or loaded-asset-index revision here.
     */
    fun reconcileSource(candidates: List<SlideshowCandidate>, sourceRevision: Long = revision) {
        val oldBySlot = source.associateBy { it.slot }
        val changed = sourceRevision != revision || source.map { it.slot } != candidates.map { it.slot } ||
                candidates.any { next ->
                    val previousId = oldBySlot[next.slot]?.assetId
                    previousId != null && next.assetId != null && previousId != next.assetId
                }
        val oldCurrent = current
        val oldPage = currentPage ?: 0
        source = candidates.map { next ->
            if (!changed && next.assetId == null) next.copy(assetId = oldBySlot[next.slot]?.assetId)
            else next
        }
        revision = sourceRevision
        val bySlot = source.associateBy { it.slot }
        val byAsset = source.mapNotNull { item -> item.assetId?.let { it to item } }.toMap()
        fun resolve(item: SlideshowCandidate): SlideshowCandidate? =
            item.assetId?.let { byAsset[it] } ?: bySlot[item.slot]?.takeIf { next ->
                item.assetId == null || next.assetId == null || item.assetId == next.assetId
            }?.let { next -> if (next.assetId == null) next.copy(assetId = item.assetId) else next }

        val retained = history.mapIndexedNotNull { index, item -> resolve(item)?.let { index to it } }
        val retainedIndex = retained.indexOfFirst { it.first == historyIndex }
        history.clear()
        history += retained.map { it.second }
        if (retainedIndex >= 0) {
            historyIndex = retainedIndex
        } else {
            val replacement = oldCurrent?.let(::resolve) ?: source.getOrNull(oldPage.coerceAtMost(source.lastIndex))
            if (replacement == null) {
                historyIndex = -1
            } else {
                val replacementIndex = retained.indexOfLast {
                    it.first < historyIndex && it.second.slot == replacement.slot
                }.takeIf { it >= 0 } ?: history.indexOfFirst {
                    it.slot == replacement.slot
                }.takeIf { it >= 0 }
                historyIndex = replacementIndex ?: retained.count { it.first < historyIndex }
                if (replacementIndex == null) history.add(historyIndex, replacement)
            }
        }
        if (changed) {
            sourceGeneration += 1
            resetRemaining()
            invalidateTarget()
        }
    }

    private fun resetRemaining() {
        remaining = if (order == SlideshowOrder.RANDOM) {
            val retainedForwardSlots = history.drop(historyIndex.coerceAtLeast(0)).map { it.slot }.toSet()
            source.map { it.slot }.filterNot { it in retainedForwardSlots }.shuffled(random).toMutableList()
        } else mutableListOf()
    }

    private fun invalidateTarget() {
        navigationGeneration += 1
        pending = null
    }
}
