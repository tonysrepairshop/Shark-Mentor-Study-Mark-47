package com.example.sharkmentor.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.sharkmentor.data.local.entity.ErrorQuestionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ErrorQuestionDao {
    @Query("SELECT * FROM error_questions WHERE state = 0 OR dueDateMillis <= :now ORDER BY dueDateMillis ASC")
    fun getDueQuestions(now: Long = System.currentTimeMillis()): Flow<List<ErrorQuestionEntity>>

    @Query("""
        SELECT * FROM error_questions 
        WHERE state = 0 OR dueDateMillis <= :now 
        ORDER BY state ASC, dueDateMillis ASC 
        LIMIT :limit
    """)
    suspend fun getTrainingBatchDirect(
        now: Long = System.currentTimeMillis(), 
        limit: Int = 20
    ): List<ErrorQuestionEntity>

    @Query("""
        SELECT * FROM error_questions 
        WHERE subject = :subject AND (state = 0 OR dueDateMillis <= :now) 
        ORDER BY state ASC, dueDateMillis ASC 
        LIMIT :limit
    """)
    suspend fun getTrainingBatchBySubjectDirect(
        subject: String,
        now: Long = System.currentTimeMillis(), 
        limit: Int = 20
    ): List<ErrorQuestionEntity>

    @Query("SELECT COUNT(*) FROM error_questions WHERE state = 0 OR dueDateMillis <= :now")
    fun getDueCountFlow(now: Long = System.currentTimeMillis()): Flow<Int>

    @Query("SELECT COUNT(*) FROM error_questions WHERE dueDateMillis <= :now")
    fun getDueQuestionsCount(now: Long = System.currentTimeMillis()): Flow<Int>

    @Query("SELECT * FROM error_questions ORDER BY createdAtMillis DESC")
    fun getAllQuestions(): Flow<List<ErrorQuestionEntity>>

    @Query("SELECT * FROM error_questions ORDER BY createdAtMillis DESC")
    suspend fun getAllQuestionsDirect(): List<ErrorQuestionEntity>

    @Query("SELECT * FROM error_questions")
    suspend fun getAllQuestionsRaw(): List<ErrorQuestionEntity>

    @Query("SELECT * FROM error_questions WHERE topic = :topic ORDER BY createdAtMillis DESC")
    fun getQuestionsByTopic(topic: String): Flow<List<ErrorQuestionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertQuestion(question: ErrorQuestionEntity): Long

    @Update
    suspend fun updateQuestion(question: ErrorQuestionEntity)

    @Delete
    suspend fun deleteQuestion(question: ErrorQuestionEntity)

    /** Writes the AI-detected sub-topic tag for a single question. */
    @Query("UPDATE error_questions SET aiTopic = :topic, isSynced = 0, lastModifiedMillis = :now WHERE id = :id")
    suspend fun updateAiTopic(id: Long, topic: String, now: Long = System.currentTimeMillis())

    /**
     * Updates a question's subject, topic, and AI sub-topic tag and marks it as unsynced
     * (`isSynced = 0`) so Cloud Sync propagates the user's or AI's reclassification.
     */
    @Query("""
        UPDATE error_questions
        SET subject = :subject,
            topic = :topic,
            aiTopic = :aiTopic,
            isSynced = 0,
            lastModifiedMillis = :now
        WHERE id = :id
    """)
    suspend fun updateQuestionClassification(
        id: Long,
        subject: String,
        topic: String,
        aiTopic: String?,
        now: Long = System.currentTimeMillis()
    )

    /**
     * Returns up to [limit] questions that have an image URI, no AI topic tag yet,
     * and have not been manually given a custom topic by the user.
     * Used by [TopicTaggingWorker] for batch processing.
     */
    @Query("""
        SELECT * FROM error_questions
        WHERE aiTopic IS NULL
          AND (topic = '' OR topic = 'Genel')
          AND questionImageUri != ''
        ORDER BY createdAtMillis ASC
        LIMIT :limit
    """)
    suspend fun getUntaggedQuestions(limit: Int = 10): List<ErrorQuestionEntity>
}
