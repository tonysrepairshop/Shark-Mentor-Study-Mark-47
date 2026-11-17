package com.example.sharkmentor.service.sync

import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.room.withTransaction
import com.example.sharkmentor.R
import com.example.sharkmentor.core.notification.SharkNotificationEngine
import com.example.sharkmentor.core.util.GoogleServicesHelper
import com.example.sharkmentor.data.local.dao.PlanFolderDao
import com.example.sharkmentor.data.local.entity.ActivePlanEntity
import com.example.sharkmentor.data.local.entity.PlanEntity
import com.example.sharkmentor.data.local.entity.ResourceEntity
import com.example.sharkmentor.data.local.entity.StudyTaskEntity
import com.example.sharkmentor.data.local.entity.TargetEntity
import com.example.sharkmentor.data.local.entity.TargetPeriod
import com.example.sharkmentor.data.local.entity.TargetType
import com.example.sharkmentor.data.local.entity.TaskEntity
import com.example.sharkmentor.data.sync.CloudSyncRepository
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "SyncWorker"
        const val UNIQUE_PERIODIC_WORK = "sharkmentor_periodic_sync"
        const val UNIQUE_IMMEDIATE_WORK = "sharkmentor_immediate_sync"

        fun enqueuePeriodicSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val periodicRequest = PeriodicWorkRequestBuilder<SyncWorker>(
                repeatInterval = 30,
                repeatIntervalTimeUnit = TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.LINEAR,
                    30,
                    TimeUnit.SECONDS
                )
                .addTag(UNIQUE_PERIODIC_WORK)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                periodicRequest
            )

            Log.i(TAG, "Periodic sync enqueued (every 30 min, network required)")
        }

        fun enqueueImmediateSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val oneTimeRequest = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .setInitialDelay(3, TimeUnit.SECONDS)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    30,
                    TimeUnit.SECONDS
                )
                .addTag(UNIQUE_IMMEDIATE_WORK)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "sharkmentor_one_time_sync",
                ExistingWorkPolicy.KEEP,
                oneTimeRequest
            )
            Log.d(TAG, "Immediate plan sync enqueued with KEEP policy")
        }

        fun cancelAllSync(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_PERIODIC_WORK)
            WorkManager.getInstance(context).cancelAllWorkByTag(UNIQUE_IMMEDIATE_WORK)
            Log.i(TAG, "All sync work cancelled")
        }
    }

    override suspend fun getForegroundInfo(): androidx.work.ForegroundInfo {
        val channelId = SharkNotificationEngine.CHANNEL_SYNC_BACKGROUND
        SharkNotificationEngine.init(applicationContext)

        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle("SharkMentor Senkronize Ediliyor")
            .setContentText("KoÃ§ planlarÄ± ve hedefler gÃ¼ncelleniyor...")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setColor(applicationContext.getColor(R.color.shark_blue_primary))
            .setOngoing(true)
            .setSilent(true)
            .build()

        return androidx.work.ForegroundInfo(991, notification)
    }

    override suspend fun doWork(): Result {
        val syncTag = "SharkMentorSync"
        Log.d(syncTag, "Starting SyncWorker execution... (attempt #$runAttemptCount)")
        val context = applicationContext
        val db = com.example.sharkmentor.data.local.SharkMentorDatabase.getDatabase(context)
        val userPrefs = com.example.sharkmentor.data.local.preferences.UserPreferences.getInstance(context)
        val firestore = GoogleServicesHelper.getFirebaseFirestore(context)

        if (firestore == null) {
            Log.w(syncTag, "Firestore unavailable, skipping sync cycle.")
            return Result.success()
        }

        val uid = try {
            GoogleServicesHelper.getFirebaseAuth(context)?.currentUser?.uid ?: "student_default"
        } catch (e: Throwable) {
            "student_default"
        }

        return try {
            Log.d(syncTag, ">>> STARTING FULL SYNC CYCLE <<<")
            val defaultInstitution = userPrefs.institutionId.takeIf { !it.isNullOrBlank() } ?: "INDIVIDUAL"
            val studentName = userPrefs.studentName ?: "Ã–ÄŸrenci"

            // 1. Ingest Active Study Plan (Map inside students/{uid})
            try {
                pullActiveStudyPlan(db, firestore, syncTag, uid)
            } catch (e: Exception) {
                Log.e(syncTag, "Err pull activeStudyPlan directly", e)
            }

            // 2. Inbound Downstream Pulls (Resources, Topics, Goals, Assigned Plans)
            try { pullRemoteResources(db, firestore, syncTag, uid) } catch (e: Exception) { Log.e(syncTag, "Err pull res", e) }
            try { pullRemoteTopics(db, firestore, syncTag, uid) } catch (e: Exception) { Log.e(syncTag, "Err pull topics", e) }
            try { pullRemoteGoals(db, firestore, syncTag, uid) } catch (e: Exception) { Log.e(syncTag, "Err pull goals", e) }
            try { pullRemoteAssignedPlans(db, firestore, syncTag, defaultInstitution, uid) } catch (e: Exception) { Log.e(syncTag, "Err pull plans", e) }
            try { pullRemoteSchedule(db, firestore, syncTag, uid) } catch (e: Exception) { Log.e(syncTag, "Err pull schedule", e) }

            // 3. Outbound Upstream Pushes
            var uploadedCount = 0
            uploadedCount += syncPendingDeletions(db, firestore, syncTag, defaultInstitution, uid)
            uploadedCount += syncLocalSessions(db, firestore, syncTag, defaultInstitution, studentName, uid)
            uploadedCount += syncPendingDeletedTargets(db, firestore, syncTag)
            uploadedCount += syncPendingDeletedResources(db, firestore, syncTag)
            uploadedCount += syncLocalPlans(db, firestore, syncTag, defaultInstitution, studentName, uid)
            uploadedCount += syncLocalTopics(db, firestore, syncTag, uid)
            uploadedCount += syncLocalResources(db, firestore, syncTag, uid)

            try {
                val repository = CloudSyncRepository(context)
                if (repository.getPendingSyncCount() > 0) repository.syncAll()
            } catch (e: Exception) {
                Log.e(syncTag, "Fallback sync err", e)
            }

            Log.d(syncTag, ">>> SYNC CYCLE FINISHED <<<")
            Result.success()
        } catch (e: Exception) {
            Log.e(syncTag, "SyncWorker failed: ${e.message}", e)
            if (runAttemptCount < 5) Result.retry() else Result.failure()
        }
    }

    // â”€â”€ 1. Ingest Active Study Plan Directly To Room â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    private suspend fun pullActiveStudyPlan(
        db: com.example.sharkmentor.data.local.SharkMentorDatabase,
        firestore: FirebaseFirestore,
        syncTag: String,
        uid: String
    ) = withContext(Dispatchers.IO) {
        val studentDoc = firestore.collection("students").document(uid).get(Source.SERVER).await()
        if (!studentDoc.exists()) return@withContext

        val rawPlan = studentDoc.get("activeStudyPlan") as? Map<*, *>
        val studyPlanDao = try { db.studyPlanDao() } catch (e: Exception) { null }

        if (studyPlanDao == null) {
            Log.w(syncTag, "studyPlanDao is not available in Room DB, skipping study plan ingestion.")
            return@withContext
        }

        if (rawPlan != null) {
            val title = rawPlan["title"] as? String ?: "Ã‡alÄ±ÅŸma PlanÄ±"
            val strategyNote = rawPlan["strategyNote"] as? String ?: ""
            val totalQuestions = (rawPlan["totalTargetQuestions"] as? Number)?.toInt() ?: 0
            val updatedAt = studentDoc.getTimestamp("activeStudyPlanUpdatedAt")?.toDate()?.time
                ?: System.currentTimeMillis()

            val planEntity = ActivePlanEntity(
                id = "CURRENT_ACTIVE_PLAN",
                title = title,
                strategyNote = strategyNote,
                totalTargetQuestions = totalQuestions,
                updatedAtTimestamp = updatedAt
            )

            val rawDays = rawPlan["days"] as? List<*> ?: emptyList<Any>()
            val taskEntities = mutableListOf<StudyTaskEntity>()
            val dayNames = listOf("Pazartesi", "SalÄ±", "Ã‡arÅŸamba", "PerÅŸembe", "Cuma", "Cumartesi", "Pazar")

            rawDays.forEachIndexed { defaultIdx, dayObj ->
                val dayMap = dayObj as? Map<*, *> ?: return@forEachIndexed
                val dayName = dayMap["day"] as? String ?: "GÃ¼n ${defaultIdx + 1}"
                val dayIdx = dayNames.indexOf(dayName).let { if (it == -1) defaultIdx else it }
                val tasks = dayMap["tasks"] as? List<*> ?: emptyList<Any>()

                tasks.forEach { taskObj ->
                    val taskMap = taskObj as? Map<*, *> ?: return@forEach
                    taskEntities.add(
                        StudyTaskEntity(
                            planId = "CURRENT_ACTIVE_PLAN",
                            dayName = dayName,
                            dayIndex = dayIdx,
                            time = taskMap["time"] as? String ?: "",
                            subject = taskMap["subject"] as? String ?: "Ders",
                            topic = taskMap["topic"] as? String ?: "",
                            goal = taskMap["goal"] as? String ?: "",
                            isCompleted = taskMap["isCompleted"] as? Boolean ?: false
                        )
                    )
                }
            }

            studyPlanDao.replaceActivePlanWithTasks(planEntity, taskEntities)
            Log.d(syncTag, "Successfully ingested active study plan and ${taskEntities.size} tasks into StudyPlanDao Room DB.")

            // --- BRIDGE TO LEGACY PlanDao FOR ANDROID UI ---
            try {
                val planFolderDao = db.planFolderDao()
                val planDao = db.planDao()
                
                var defaultFolderId = getFirstActiveFolderIdOrNull(planFolderDao)
                if (defaultFolderId == null) {
                    val newFolder = com.example.sharkmentor.data.local.entity.PlanFolderEntity(
                        id = "ACTIVE_PLAN_FOLDER",
                        title = "Aktif Ã‡alÄ±ÅŸma ProgramÄ±",
                        createdAt = System.currentTimeMillis(),
                        endTimestamp = System.currentTimeMillis() + (7L * 24 * 3600 * 1000),
                        isArchived = false
                    )
                    planFolderDao.insertFolder(newFolder)
                    defaultFolderId = "ACTIVE_PLAN_FOLDER"
                }

                val legacyPlanTasks = mutableListOf<com.example.sharkmentor.data.local.entity.PlanEntity>()
                
                rawDays.forEachIndexed { defaultIdx, dayObj ->
                    val dayMap = dayObj as? Map<*, *> ?: return@forEachIndexed
                    val dayName = dayMap["day"] as? String ?: "GÃ¼n ${defaultIdx + 1}"
                    val dayIdx = dayNames.indexOf(dayName).let { if (it == -1) defaultIdx else it }
                    val tasks = dayMap["tasks"] as? List<*> ?: emptyList<Any>()

                    tasks.forEachIndexed { taskIdx, taskObj ->
                        val taskMap = taskObj as? Map<*, *> ?: return@forEachIndexed
                        legacyPlanTasks.add(
                            com.example.sharkmentor.data.local.entity.PlanEntity(
                                id = "ACTIVE_PLAN_TASK_${dayIdx}_${taskIdx}_${System.currentTimeMillis() % 10000}",
                                folderId = defaultFolderId,
                                title = taskMap["topic"] as? String ?: taskMap["goal"] as? String ?: "GÃ¶rev",
                                subject = taskMap["subject"] as? String ?: "Genel",
                                dayOfWeek = dayIdx + 1, // 1=Mon
                                scheduledTime = taskMap["time"] as? String ?: "",
                                targetQuestions = (taskMap["targetQuestions"] as? Number)?.toInt() ?: 30,
                                isCompleted = taskMap["isCompleted"] as? Boolean ?: false,
                                orderIndex = taskIdx
                            )
                        )
                    }
                }
                
                // Perform transactional wipe and insert to ensure the UI updates instantly
                db.withTransaction {
                    planDao.deleteTasksByFolder(defaultFolderId)
                    if (legacyPlanTasks.isNotEmpty()) {
                        planDao.insertTasks(legacyPlanTasks)
                    }
                }
                Log.d(syncTag, "Successfully bridged active study plan to legacy PlanDao (Folder: $defaultFolderId).")
            } catch (e: Exception) {
                Log.e(syncTag, "Failed to bridge active study plan to legacy PlanDao: ${e.message}", e)
            }
            // -----------------------------------------------
        } else {
            studyPlanDao.clearPlan()
            studyPlanDao.clearTasks()
            Log.d(syncTag, "Active study plan removed from Firestore, wiped locally.")
        }
    }

    // â”€â”€ 2. Inbound Downstream Pull: Coach-Assigned Resources (With Differential Deletion) â”€â”€
    private suspend fun pullRemoteResources(
        db: com.example.sharkmentor.data.local.SharkMentorDatabase,
        firestore: FirebaseFirestore,
        syncTag: String,
        uid: String
    ) = withContext(Dispatchers.IO) {
        try {
            // First check the student's subcollection (where Web saves it)
            var snapshot = firestore.collection("students").document(uid)
                .collection("resources")
                .get(Source.SERVER).await()

            // Fallback to legacy root collection if subcollection is completely empty
            if (snapshot.isEmpty) {
                snapshot = firestore.collection("resources")
                    .whereEqualTo("studentId", uid)
                    .get(Source.SERVER).await()
            }

            val goalsDao = db.goalsDao()
            val remoteIds = snapshot.documents.mapNotNull { doc ->
                doc.getSafeLong("id") ?: doc.id.toLongOrNull() ?: abs(doc.id.hashCode().toLong())
            }

            if (remoteIds.isEmpty()) {
                goalsDao.clearAllResources()
                Log.d(syncTag, "No remote resources found. Purged all local Room resources.")
            } else {
                goalsDao.deleteOrphanedResources(remoteIds)
            }

            for (doc in snapshot.documents) {
                val isDeleted = doc.getSafeBoolean("isDeleted") ?: false
                val remoteId = doc.getSafeLong("id")
                    ?: doc.id.toLongOrNull()
                    ?: abs(doc.id.hashCode().toLong())

                if (isDeleted) {
                    goalsDao.deleteResourcePermanently(remoteId)
                    continue
                }

                val totalTarget = doc.getSafeInt("totalTarget")
                    ?: doc.getSafeInt("totalCount")
                    ?: 100
                val completedCount = doc.getSafeInt("completedCount") ?: 0
                val targetType = doc.getSafeString("targetType") ?: doc.getSafeString("resourceType") ?: "PAGE"
                val unitType = if (targetType == "QUESTION") "Soru" else "Sayfa"

                val rawTitle = doc.getSafeString("title") ?: "Kaynak Kitap"
                val publisher = doc.getSafeString("publisher")
                val finalTitle = if (publisher.isNullOrBlank()) rawTitle else "$publisher - $rawTitle"

                val resourceEntity = ResourceEntity(
                    id = remoteId,
                    title = finalTitle,
                    subject = doc.getSafeString("subject") ?: "Genel",
                    unitType = unitType,
                    totalUnits = totalTarget,
                    completedUnits = completedCount,
                    colorHex = doc.getSafeString("colorHex") ?: "#3B82F6",
                    isAssignedByCoach = doc.getSafeBoolean("isAssignedByCoach") ?: true,
                    isDeleted = false,
                    isSynced = true,
                    lastModifiedMillis = doc.getTimestamp("updatedAt")?.toDate()?.time
                        ?: doc.getSafeLong("updatedAt")
                        ?: System.currentTimeMillis()
                )

                goalsDao.upsertResource(resourceEntity)
                Log.d(syncTag, "Successfully saved remote resource to Room: id=$remoteId, title=${resourceEntity.title}")
            }
        } catch (e: Exception) {
            Log.e(syncTag, "CRITICAL FAILURE in pullRemoteResources: ${e.message}", e)
        }
    }

    // â”€â”€ 3. Inbound Downstream Pull: Coach-Assigned Topics â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    private suspend fun pullRemoteTopics(
        db: com.example.sharkmentor.data.local.SharkMentorDatabase,
        firestore: FirebaseFirestore,
        syncTag: String,
        uid: String
    ) = withContext(Dispatchers.IO) {
        try {
            val snapshot = firestore.collection("topics")
                .whereEqualTo("studentId", uid)
                .get(Source.SERVER).await()
            val syncDao = db.syncDao()
            val goalsDao = db.goalsDao()

            val remoteIds = snapshot.documents.mapNotNull { doc ->
                doc.getSafeLong("id") ?: doc.id.toLongOrNull() ?: abs(doc.id.hashCode().toLong())
            }

            if (remoteIds.isEmpty()) {
                goalsDao.clearAllTopics()
            } else {
                goalsDao.deleteOrphanedTopics(remoteIds)
            }

            for (doc in snapshot.documents) {
                val isDeleted = doc.getSafeBoolean("isDeleted") ?: false
                val remoteId = doc.getSafeLong("id")
                    ?: doc.id.toLongOrNull()
                    ?: abs(doc.id.hashCode().toLong())

                if (isDeleted) {
                    syncDao.deleteTopicPermanently(remoteId)
                    continue
                }

                val topicEntity = com.example.sharkmentor.data.local.entity.TopicGoalEntity(
                    id = remoteId,
                    subject = doc.getSafeString("subject") ?: "Genel",
                    topic = doc.getSafeString("topicName") ?: "Konu",
                    currentLevel = doc.getSafeInt("masteryLevel") ?: doc.getSafeInt("masteryScore") ?: 0,
                    targetLevel = doc.getSafeInt("targetMasteryScore") ?: 5,
                    notes = "",
                    isSynced = true,
                    isDeleted = false,
                    updatedAtMillis = doc.getTimestamp("updatedAt")?.toDate()?.time
                        ?: doc.getSafeLong("updatedAt")
                        ?: System.currentTimeMillis()
                )

                syncDao.upsertTopic(topicEntity)
                Log.d(syncTag, "Successfully saved remote topic to Room: id=$remoteId, topic=${topicEntity.topic}")
            }
        } catch (e: Exception) {
            Log.e(syncTag, "CRITICAL FAILURE in pullRemoteTopics: ${e.message}", e)
        }
    }

    // â”€â”€ 4. Inbound Downstream Pull: Coach-Assigned Goals (targets/goals) â”€â”€
    private suspend fun pullRemoteGoals(
        db: com.example.sharkmentor.data.local.SharkMentorDatabase,
        firestore: FirebaseFirestore,
        syncTag: String,
        uid: String
    ) = withContext(Dispatchers.IO) {
        try {
            // Web writes to students/{uid}/goals
            val goalsSnapshot = firestore.collection("students").document(uid)
                .collection("goals")
                .get(Source.SERVER).await()

            val targetDao = db.targetDao()
            val concreteTargetDao = db.concreteTargetDao()

            var latestMacroGoal: com.google.firebase.firestore.DocumentSnapshot? = null

            for (doc in goalsSnapshot.documents) {
                val isDeleted = doc.getSafeBoolean("isDeleted") ?: false
                val remoteId = doc.getSafeLong("id")
                    ?: doc.id.toLongOrNull()
                    ?: abs(doc.id.hashCode().toLong())

                if (isDeleted) {
                    targetDao.purgeTarget(remoteId)
                    continue
                }

                // Check if it's the latest macro goal
                val currentCreatedAt = doc.getTimestamp("createdAt")?.toDate()?.time ?: doc.id.toLongOrNull() ?: 0L
                val latestCreatedAt = latestMacroGoal?.getTimestamp("createdAt")?.toDate()?.time ?: latestMacroGoal?.id?.toLongOrNull() ?: -1L
                if (currentCreatedAt > latestCreatedAt) {
                    latestMacroGoal = doc
                }

                val title = doc.getSafeString("title") ?: "Hedef"
                val category = doc.getSafeString("category") ?: "CUSTOM"
                val isCompleted = doc.getSafeBoolean("isCompleted") ?: false

                val targetEntity = TargetEntity(
                    id = remoteId,
                    title = title,
                    description = doc.getSafeString("description") ?: "",
                    deadlineMillis = System.currentTimeMillis() + (7L * 24 * 60 * 60 * 1000),
                    type = TargetType.SHORT_TERM,
                    period = TargetPeriod.WEEKLY,
                    priority = "NORMAL",
                    category = category,
                    targetValue = null,
                    isCompleted = isCompleted,
                    isAssignedByCoach = true,
                    coachNote = doc.getSafeString("coachNote"),
                    date = "",
                    isSynced = true,
                    isDeleted = false,
                    lastModifiedMillis = System.currentTimeMillis()
                )

                targetDao.upsertTarget(targetEntity)
                Log.d(syncTag, "Successfully saved remote goal to Room: id=$remoteId, title=$title")
            }

            // Bridge to ConcreteTargetDao (Macro Target)
            latestMacroGoal?.let { doc ->
                val title = doc.getSafeString("title") ?: "Hedef"
                val targetRanking = doc.getSafeLong("targetRanking")?.toInt()
                val targetScoreOrRank = if (targetRanking != null && targetRanking > 0) "$targetRanking SÄ±ralama" else ""
                val concreteTarget = com.example.sharkmentor.data.local.entity.ConcreteTargetEntity(
                    id = 1,
                    examType = "YKS",
                    targetTitle = title,
                    targetScoreOrRank = targetScoreOrRank,
                    aiAnalysisSummary = doc.getSafeString("coachNote") ?: "",
                    targetRanking = targetRanking,
                    targetUniversity = doc.getSafeString("targetUniversity"),
                    targetDepartment = doc.getSafeString("targetDepartment"),
                    updatedAtMillis = System.currentTimeMillis()
                )
                concreteTargetDao.insertOrUpdateTarget(concreteTarget)
                Log.d(syncTag, "Successfully bridged latest macro goal into ConcreteTargetDao")
            }
        } catch (e: Exception) {
            Log.e(syncTag, "CRITICAL FAILURE in pullRemoteGoals: ${e.message}", e)
        }
    }

    // â”€â”€ 5. Inbound Downstream Pull: Legacy Plans / Targets â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    private suspend fun pullRemoteAssignedPlans(
        db: com.example.sharkmentor.data.local.SharkMentorDatabase,
        firestore: FirebaseFirestore,
        syncTag: String,
        instId: String,
        uid: String
    ) = withContext(Dispatchers.IO) {
        val collectionsToPull = listOf("plans", "targets")
        val targetDao = db.targetDao()
        val defaultFolderId = getFirstActiveFolderIdOrNull(db.planFolderDao())

        for (colName in collectionsToPull) {
            try {
                val fourteenDaysAgo = System.currentTimeMillis() - (14L * 24 * 60 * 60 * 1000)
                val snapshot = try {
                    firestore.collection(colName)
                        .whereGreaterThanOrEqualTo("createdAt", fourteenDaysAgo)
                        .get(Source.SERVER).await()
                } catch (e: Exception) {
                    firestore.collection(colName)
                        .whereGreaterThanOrEqualTo("createdAt", fourteenDaysAgo)
                        .get().await()
                }

                if (snapshot.isEmpty) continue

                Log.d(syncTag, "Pulling from collection '$colName': fetched ${snapshot.size()} documents. Processing in single batch...")

                val pendingDeletions = db.pendingDeletionDao().getAllPendingDeletions()
                val deletedEntityIds = pendingDeletions.map { it.entityId }.toSet()

                val entitiesToInsert = mutableListOf<PlanEntity>()

                for (doc in snapshot.documents) {
                    try {
                        val docStudentId = doc.getSafeString("studentId") ?: doc.getSafeString("uid")
                        if (docStudentId != null && docStudentId != uid) continue

                        val isDeleted = doc.getSafeBoolean("isDeleted") ?: false
                        val remoteId = doc.getSafeLong("id")
                            ?: doc.id.toLongOrNull()
                            ?: abs(doc.id.hashCode().toLong())

                        val folderId = doc.getSafeString("folderId") ?: ""
                        val isDeletedLocally = deletedEntityIds.contains(doc.id) ||
                                deletedEntityIds.contains(remoteId.toString()) ||
                                (folderId.isNotBlank() && deletedEntityIds.contains(folderId))

                        if (isDeletedLocally) {
                            Log.d(syncTag, "Skipping remote document [$colName]: docId=${doc.id} (local tombstone exists)")
                            continue
                        }

                        if (isDeleted) {
                            targetDao.purgeTarget(remoteId)
                            try {
                                db.planDao().deleteTasksByFolder(doc.id)
                            } catch (_: Exception) {}
                            Log.d(syncTag, "Purged deleted tombstone [$colName]: ID=$remoteId / docId=${doc.id}")
                            continue
                        }

                        val title = doc.getSafeString("title") ?: doc.getSafeString("planTitle") ?: "Koç Hedefi"

                        if (colName == "plans") {
                            val resolvedFolderId = defaultFolderId ?: continue
                            val rawPlan = PlanEntity(
                                id = doc.id,
                                folderId = doc.getSafeString("folderId") ?: "",
                                title = title,
                                subject = doc.getSafeString("subject") ?: doc.getSafeString("category") ?: "Ders",
                                dayOfWeek = doc.getSafeInt("dayOfWeek") ?: 0,
                                scheduledTime = doc.getSafeString("scheduledTime") ?: "",
                                targetQuestions = (doc.getSafeInt("targetQuestions") ?: doc.getSafeInt("targetValue")?.toInt() ?: 0).let { q -> if (q > 0) q else 30 },
                                isCompleted = doc.getSafeBoolean("isCompleted") ?: doc.getSafeBoolean("completed") ?: false,
                                orderIndex = doc.getSafeInt("orderIndex") ?: 0
                            )
                            val parsedPlan = parseLegacyPlanTitle(rawPlan, resolvedFolderId)
                            entitiesToInsert.add(parsedPlan)
                        }

                        val rawPeriod = doc.getSafeString("planType") ?: doc.getSafeString("period") ?: "SHORT_TERM"
                        val normalizedPeriod = if (colName == "targets") "LONG_TERM" else if (rawPeriod.contains("LONG", ignoreCase = true) || rawPeriod.contains("UZUN", ignoreCase = true)) "LONG_TERM" else "SHORT_TERM"

                        val (targetType, targetPeriod) = if (normalizedPeriod == "LONG_TERM") {
                            Pair(TargetType.LONG_TERM, TargetPeriod.MONTHLY)
                        } else {
                            Pair(TargetType.SHORT_TERM, TargetPeriod.WEEKLY)
                        }

                        val priority = doc.getSafeString("priority") ?: "NORMAL"
                        val dateStr = doc.getSafeString("date") ?: doc.getSafeString("planDate") ?: doc.getSafeString("deadline") ?: ""

                        val deadlineMillis = doc.getSafeLong("deadlineMillis") ?: try {
                            if (dateStr.isNotBlank()) {
                                SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(dateStr)?.time
                                    ?: (System.currentTimeMillis() + 86400000L)
                            } else {
                                System.currentTimeMillis() + 86400000L
                            }
                        } catch (e: Exception) {
                            System.currentTimeMillis() + 86400000L
                        }

                        val targetEntity = TargetEntity(
                            id = remoteId,
                            title = title,
                            description = doc.getSafeString("coachNote") ?: doc.getSafeString("description") ?: "",
                            deadlineMillis = deadlineMillis,
                            type = targetType,
                            period = targetPeriod,
                            priority = priority,
                            category = doc.getSafeString("category") ?: "CUSTOM",
                            targetValue = doc.getSafeString("targetValue"),
                            isCompleted = doc.getSafeBoolean("isCompleted") ?: doc.getSafeBoolean("completed") ?: false,
                            isAssignedByCoach = doc.getSafeBoolean("isAssignedByCoach") ?: true,
                            coachNote = doc.getSafeString("coachNote") ?: doc.getSafeString("description"),
                            date = dateStr,
                            isSynced = true,
                            isDeleted = false,
                            lastModifiedMillis = System.currentTimeMillis()
                        )

                        val taskEntities = mutableListOf<TaskEntity>()
                        val rawTasks = doc.get("tasks") as? List<*> ?: emptyList<Any>()
                        rawTasks.forEachIndexed { idx, item ->
                            if (item is Map<*, *>) {
                                val taskTitle = item["title"]?.toString() ?: ""
                                if (taskTitle.isNotBlank()) {
                                    val isTaskComp = when (val compVal = item["isCompleted"]) {
                                        is Boolean -> compVal
                                        else -> compVal?.toString()?.toBooleanStrictOrNull() ?: false
                                    }
                                    taskEntities.add(
                                        TaskEntity(
                                            id = (remoteId * 100) + idx + 1,
                                            targetId = remoteId,
                                            title = taskTitle,
                                            isCompleted = isTaskComp,
                                            orderIndex = idx,
                                            isSynced = true
                                        )
                                    )
                                }
                            }
                        }

                        if (colName == "targets") {
                            targetDao.upsertTarget(targetEntity.copy(isSynced = true))
                            targetDao.deleteTasksByTargetId(remoteId)
                        } else {
                            targetDao.syncRemotePlan(targetEntity, taskEntities)
                        }
                    } catch (docErr: Exception) {
                        Log.e(syncTag, "Error parsing document ${doc.id} in '$colName': ${docErr.message}", docErr)
                    }
                }

                if (colName == "plans" && entitiesToInsert.isNotEmpty()) {
                    db.planDao().insertAll(entitiesToInsert)
                    Log.d(syncTag, "Batch insert completed for ${entitiesToInsert.size} plans.")
                }
            } catch (colErr: Exception) {
                Log.w(syncTag, "Could not query collection '$colName': ${colErr.message}")
            }
        }
    }

    // ── 6. Inbound Downstream Pull: Coach-Assigned Schedule ──────────────
    private suspend fun pullRemoteSchedule(
        db: com.example.sharkmentor.data.local.SharkMentorDatabase,
        firestore: FirebaseFirestore,
        syncTag: String,
        uid: String
    ) = withContext(Dispatchers.IO) {
        try {
            val studentDoc = firestore.collection("students").document(uid).get(Source.SERVER).await()
            if (!studentDoc.exists()) return@withContext

            val scheduleMap = studentDoc.get("customScheduleData") as? Map<*, *>
            val scheduleDao = db.scheduleDao()

            if (scheduleMap != null) {
                val entities = mutableListOf<com.example.sharkmentor.data.local.entity.InstitutionScheduleEntity>()
                val daysList = scheduleMap["days"] as? List<*> ?: emptyList<Any>()

                daysList.forEachIndexed { dayIdx, dayObj ->
                    val dayMap = dayObj as? Map<*, *> ?: return@forEachIndexed
                    val dayName = dayMap["day"] as? String ?: ""

                    val dayOfWeek = when (dayName.lowercase(java.util.Locale.getDefault())) {
                        "pazartesi" -> 1
                        "salı" -> 2
                        "çarşamba" -> 3
                        "perşembe" -> 4
                        "cuma" -> 5
                        "cumartesi" -> 6
                        "pazar" -> 7
                        else -> dayIdx + 1
                    }

                    val blocksList = dayMap["blocks"] as? List<*> ?: emptyList<Any>()
                    blocksList.forEachIndexed { blockIdx, blockObj ->
                        val blockMap = blockObj as? Map<*, *> ?: return@forEachIndexed
                        val timeStr = blockMap["time"] as? String ?: ""
                        val times = timeStr.split("-").map { it.trim() }
                        val startTime = times.getOrNull(0) ?: "08:00"
                        val endTime = times.getOrNull(1) ?: "09:00"

                        entities.add(
                            com.example.sharkmentor.data.local.entity.InstitutionScheduleEntity(
                                id = "CUSTOM_SCHED_${dayOfWeek}_${blockIdx}_${System.currentTimeMillis() % 10000}",
                                dayOfWeek = dayOfWeek,
                                startTime = startTime,
                                endTime = endTime,
                                subject = blockMap["subject"] as? String ?: "",
                                type = "DERSHANE",
                                teacherName = "Koç Programı",
                                period = blockIdx + 1
                            )
                        )
                    }
                }

                androidx.room.withTransaction(db) {
                    scheduleDao.deleteAll()
                    if (entities.isNotEmpty()) {
                        scheduleDao.insertAll(entities)
                    }
                }
                Log.d(syncTag, "Successfully synced custom schedule from Web to ScheduleDao: ${entities.size} slots")
            } else {
                scheduleDao.deleteAll()
                Log.d(syncTag, "No custom schedule assigned. Wiped local ScheduleDao.")
            }
        } catch (e: Exception) {
            Log.e(syncTag, "CRITICAL FAILURE in pullRemoteSchedule: ${e.message}", e)
        }
    }

    // ── Outbound Deletions & Sync Helpers ────────────────────────────────
    private suspend fun syncPendingDeletions(
        db: com.example.sharkmentor.data.local.SharkMentorDatabase,
        firestore: FirebaseFirestore,
        syncTag: String,
        defaultInstitution: String,
        uid: String
    ): Int {
        var count = 0
        val pendingDeletionDao = db.pendingDeletionDao()
        val pendingDeletions = pendingDeletionDao.getAllPendingDeletions()
        for (deletion in pendingDeletions) {
            try {
                val fullDocPath = if (deletion.collectionName.startsWith("hedeflerim/")) {
                    "${deletion.collectionName}/${deletion.entityId}"
                } else {
                    "${getCollectionPath(defaultInstitution, uid, deletion.collectionName)}/${deletion.entityId}"
                }
                firestore.document(fullDocPath).delete().await()
                pendingDeletionDao.deleteById(deletion.id)
                count++
            } catch (e: Exception) {
                Log.e(syncTag, "Failed delete ${deletion.entityId}", e)
            }
        }
        return count
    }

    private suspend fun syncLocalSessions(
        db: com.example.sharkmentor.data.local.SharkMentorDatabase,
        firestore: FirebaseFirestore,
        syncTag: String,
        defaultInstitution: String,
        studentName: String,
        uid: String
    ): Int {
        var count = 0
        val pendingSessions = db.syncDao().getUnsyncedSessions()
        for (session in pendingSessions) {
            try {
                val instId = session.institutionId?.takeIf { it.isNotBlank() } ?: defaultInstitution
                val sessionData = hashMapOf(
                    "id" to session.id.toString(),
                    "durationSeconds" to session.durationSeconds,
                    "durationMinutes" to session.durationSeconds / 60,
                    "durationFormatted" to "${session.durationSeconds / 60} dk ${session.durationSeconds % 60} sn",
                    "studentName" to studentName,
                    "institutionId" to instId,
                    "isIndividual" to (instId == "INDIVIDUAL"),
                    "timestamp" to session.completedAt,
                    "isDeleted" to false,
                    "syncedAt" to System.currentTimeMillis()
                )

                val parentStudentData = hashMapOf("id" to uid, "name" to studentName, "lastActiveAt" to FieldValue.serverTimestamp())
                if (instId != "INDIVIDUAL") parentStudentData["coachCode"] = instId
                firestore.collection("students").document(uid).set(parentStudentData, SetOptions.merge())

                firestore.collection("students").document(uid).collection("focus_sessions").document(session.id.toString()).set(sessionData, SetOptions.merge()).await()
                db.syncDao().markSessionAsSynced(session.id)
                count++
            } catch (e: Exception) {
                Log.e(syncTag, "Session err", e)
            }
        }
        return count
    }

    private suspend fun syncPendingDeletedTargets(
        db: com.example.sharkmentor.data.local.SharkMentorDatabase,
        firestore: FirebaseFirestore,
        syncTag: String
    ): Int {
        var count = 0
        for (deleted in db.targetDao().getPendingDeletedTargets()) {
            try {
                firestore.collection("plans").document(deleted.id.toString()).update("isDeleted", true, "updatedAt", FieldValue.serverTimestamp()).await()
                db.targetDao().purgeTarget(deleted.id)
                count++
            } catch (e: Exception) {
                Log.e(syncTag, "Target del err", e)
            }
        }
        return count
    }

    private suspend fun syncPendingDeletedResources(
        db: com.example.sharkmentor.data.local.SharkMentorDatabase,
        firestore: FirebaseFirestore,
        syncTag: String
    ): Int {
        var count = 0
        for (deleted in db.goalsDao().getPendingDeletedResources()) {
            try {
                firestore.collection("resources").document(deleted.id.toString()).update("isDeleted", true, "updatedAt", FieldValue.serverTimestamp()).await()
                db.goalsDao().deleteResourcePermanently(deleted.id)
                count++
            } catch (e: Exception) {
                Log.e(syncTag, "Resource del err", e)
            }
        }
        return count
    }

    private suspend fun syncLocalPlans(
        db: com.example.sharkmentor.data.local.SharkMentorDatabase,
        firestore: FirebaseFirestore,
        syncTag: String,
        defaultInstitution: String,
        studentName: String,
        uid: String
    ): Int {
        var count = 0
        for (planWithTasks in db.syncDao().getUnsyncedPlansWithTasks()) {
            try {
                val plan = planWithTasks.target
                val tasks = planWithTasks.tasks
                val instId = plan.institutionId?.takeIf { it.isNotBlank() } ?: defaultInstitution
                val tasksList = tasks.map { mapOf("id" to it.id, "title" to it.title, "isCompleted" to it.isCompleted, "orderIndex" to it.orderIndex) }
                val colName = if (plan.type == TargetType.LONG_TERM) "targets" else "plans"

                val planData = hashMapOf(
                    "id" to plan.id.toString(), "studentId" to uid, "title" to plan.title, "date" to plan.date,
                    "deadlineMillis" to plan.deadlineMillis, "isCompleted" to plan.isCompleted, "completed" to plan.isCompleted,
                    "totalTasks" to tasks.size, "completedTasks" to tasks.count { it.isCompleted }, "studentName" to studentName,
                    "institutionId" to instId, "isIndividual" to (instId == "INDIVIDUAL"), "isCoachTask" to plan.isAssignedByCoach,
                    "isAssignedByCoach" to plan.isAssignedByCoach, "tasks" to tasksList, "isDeleted" to plan.isDeleted,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                firestore.collection(colName).document(plan.id.toString()).set(planData, SetOptions.merge()).await()

                if (plan.isDeleted) db.targetDao().purgeTargetAndTasks(plan.id) else {
                    db.syncDao().markTargetAsSynced(plan.id)
                    db.syncDao().markTasksAsSyncedForTarget(plan.id)
                }
                count++
            } catch (e: Exception) {
                Log.e(syncTag, "Plan err", e)
            }
        }
        return count
    }

    private suspend fun syncLocalTopics(
        db: com.example.sharkmentor.data.local.SharkMentorDatabase,
        firestore: FirebaseFirestore,
        syncTag: String,
        uid: String
    ): Int {
        var count = 0
        val pendingTopics = db.syncDao().getUnsyncedTopics()
        for (topic in pendingTopics) {
            try {
                val rootTopicData = hashMapOf<String, Any?>("studentId" to uid, "subject" to topic.subject, "topicName" to topic.topic, "masteryLevel" to topic.currentLevel, "updatedAt" to FieldValue.serverTimestamp())
                firestore.collection("topics").document(topic.id.toString()).set(rootTopicData, SetOptions.merge()).await()
                count++
            } catch (e: Exception) {
                Log.e(syncTag, "Topic err", e)
            }
        }
        if (pendingTopics.isNotEmpty()) db.syncDao().markTopicsAsSynced(pendingTopics.map { it.id })
        return count
    }

    private suspend fun syncLocalResources(
        db: com.example.sharkmentor.data.local.SharkMentorDatabase,
        firestore: FirebaseFirestore,
        syncTag: String,
        uid: String
    ): Int {
        var count = 0
        val pendingResources = db.syncDao().getUnsyncedResources()
        for (resource in pendingResources) {
            try {
                val resourceData = hashMapOf<String, Any?>("id" to resource.id, "title" to resource.title, "subject" to resource.subject, "currentUnits" to resource.completedUnits, "completedUnits" to resource.completedUnits, "totalUnits" to resource.totalUnits, "isCompleted" to (resource.completedUnits >= resource.totalUnits), "colorHex" to (resource.colorHex ?: "#0ea5e9"), "studentId" to uid, "origin" to "APP", "isDeleted" to false, "isAssignedByCoach" to resource.isAssignedByCoach, "updatedAt" to FieldValue.serverTimestamp())
                firestore.document("resources/${resource.cloudId ?: resource.id.toString()}").set(resourceData, SetOptions.merge()).await()
                count++
            } catch (e: Exception) {
                Log.e(syncTag, "Res err", e)
            }
        }
        if (pendingResources.isNotEmpty()) db.syncDao().markResourcesAsSynced(pendingResources.map { it.id })
        return count
    }

    private suspend fun getFirstActiveFolderIdOrNull(
        planFolderDao: PlanFolderDao
    ): String? {
        return planFolderDao.getFoldersCreatedAfter(0).firstOrNull()?.id
    }

    private fun parseLegacyPlanTitle(
        plan: PlanEntity,
        defaultFolderId: String
    ): PlanEntity {
        val title = plan.title

        var day = plan.dayOfWeek
        var time = plan.scheduledTime

        if (day !in 1..7) {
            day = when {
                title.contains("Pazartesi", ignoreCase = true) -> 1
                title.contains("Salı", ignoreCase = true) -> 2
                title.contains("Çarşamba", ignoreCase = true) -> 3
                title.contains("Perşembe", ignoreCase = true) -> 4
                title.contains("Cumartesi", ignoreCase = true) -> 6
                title.contains("Cuma", ignoreCase = true) -> 5
                title.contains("Pazar", ignoreCase = true) -> 7
                else -> 1
            }
        }

        val timeMatcher = Regex("(\\d{2}:\\d{2})").find(title)
        if (timeMatcher != null && (time.isBlank() || time == "09:00")) {
            time = timeMatcher.groupValues[1]
        }

        val folderId = if (plan.folderId.isBlank() || plan.folderId == "default_folder") {
            defaultFolderId
        } else {
            plan.folderId
        }

        return plan.copy(
            folderId = folderId,
            dayOfWeek = day,
            scheduledTime = if (time.isNotBlank()) time else "09:00"
        )
    }

    private fun getCollectionPath(instId: String, uid: String, collection: String): String {
        return if (instId == "INDIVIDUAL") {
            "students/$uid/$collection"
        } else {
            "institutions/$instId/students/$uid/$collection"
        }
    }
}

// ── DocumentSnapshot Safety Extensions ──────────────────────────────────────────

private fun DocumentSnapshot.getSafeString(field: String): String? {
    val value = get(field) ?: return null
    return value.toString()
}

private fun DocumentSnapshot.getSafeLong(field: String): Long? {
    val value = get(field) ?: return null
    return (value as? Number)?.toLong()
        ?: value.toString().toDoubleOrNull()?.toLong()
        ?: value.toString().toLongOrNull()
}

private fun DocumentSnapshot.getSafeInt(field: String): Int? {
    val value = get(field) ?: return null
    return (value as? Number)?.toInt()
        ?: value.toString().toDoubleOrNull()?.toInt()
        ?: value.toString().toIntOrNull()
}

private fun DocumentSnapshot.getSafeBoolean(field: String): Boolean? {
    val value = get(field) ?: return null
    if (value is Boolean) return value
    return value.toString().toBooleanStrictOrNull()
}
