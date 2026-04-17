package com.udnahc.immichgallery.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.udnahc.immichgallery.data.local.entity.SyncMetadataEntity

@Dao
interface SyncMetadataDao {
    @Upsert
    suspend fun upsert(metadata: SyncMetadataEntity)

    @Query("SELECT lastSyncedAt FROM sync_metadata WHERE scope = :scope")
    suspend fun getLastSyncedAt(scope: String): Long?

    @Query("DELETE FROM sync_metadata")
    suspend fun clearAll()
}
