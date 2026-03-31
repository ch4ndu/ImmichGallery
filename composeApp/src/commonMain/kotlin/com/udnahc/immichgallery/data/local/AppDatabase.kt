package com.udnahc.immichgallery.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.udnahc.immichgallery.data.local.dao.TimelineDao
import com.udnahc.immichgallery.data.local.entity.TimelineAssetEntity
import com.udnahc.immichgallery.data.local.entity.TimelineBucketEntity

@Database(
    entities = [TimelineBucketEntity::class, TimelineAssetEntity::class],
    version = 1
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun timelineDao(): TimelineDao
}
