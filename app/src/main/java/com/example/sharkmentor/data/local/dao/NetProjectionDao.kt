package com.example.sharkmentor.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.sharkmentor.data.local.entity.NetProjectionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NetProjectionDao {
    @Query("SELECT * FROM net_projections WHERE id = 1")
    fun getLatestProjection(): Flow<NetProjectionEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveProjection(projection: NetProjectionEntity)

    @Query("SELECT * FROM net_projections WHERE isSynced = 0 AND isDeleted = 0")
    suspend fun getUnsyncedProjections(): List<NetProjectionEntity>

    @Query("UPDATE net_projections SET isSynced = 1, cloudId = :cloudId WHERE id = :localId")
    suspend fun markSynced(localId: Int, cloudId: String)
}
