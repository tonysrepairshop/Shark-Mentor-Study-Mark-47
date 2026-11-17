package com.example.sharkmentor.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.sharkmentor.data.local.entity.ConcreteTargetEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConcreteTargetDao {
    @Query("SELECT * FROM concrete_targets WHERE id = 1 LIMIT 1")
    fun getTargetFlow(): Flow<ConcreteTargetEntity?>

    @Query("SELECT * FROM concrete_targets WHERE id = 1 LIMIT 1")
    suspend fun getTargetRaw(): ConcreteTargetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveTarget(target: ConcreteTargetEntity)

    @Query("DELETE FROM concrete_targets")
    suspend fun clearTarget()
}
