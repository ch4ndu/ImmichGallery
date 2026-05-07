package com.udnahc.immichgallery.ui.component

import com.udnahc.immichgallery.domain.model.ErrorItem
import com.udnahc.immichgallery.domain.model.HeaderItem
import com.udnahc.immichgallery.domain.model.MosaicBandItem
import com.udnahc.immichgallery.domain.model.PhotoGridDisplayItem
import com.udnahc.immichgallery.domain.model.PhotoItem
import com.udnahc.immichgallery.domain.model.PlaceholderItem
import com.udnahc.immichgallery.domain.model.RowItem

fun photoGridDisplayItemContentType(item: PhotoGridDisplayItem): Any =
    when (item) {
        is RowItem -> "row_${item.kind}_${item.photos.size}_${item.isComplete}"
        is MosaicBandItem -> "mosaic_${item.tiles.size}_${item.kind}"
        is HeaderItem -> "header"
        is PlaceholderItem -> "placeholder"
        is ErrorItem -> "error"
        is PhotoItem -> "photo"
    }
