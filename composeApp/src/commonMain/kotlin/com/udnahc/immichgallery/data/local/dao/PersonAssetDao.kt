package com.udnahc.immichgallery.data.local.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.udnahc.immichgallery.data.local.entity.PersonAssetEntity

@Dao
interface PersonAssetDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(assets: List<PersonAssetEntity>)

    @Query("DELETE FROM person_assets WHERE personId = :personId")
    suspend fun deleteByPerson(personId: String)

    @Transaction
    suspend fun replacePerson(personId: String, assets: List<PersonAssetEntity>) {
        deleteByPerson(personId)
        insertAll(assets)
    }

    @Query("SELECT * FROM person_assets WHERE personId = :personId ORDER BY sortOrder")
    fun getAssetsPaging(personId: String): PagingSource<Int, PersonAssetEntity>

    @Query("SELECT COUNT(*) FROM person_assets WHERE personId = :personId AND sortOrder < :sortOrder")
    suspend fun getAssetPosition(personId: String, sortOrder: Int): Int

    @Query("DELETE FROM person_assets")
    suspend fun deleteAll()
}
