package com.udnahc.immichgallery.data.local

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import com.udnahc.immichgallery.data.local.dao.AlbumDao
import com.udnahc.immichgallery.data.local.dao.AssetDao
import com.udnahc.immichgallery.data.local.dao.PersonDao
import com.udnahc.immichgallery.data.local.dao.SyncMetadataDao
import com.udnahc.immichgallery.data.local.dao.TimelineDao
import com.udnahc.immichgallery.data.local.entity.AlbumAssetCrossRef
import com.udnahc.immichgallery.data.local.entity.AlbumEntity
import com.udnahc.immichgallery.data.local.entity.AssetEntity
import com.udnahc.immichgallery.data.local.entity.PersonAssetCrossRef
import com.udnahc.immichgallery.data.local.entity.PersonEntity
import com.udnahc.immichgallery.data.local.entity.SyncMetadataEntity
import com.udnahc.immichgallery.data.local.entity.TimelineAssetCrossRef
import com.udnahc.immichgallery.data.local.entity.TimelineBucketEntity

@Database(
    entities = [
        TimelineBucketEntity::class,
        AssetEntity::class,
        TimelineAssetCrossRef::class,
        SyncMetadataEntity::class,
        AlbumEntity::class,
        AlbumAssetCrossRef::class,
        PersonEntity::class,
        PersonAssetCrossRef::class
    ],
    version = 5
)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun timelineDao(): TimelineDao
    abstract fun assetDao(): AssetDao
    abstract fun syncMetadataDao(): SyncMetadataDao
    abstract fun albumDao(): AlbumDao
    abstract fun personDao(): PersonDao
}

@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}
