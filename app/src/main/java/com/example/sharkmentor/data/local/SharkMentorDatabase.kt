package com.example.sharkmentor.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.example.sharkmentor.data.local.dao.ErrorQuestionDao
import com.example.sharkmentor.data.local.dao.GoalsDao
import com.example.sharkmentor.data.local.dao.SessionDao
import com.example.sharkmentor.data.local.dao.TargetDao
import com.example.sharkmentor.data.local.dao.SyncDao
import com.example.sharkmentor.data.local.entity.ErrorQuestionEntity
import com.example.sharkmentor.data.local.entity.FocusSessionEntity
import com.example.sharkmentor.data.local.entity.ResourceEntity
import com.example.sharkmentor.data.local.entity.TargetEntity
import com.example.sharkmentor.data.local.entity.TaskEntity
import com.example.sharkmentor.data.local.entity.TopicGoalEntity
import com.example.sharkmentor.data.local.entity.DailyReflectionEntity
import com.example.sharkmentor.data.local.dao.DailyReflectionDao
import com.example.sharkmentor.data.local.entity.ConcreteTargetEntity
import com.example.sharkmentor.data.local.entity.MonthlyExamNetEntity
import com.example.sharkmentor.data.local.entity.PendingDeletionEntity
import com.example.sharkmentor.data.local.dao.ConcreteTargetDao
import com.example.sharkmentor.data.local.dao.MonthlyExamNetDao
import com.example.sharkmentor.data.local.dao.PendingDeletionDao

import com.example.sharkmentor.data.local.dao.AgentMemoryDao
import com.example.sharkmentor.data.local.dao.NetProjectionDao
import com.example.sharkmentor.data.local.entity.AgentMemoryEntity
import com.example.sharkmentor.data.local.dao.PlanDao
import com.example.sharkmentor.data.local.dao.PlanFolderDao
import com.example.sharkmentor.data.local.dao.InstitutionScheduleDao
import com.example.sharkmentor.data.local.entity.NetProjectionEntity
import com.example.sharkmentor.data.local.entity.PlanEntity
import com.example.sharkmentor.data.local.entity.PlanFolderEntity
import com.example.sharkmentor.data.local.entity.InstitutionScheduleEntity
import com.example.sharkmentor.data.local.entity.TrainingFolderEntity
import com.example.sharkmentor.data.local.entity.TrainingQuestionEntity
import com.example.sharkmentor.data.local.dao.TrainingFolderDao
import com.example.sharkmentor.data.local.dao.TrainingQuestionDao
import com.example.sharkmentor.data.local.dao.NoteDao
import com.example.sharkmentor.data.local.dao.StudyPlanDao
import com.example.sharkmentor.data.local.entity.ActivePlanEntity
import com.example.sharkmentor.data.local.entity.StudyTaskEntity
import com.example.sharkmentor.data.local.entity.NoteEntity

@Database(
    entities = [
        TargetEntity::class,
        TaskEntity::class,
        FocusSessionEntity::class,
        ErrorQuestionEntity::class,
        TopicGoalEntity::class,
        ResourceEntity::class,
        DailyReflectionEntity::class,
        ConcreteTargetEntity::class,
        MonthlyExamNetEntity::class,
        PendingDeletionEntity::class,
        AgentMemoryEntity::class,
        NetProjectionEntity::class,
        PlanFolderEntity::class,
        PlanEntity::class,
        InstitutionScheduleEntity::class,
        TrainingFolderEntity::class,
        TrainingQuestionEntity::class,
        NoteEntity::class,
        ActivePlanEntity::class,
        StudyTaskEntity::class
    ],
    version = 37,
    exportSchema = false
)
abstract class SharkMentorDatabase : RoomDatabase() {
    abstract fun targetDao(): TargetDao
    abstract fun sessionDao(): SessionDao
    abstract fun errorQuestionDao(): ErrorQuestionDao
    abstract fun goalsDao(): GoalsDao
    abstract fun dailyReflectionDao(): DailyReflectionDao
    abstract fun concreteTargetDao(): ConcreteTargetDao
    abstract fun monthlyExamNetDao(): MonthlyExamNetDao
    abstract fun pendingDeletionDao(): PendingDeletionDao
    abstract fun syncDao(): SyncDao
    abstract fun agentMemoryDao(): AgentMemoryDao
    abstract fun netProjectionDao(): NetProjectionDao
    abstract fun planFolderDao(): PlanFolderDao
    abstract fun planDao(): PlanDao
    abstract fun institutionScheduleDao(): InstitutionScheduleDao
    abstract fun trainingFolderDao(): TrainingFolderDao
    abstract fun trainingQuestionDao(): TrainingQuestionDao
    abstract fun noteDao(): NoteDao
    abstract fun studyPlanDao(): StudyPlanDao

    companion object {
        @Volatile
        private var INSTANCE: SharkMentorDatabase? = null

        fun getDatabase(context: android.content.Context): SharkMentorDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = androidx.room.Room.databaseBuilder(
                    context.applicationContext,
                    SharkMentorDatabase::class.java,
                    "sharkmentor_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
