package com.udnahc.immichgallery.data.local

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import com.udnahc.immichgallery.data.local.dao.TimelineDao
import com.udnahc.immichgallery.data.local.entity.TimelineAssetEntity
import com.udnahc.immichgallery.data.local.entity.TimelineBucketEntity

@Database(
    entities = [TimelineBucketEntity::class, TimelineAssetEntity::class],
    version = 1
)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun timelineDao(): TimelineDao
}

@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}
