package com.udnahc.immichgallery.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class ViewConfig(
    val mosaicEnabled: Boolean = false,
    val mosaicFamilies: Set<MosaicTemplateFamily> = MosaicTemplateFamily.defaultSet()
) {
    val normalized: ViewConfig
        get() = copy(mosaicFamilies = mosaicFamilies.normalizedMosaicFamilies())
}

enum class MosaicTemplateFamily(val persistedId: String, val tileCount: Int) {
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
