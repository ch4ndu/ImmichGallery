@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package com.udnahc.immichgallery.data.local

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import com.udnahc.immichgallery.data.local.dao.AlbumAssetDao
import com.udnahc.immichgallery.data.local.dao.PersonAssetDao
import com.udnahc.immichgallery.data.local.dao.TimelineAssetDao
import com.udnahc.immichgallery.data.local.entity.AlbumAssetEntity
import com.udnahc.immichgallery.data.local.entity.PersonAssetEntity
import com.udnahc.immichgallery.data.local.entity.TimelineAssetEntity

@Database(
    entities = [
        TimelineAssetEntity::class,
        AlbumAssetEntity::class,
        PersonAssetEntity::class
    ],
    version = 1,
    exportSchema = false
)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun timelineAssetDao(): TimelineAssetDao
    abstract fun albumAssetDao(): AlbumAssetDao
    abstract fun personAssetDao(): PersonAssetDao
}

@Suppress("NO_ACTUAL_FOR_EXPECT", "KotlinNoActualForExpect")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}
