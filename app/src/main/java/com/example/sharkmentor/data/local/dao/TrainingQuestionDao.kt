package com.example.sharkmentor.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.sharkmentor.data.local.entity.TrainingQuestionEntity

@Dao
interface TrainingQuestionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertQuestion(question: TrainingQuestionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(questions: List<TrainingQuestionEntity>)

    @Query("SELECT * FROM training_questions WHERE folderId = :folderId AND isDeleted = 0")
    suspend fun getQuestionsByFolder(folderId: String): List<TrainingQuestionEntity>

    @Query("SELECT * FROM training_questions")
    suspend fun getAllQuestions(): List<TrainingQuestionEntity>

    @Query("SELECT * FROM training_questions WHERE isSynced = 0")
    suspend fun getUnsyncedQuestions(): List<TrainingQuestionEntity>

    @Query("UPDATE training_questions SET isSynced = 1, cloudId = :cloudId WHERE id = :localId")
    suspend fun markSynced(localId: String, cloudId: String)
}
