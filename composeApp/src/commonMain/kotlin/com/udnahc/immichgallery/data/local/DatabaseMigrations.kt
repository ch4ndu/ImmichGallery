package com.udnahc.immichgallery.data.local

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection

internal val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(connection: SQLiteConnection) = Unit
}
