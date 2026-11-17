package com.example.sharkmentor.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.sharkmentor.data.local.dao.StudyPlanDao
import com.example.sharkmentor.data.local.entity.ActivePlanEntity
import com.example.sharkmentor.data.local.entity.StudyTaskEntity
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import com.example.sharkmentor.core.util.GoogleServicesHelper
import com.example.sharkmentor.data.local.SharkMentorDatabase

class FirebaseToRoomSyncManager(
    private val context: Context
) {
    private val firestore = GoogleServicesHelper.getFirebaseFirestore()!!
    private val auth = GoogleServicesHelper.getFirebaseAuth(context)!!
    private val studyPlanDao: StudyPlanDao = SharkMentorDatabase.getDatabase(context).studyPlanDao()
    
    private val prefs: SharedPreferences =
        context.getSharedPreferences("sync_version_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "PlanSyncManager"
        private const val KEY_LAST_SYNCED_TIMESTAMP = "last_synced_plan_timestamp"
        private val DAY_ORDER = listOf("Pazartesi", "Salı", "Çarşamba", "Perşembe", "Cuma", "Cumartesi", "Pazar")
    }

    suspend fun syncRemotePlanToRoom(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val uid = auth.currentUser?.uid ?: return@withContext false

        try {
            // Fetch the single student root document from the server
            val snapshot = firestore.collection("students").document(uid)
                .get(Source.SERVER)
                .await()

            if (!snapshot.exists()) {
                Log.d(TAG, "Student document does not exist.")
                return@withContext false
            }

            // Read the server-side update timestamp
            val remoteTimestamp = snapshot.getTimestamp("activeStudyPlanUpdatedAt")?.toDate()?.time ?: 0L
            val localTimestamp = prefs.getLong(KEY_LAST_SYNCED_TIMESTAMP, 0L)

            // Prevent redundant processing if local copy is already up-to-date
            if (!force && remoteTimestamp != 0L && remoteTimestamp <= localTimestamp) {
                Log.d(TAG, "Local Room database is already up-to-date with remote timestamp.")
                return@withContext true
            }

            val rawPlan = snapshot.get("activeStudyPlan") as? Map<*, *>
            if (rawPlan == null) {
                Log.d(TAG, "No activeStudyPlan map in document. Clearing local plan.")
                studyPlanDao.clearPlan()
                studyPlanDao.clearTasks()
                prefs.edit().putLong(KEY_LAST_SYNCED_TIMESTAMP, remoteTimestamp).apply()
                return@withContext true
            }

            // Safely extract plan headers
            val title = rawPlan["title"] as? String ?: "Haftalık Çalışma Programı"
            val strategyNote = rawPlan["strategyNote"] as? String ?: ""
            val totalQuestions = (rawPlan["totalTargetQuestions"] as? Number)?.toInt() ?: 0

            val planEntity = ActivePlanEntity(
                id = "CURRENT_ACTIVE_PLAN",
                title = title,
                strategyNote = strategyNote,
                totalTargetQuestions = totalQuestions,
                updatedAtTimestamp = remoteTimestamp
            )

            // Deconstruct nested 7-day tasks into flat relational Room entities
            val rawDays = rawPlan["days"] as? List<*> ?: emptyList<Any>()
            val taskEntities = mutableListOf<StudyTaskEntity>()

            rawDays.forEachIndexed { defaultDayIndex, dayObj ->
                val dayMap = dayObj as? Map<*, *> ?: return@forEachIndexed
                val dayName = dayMap["day"] as? String ?: "Gün ${defaultDayIndex + 1}"
                val calculatedDayIndex = DAY_ORDER.indexOf(dayName).let { if (it == -1) defaultDayIndex else it }
                val rawTasks = dayMap["tasks"] as? List<*> ?: emptyList<Any>()

                rawTasks.forEach { taskObj ->
                    val taskMap = taskObj as? Map<*, *> ?: return@forEach
                    taskEntities.add(
                        StudyTaskEntity(
                            planId = "CURRENT_ACTIVE_PLAN",
                            dayName = dayName,
                            dayIndex = calculatedDayIndex,
                            time = taskMap["time"] as? String ?: "",
                            subject = taskMap["subject"] as? String ?: "Ders",
                            topic = taskMap["topic"] as? String ?: "",
                            goal = taskMap["goal"] as? String ?: "",
                            isCompleted = taskMap["isCompleted"] as? Boolean ?: false
                        )
                    )
                }
            }

            // Ingest into Room in a single SQLite transaction
            studyPlanDao.replaceActivePlanWithTasks(planEntity, taskEntities)
            prefs.edit().putLong(KEY_LAST_SYNCED_TIMESTAMP, remoteTimestamp).apply()

            Log.d(TAG, "Successfully ingested active study plan and ${taskEntities.size} tasks into Room DB.")
            return@withContext true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to synchronize remote plan to Room", e)
            return@withContext false
        }
    }
}
