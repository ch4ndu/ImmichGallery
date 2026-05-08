package com.udnahc.immichgallery.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class ViewConfig(
    val mosaicEnabled: Boolean = true,
    val mosaicFamilies: Set<MosaicTemplateFamily> = MosaicTemplateFamily.defaultSet(),
    val cacheMosaicResults: Boolean = true,
    val disableZoomWhenMosaicEnabled: Boolean = true,
    val mosaicColumnCount: Int = DEFAULT_MOSAIC_COLUMN_COUNT
) {
    val normalized: ViewConfig
        get() = copy(
            mosaicFamilies = mosaicFamilies.normalizedMosaicFamilies(),
            mosaicColumnCount = mosaicColumnCount.coerceIn(
                SUPPORTED_MOSAIC_COLUMN_COUNTS.first,
                SUPPORTED_MOSAIC_COLUMN_COUNTS.last
            )
        )
}

const val DEFAULT_MOSAIC_COLUMN_COUNT = 4

enum class MosaicTemplateFamily(
    val persistedId: String,
    val tileCount: Int
) {
    FOUR_TILE("FOUR_TILE", 4),
    FIVE_TILE("FIVE_TILE", 5),
    SIX_TILE("SIX_TILE", 6);

    companion object {
        fun defaultSet(): Set<MosaicTemplateFamily> = entries.toSet()

        fun fromPersistedId(id: String): MosaicTemplateFamily? =
            entries.firstOrNull { it.persistedId == id }
    }
}

fun Set<MosaicTemplateFamily>.normalizedMosaicFamilies(): Set<MosaicTemplateFamily> =
    if (isEmpty()) MosaicTemplateFamily.defaultSet() else this
