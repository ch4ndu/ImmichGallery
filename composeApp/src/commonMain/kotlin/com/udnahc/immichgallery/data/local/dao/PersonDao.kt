package com.udnahc.immichgallery.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.udnahc.immichgallery.data.local.entity.AssetEntity
import com.udnahc.immichgallery.data.local.entity.PersonAssetCrossRef
import com.udnahc.immichgallery.data.local.entity.PersonEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PersonDao {
    @Upsert
    suspend fun upsertPeople(people: List<PersonEntity>)

    @Query("SELECT * FROM people WHERE isHidden = 0 ORDER BY sortOrder ASC")
    fun observePeople(): Flow<List<PersonEntity>>

    @Upsert
    suspend fun upsertPersonRefs(refs: List<PersonAssetCrossRef>)

    @Query("DELETE FROM person_asset_refs WHERE personId = :personId")
    suspend fun clearPersonRefs(personId: String)

    @Query(
        """
        DELETE FROM person_asset_refs
        WHERE personId = :personId
        AND sortOrder >= :startSortOrder
        AND sortOrder < :endSortOrder
        """
    )
    suspend fun clearPersonRefsInSortRange(
        personId: String,
        startSortOrder: Int,
        endSortOrder: Int
    )

    @Query(
        """
        DELETE FROM person_asset_refs
        WHERE personId = :personId
        AND sortOrder >= :startSortOrder
        """
    )
    suspend fun clearPersonRefsFromSortOrder(personId: String, startSortOrder: Int)

    @Query("SELECT COUNT(*) FROM person_asset_refs WHERE personId = :personId")
    suspend fun getPersonAssetCount(personId: String): Int

    @Transaction
    @Query(
        """
        SELECT a.* FROM assets a
        INNER JOIN person_asset_refs r ON a.id = r.assetId
        WHERE r.personId = :personId
        ORDER BY r.sortOrder ASC
        """
    )
    fun observePersonAssets(personId: String): Flow<List<AssetEntity>>

    @Transaction
    @Query(
        """
        SELECT a.* FROM assets a
        INNER JOIN person_asset_refs r ON a.id = r.assetId
        WHERE r.personId = :personId
        ORDER BY r.sortOrder ASC
        """
    )
    suspend fun getPersonAssets(personId: String): List<AssetEntity>

    @Query("SELECT COUNT(*) FROM people WHERE isHidden = 0")
    suspend fun getPeopleCount(): Int

    @Query("DELETE FROM people")
    suspend fun clearPeople()

    @Query("DELETE FROM person_asset_refs")
    suspend fun clearAllPersonRefs()

    @Transaction
    suspend fun replacePeople(people: List<PersonEntity>) {
        clearPeople()
        upsertPeople(people)
    }

    @Transaction
    suspend fun replacePersonRefs(personId: String, refs: List<PersonAssetCrossRef>) {
        clearPersonRefs(personId)
        upsertPersonRefs(refs)
    }

    @Transaction
    suspend fun replacePersonRefsInSortRange(
        personId: String,
        startSortOrder: Int,
        endSortOrder: Int,
        refs: List<PersonAssetCrossRef>
    ) {
        clearPersonRefsInSortRange(personId, startSortOrder, endSortOrder)
        upsertPersonRefs(refs)
    }

    @Transaction
    suspend fun replacePersonRefsFromSortOrder(
        personId: String,
        startSortOrder: Int,
        refs: List<PersonAssetCrossRef>
    ) {
        clearPersonRefsFromSortOrder(personId, startSortOrder)
        upsertPersonRefs(refs)
    }
}
