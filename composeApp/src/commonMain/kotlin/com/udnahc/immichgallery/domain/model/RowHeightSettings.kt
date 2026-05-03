package com.udnahc.immichgallery.domain.model

const val DEFAULT_GRID_COLUMN_COUNT = 3

enum class RowHeightScope { TIMELINE, ALBUM_DETAIL, PERSON_DETAIL, SEARCH }

fun defaultTargetRowHeightForWidth(availableWidth: Float): Float =
    if (availableWidth > 0f) {
        availableWidth / DEFAULT_GRID_COLUMN_COUNT
    } else {
        DEFAULT_TARGET_ROW_HEIGHT
    }
