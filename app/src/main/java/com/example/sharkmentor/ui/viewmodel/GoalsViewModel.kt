package com.example.sharkmentor.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.sharkmentor.data.local.SharkMentorDatabase
import com.example.sharkmentor.data.local.entity.ResourceEntity
import com.example.sharkmentor.data.local.entity.TopicGoalEntity
import com.example.sharkmentor.data.local.entity.ConcreteTargetEntity
import com.example.sharkmentor.data.local.dao.ConcreteTargetDao
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import com.example.sharkmentor.core.util.GoogleServicesHelper
import com.example.sharkmentor.service.sync.SyncWorker
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

@Stable
data class SubjectFolderUiModel(
    val subjectName: String,
    val topics: List<TopicGoalEntity>,
    val averageLevel: Float,
    val completedCount: Int
)

@Immutable
data class GoalsUiState(
    val isLoading: Boolean = false,
    val concreteTarget: ConcreteTargetEntity? = null,
    val isAnalyzingTarget: Boolean = false,
    val groupedTopics: List<SubjectFolderUiModel> = emptyList(),
    val resources: List<ResourceEntity> = emptyList(),
    val isScanning: Boolean = false,
    val scanError: String? = null,
    val lastScannedUri: String? = null
)

class GoalsViewModel(application: Application) : AndroidViewModel(application) {

    private var dao: com.example.sharkmentor.data.local.dao.GoalsDao? = null
    private var targetDao: ConcreteTargetDao? = null
    private val aiService = com.example.sharkmentor.core.ai.GeminiCoreService(application)

    private val _topicGoals = MutableStateFlow<List<TopicGoalEntity>>(emptyList())
    private val _resources = MutableStateFlow<List<ResourceEntity>>(emptyList())
    private val _concreteTarget = MutableStateFlow<ConcreteTargetEntity?>(null)
    private val _isScanning = MutableStateFlow(false)
    private val _scanError = MutableStateFlow<String?>(null)
    private val _lastScannedUri = MutableStateFlow<String?>(null)
    private val _isAnalyzingTarget = MutableStateFlow(false)

    private val _expandedSubjectNames = MutableStateFlow<Set<String>>(emptySet())
    val expandedSubjectNames: StateFlow<Set<String>> = _expandedSubjectNames.asStateFlow()

    fun toggleSubjectFolder(subjectName: String) {
        _expandedSubjectNames.update { currentSet ->
            if (currentSet.contains(subjectName)) {
                currentSet - subjectName
            } else {
                currentSet + subjectName
            }
        }
    }

    fun collapseAllFolders() {
        _expandedSubjectNames.value = emptySet()
    }

    private val _isLoading = MutableStateFlow(true)

    val uiState: StateFlow<GoalsUiState> = combine(
        _concreteTarget,
        _isAnalyzingTarget,
        _topicGoals,
        _resources,
        _isScanning,
        _scanError,
        _lastScannedUri,
        _isLoading
    ) { params ->
        val target = params[0] as com.example.sharkmentor.data.local.entity.ConcreteTargetEntity?
        val analyzing = params[1] as Boolean
        @Suppress("UNCHECKED_CAST")
        val topics = params[2] as List<com.example.sharkmentor.data.local.entity.TopicGoalEntity>
        @Suppress("UNCHECKED_CAST")
        val resources = params[3] as List<com.example.sharkmentor.data.local.entity.ResourceEntity>
        val scanning = params[4] as Boolean
        val error = params[5] as String?
        val uri = params[6] as String?
        val loading = params[7] as Boolean

        val grouped = topics.groupBy { it.subject }
            .map { (subject, t) ->
                val avg = if (t.isNotEmpty()) t.map { it.currentLevel }.average().toFloat() else 1f
                val completed = t.count { it.currentLevel >= 4 }
                SubjectFolderUiModel(
                    subjectName = subject,
                    topics = t,
                    averageLevel = avg,
                    completedCount = completed
                )
            }
            .sortedBy { it.subjectName }

        GoalsUiState(
            isLoading = loading,
            concreteTarget = target,
            isAnalyzingTarget = analyzing,
            groupedTopics = grouped,
            resources = resources,
            isScanning = scanning,
            scanError = error,
            lastScannedUri = uri
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = GoalsUiState(isLoading = true)
    )

    fun clearScanError() {
        _scanError.value = null
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val database = SharkMentorDatabase.getDatabase(application)
            dao = database.goalsDao()
            targetDao = database.concreteTargetDao()
            
            launch {
                targetDao?.getTargetFlow()?.collect { _concreteTarget.value = it }
            }
            
            launch {
                dao?.getAllTopicGoalsFlow()?.collect { 
                    _topicGoals.value = it 
                    _isLoading.value = false 
                }
            }
            
            // Listen to resources from Firestore directly only when authenticated
            val currentUser = runCatching { FirebaseAuth.getInstance().currentUser }.getOrNull()
                ?: GoogleServicesHelper.getCurrentUser(context = getApplication())
            val uid = currentUser?.uid
            if (currentUser != null && uid != null) {
                runCatching {
                    GoogleServicesHelper.getFirebaseFirestore(getApplication())?.collection("resources")
                        ?.whereEqualTo("studentId", uid)
                        ?.addSnapshotListener { snapshots, error ->
                            if (error != null) {
                                Log.w("ResourceSync", "Kaynak dinleme hatası (offline): ${error.message}")
                                return@addSnapshotListener
                            }
                            if (snapshots != null) {
                                val list = snapshots.documents.mapNotNull { doc ->
                                    try {
                                        com.example.sharkmentor.data.local.entity.ResourceEntity(
                                            id = doc.id.hashCode().toLong(),
                                            title = doc.getString("title") ?: "",
                                            subject = doc.getString("subject") ?: "",
                                            totalUnits = doc.getLong("totalUnits")?.toInt() ?: doc.getLong("totalTarget")?.toInt() ?: 1,
                                            completedUnits = doc.getLong("completedUnits")?.toInt() ?: doc.getLong("currentUnits")?.toInt() ?: 0,
                                            unitType = doc.getString("unitType") ?: doc.getString("unit") ?: "Sayfa",
                                            colorHex = doc.getString("colorHex") ?: "#0ea5e9",
                                            cloudId = doc.id,
                                            isSynced = true,
                                            isAssignedByCoach = doc.getBoolean("isCoachAssigned") ?: (doc.getString("assignedBy") == "coach")
                                        )
                                    } catch (e: Exception) {
                                        null
                                    }
                                }
                                _resources.value = list
                                _isLoading.value = false
                            }
                        }
                }.onFailure { e ->
                    Log.w("ResourceSync", "Failed to attach resources listener: ${e.message}")
                }
            }
        }
    }

    // â”€â”€ Direct Firestore Sync Helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private fun syncTopicToFirestore(topicId: String, subject: String, topicName: String, masteryLevel: Int) {
        val uid = GoogleServicesHelper.getCurrentUser(context = getApplication())?.uid ?: return
        val db = GoogleServicesHelper.getFirebaseFirestore(getApplication()) ?: return

        val topicData = hashMapOf<String, Any?>(
            "studentId"   to uid,
            "subject"     to subject,
            "topicName"   to topicName,
            "masteryLevel" to masteryLevel,
            "updatedAt"   to FieldValue.serverTimestamp()
        )

        db.collection("topics").document(topicId)
            .set(topicData, SetOptions.merge())
            .addOnSuccessListener {
                Log.d("SharkSync", "Topic $topicId synced to root topics collection.")
            }
            .addOnFailureListener { e ->
                Log.e("SharkSync", "Failed to sync topic $topicId: ${e.message}", e)
            }
    }

    private fun syncResourceToFirestore(resource: ResourceEntity) {
        val uid = GoogleServicesHelper.getCurrentUser(context = getApplication())?.uid ?: return
        val db = GoogleServicesHelper.getFirebaseFirestore(getApplication()) ?: return
        val resourceId = resource.cloudId ?: resource.id.toString()
        val totalUnits = resource.totalUnits
        val clamped = resource.completedUnits.coerceIn(0, totalUnits)

        val resourceData = hashMapOf<String, Any?>(
            "title"             to resource.title,
            "subject"           to resource.subject,
            "currentUnits"      to clamped,
            "completedUnits"    to clamped,
            "isCompleted"       to (clamped >= totalUnits),
            "completed"         to (clamped >= totalUnits),
            "totalUnits"        to totalUnits,
            "totalTarget"       to totalUnits,
            "colorHex"          to (resource.colorHex ?: "#0ea5e9"),
            "studentId"         to uid,
            "origin"            to "APP",
            "isDeleted"         to false,
            "isAssignedByCoach" to resource.isAssignedByCoach,
            "updatedAt"         to FieldValue.serverTimestamp()
        )

        db.collection("resources").document(resourceId)
            .set(resourceData, SetOptions.merge())
            .addOnFailureListener { e -> Log.e("ResourceSync", "GÃ¼ncelleme hatasÄ±", e) }
    }

    fun updateTopicGoal(topicGoal: TopicGoalEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val updated = topicGoal.copy(
                isSynced = false,
                updatedAtMillis = System.currentTimeMillis()
            )
            dao?.updateTopicGoal(updated)
            syncTopicToFirestore(updated.id.toString(), updated.subject, updated.topic, updated.currentLevel)
            val context = getApplication<android.app.Application>()
            android.util.Log.d("SharkMentorSync", "Triggering sync for updateTopicGoal")
            com.example.sharkmentor.service.sync.SyncWorker.enqueueImmediateSync(context)
        }
    }

    fun insertTopicGoal(subject: String, topic: String, targetLevel: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<android.app.Application>()
            val institutionId = com.example.sharkmentor.data.local.preferences.UserPreferences.getInstance(context).institutionId ?: "INDIVIDUAL"
            val entity = TopicGoalEntity(
                subject = subject,
                topic = topic,
                targetLevel = targetLevel,
                currentLevel = 1,
                isSynced = false,
                institutionId = institutionId
            )
            val insertedId = dao?.insertTopicGoal(entity) ?: 0L
            if (insertedId > 0L) {
                syncTopicToFirestore(insertedId.toString(), subject, topic, 1)
            }
            Log.d("SharkMentorSync", "Triggering sync for insertTopicGoal")
            com.example.sharkmentor.service.sync.SyncWorker.enqueueImmediateSync(context)
        }
    }

    fun deleteTopic(topic: TopicGoalEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<android.app.Application>()
            dao?.softDeleteTopicGoal(topic.id)
            val database = SharkMentorDatabase.getDatabase(context)
            database.pendingDeletionDao().insert(
                com.example.sharkmentor.data.local.entity.PendingDeletionEntity(
                    entityId = topic.id.toString(),
                    collectionName = "hedeflerim/konularim/dersler/${topic.subject}/topics"
                )
            )
            android.util.Log.d("SharkMentorSync", "Triggering sync for deleteTopic id=${topic.id} subject=${topic.subject}")
            com.example.sharkmentor.service.sync.SyncWorker.enqueueImmediateSync(context)
        }
    }

    fun deleteSubjectFolder(subjectName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<android.app.Application>()
            val database = SharkMentorDatabase.getDatabase(context)
            val allTopics = dao?.getAllTopicsRaw()?.filter { it.subject == subjectName }
            dao?.softDeleteBySubject(subjectName)
            
            allTopics?.forEach { topic ->
                database.pendingDeletionDao().insert(
                    com.example.sharkmentor.data.local.entity.PendingDeletionEntity(
                        entityId = topic.id.toString(),
                        collectionName = "hedeflerim/konularim/dersler/${topic.subject}/topics"
                    )
                )
            }
            android.util.Log.d("SharkMentorSync", "Triggering sync for deleteSubjectFolder: $subjectName (${allTopics?.size ?: 0} topics)")
            com.example.sharkmentor.service.sync.SyncWorker.enqueueImmediateSync(context)
        }
    }

    fun updateResource(resource: ResourceEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val updated = resource.copy(
                isSynced = false,
                lastModifiedMillis = System.currentTimeMillis()
            )
            dao?.updateResource(updated)
            syncResourceToFirestore(updated)
            val context = getApplication<Application>()
            android.util.Log.d("SharkMentorSync", "Triggering sync for updateResource")
            com.example.sharkmentor.service.sync.SyncWorker.enqueueImmediateSync(context)
        }
    }

    fun incrementResource(resourceId: Long, step: Int = 1) {
        viewModelScope.launch(Dispatchers.IO) {
            val resource = _resources.value.find { it.id == resourceId } ?: return@launch
            val targetVal = (resource.completedUnits + step).coerceIn(0, resource.totalUnits)
            val cloudId = resource.cloudId ?: resource.id.toString()

            val firestore = com.example.sharkmentor.core.util.GoogleServicesHelper.getFirebaseFirestore(getApplication())
            firestore?.collection("resources")?.document(cloudId)
                ?.update(
                    mapOf(
                        "currentUnits" to targetVal,
                        "completedUnits" to targetVal,
                        "isCompleted" to (targetVal >= resource.totalUnits),
                        "completed" to (targetVal >= resource.totalUnits)
                    )
                )
            dao?.incrementProgress(resourceId, step)
        }
    }

    fun decrementResource(resourceId: Long, step: Int = 1) {
        viewModelScope.launch(Dispatchers.IO) {
            val resource = _resources.value.find { it.id == resourceId } ?: return@launch
            val targetVal = (resource.completedUnits - step).coerceIn(0, resource.totalUnits)
            val cloudId = resource.cloudId ?: resource.id.toString()

            val firestore = com.example.sharkmentor.core.util.GoogleServicesHelper.getFirebaseFirestore(getApplication())
            firestore?.collection("resources")?.document(cloudId)
                ?.update(
                    mapOf(
                        "currentUnits" to targetVal,
                        "completedUnits" to targetVal,
                        "isCompleted" to (targetVal >= resource.totalUnits),
                        "completed" to (targetVal >= resource.totalUnits)
                    )
                )
            dao?.decrementProgress(resourceId, step)
        }
    }

    fun setResourceUnits(resource: ResourceEntity, newUnits: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val targetVal = newUnits.coerceIn(0, resource.totalUnits)
            val cloudId = resource.cloudId ?: resource.id.toString()

            val firestore = com.example.sharkmentor.core.util.GoogleServicesHelper.getFirebaseFirestore(getApplication())
            firestore?.collection("resources")?.document(cloudId)
                ?.update(
                    mapOf(
                        "currentUnits" to targetVal,
                        "completedUnits" to targetVal,
                        "isCompleted" to (targetVal >= resource.totalUnits),
                        "completed" to (targetVal >= resource.totalUnits)
                    )
                )
            dao?.setProgress(resource.id, targetVal)
        }
    }

    fun deleteResource(resource: ResourceEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val cloudId = resource.cloudId ?: resource.id.toString()
            if (cloudId.isNotBlank()) {
                val firestore = com.example.sharkmentor.core.util.GoogleServicesHelper.getFirebaseFirestore(getApplication())
                firestore?.collection("resources")?.document(cloudId)
                    ?.delete()
                    ?.addOnSuccessListener {
                        Log.d("ResourceSync", "Kaynak Firestore'dan kalÄ±cÄ± olarak silindi: $cloudId")
                    }
                    ?.addOnFailureListener { e ->
                        Log.e("ResourceSync", "Silme hatasÄ±", e)
                    }
            }
            dao?.softDeleteResource(resource.id)
        }
    }

    fun insertResource(title: String, subject: String, totalUnits: Int, unitType: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<android.app.Application>()
            val institutionId = com.example.sharkmentor.data.local.preferences.UserPreferences.getInstance(context).institutionId ?: "INDIVIDUAL"
            val entity = ResourceEntity(
                title = title,
                subject = subject,
                totalUnits = totalUnits,
                unitType = unitType,
                institutionId = institutionId,
                isSynced = false
            )
            val insertedId = dao?.insertResource(entity) ?: 0L
            val entityWithId = if (insertedId > 0L) entity.copy(id = insertedId) else entity
            if (insertedId > 0L) {
                syncResourceToFirestore(entityWithId)
            }
            Log.d("SharkMentorSync", "Triggering sync for insertResource")
            SyncWorker.enqueueImmediateSync(context)
        }
    }

    fun extractTopicsFromDocument(context: android.content.Context, documentUri: String) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _isScanning.value = true
            _scanError.value = null
            _lastScannedUri.value = documentUri
            
            val result = kotlinx.coroutines.withContext(Dispatchers.IO) {
                aiService.extractTopicsFromDocument(documentUri)
            }

            result.onSuccess { topicPairs ->
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    val institutionId = com.example.sharkmentor.data.local.preferences.UserPreferences.getInstance(context).institutionId ?: "INDIVIDUAL"
                    val entities = topicPairs.map { (subject, topic) ->
                        TopicGoalEntity(
                            subject = subject,
                            topic = topic,
                            currentLevel = 1,
                            targetLevel = 5,
                            isSynced = false,
                            institutionId = institutionId
                        )
                    }
                    val insertedIds = dao?.insertTopicGoals(entities) ?: emptyList()
                    entities.forEachIndexed { index, topicEntity ->
                        val topicId = insertedIds.getOrNull(index)?.takeIf { it > 0L }?.toString() ?: topicEntity.id.toString()
                        syncTopicToFirestore(topicId, topicEntity.subject, topicEntity.topic, topicEntity.currentLevel)
                    }
                    Log.d("SharkMentorSync", "Triggering sync for extractTopicsFromDocument")
                    SyncWorker.enqueueImmediateSync(context)
                }

                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        context,
                        "${topicPairs.size} adet konu baÅŸarÄ±yla eklendi!",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }.onFailure { error ->
                _scanError.value = error.localizedMessage ?: "Bilinmeyen hata"
            }
            _isScanning.value = false
        }
    }

    fun saveTarget(
        examType: String, 
        targetTitle: String,
        targetRanking: Int? = null,
        targetUniversity: String? = null,
        targetDepartment: String? = null
    ) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _isAnalyzingTarget.value = true
            
            val aiResult = kotlinx.coroutines.withContext(Dispatchers.IO) {
                aiService.analyzeConcreteTarget(examType, targetTitle, "")
            }
            
            val analysisSummary = aiResult.getOrNull() ?: "AI analizi şu an kullanılamıyor. Hedefiniz kaydedildi."
            
            val finalTargetScoreOrRank = if (targetRanking != null && targetRanking > 0) {
                "$targetRanking Sıralama"
            } else {
                "Hedef Belirlenmedi"
            }
            
            val entity = ConcreteTargetEntity(
                examType = examType,
                targetTitle = targetTitle,
                targetScoreOrRank = finalTargetScoreOrRank,
                aiAnalysisSummary = analysisSummary,
                targetRanking = targetRanking,
                targetUniversity = targetUniversity,
                targetDepartment = targetDepartment
            )
            
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                targetDao?.saveTarget(entity)
                
                val db = GoogleServicesHelper.getFirebaseFirestore(getApplication())
                val uid = GoogleServicesHelper.getCurrentUser(context = getApplication())?.uid
                if (uid != null && db != null) {
                    val targetData = hashMapOf<String, Any?>()
                    targetData["targetRanking"] = if (targetRanking != null && targetRanking > 0) targetRanking else null
                    targetData["targetUniversity"] = targetUniversity
                    targetData["targetDepartment"] = targetDepartment
                    
                    db.collection("users").document(uid).set(targetData, SetOptions.merge())
                    db.collection("students").document(uid).set(targetData, SetOptions.merge())
                }
            }
            
            _isAnalyzingTarget.value = false
        }
    }

    fun deleteTarget() {
        viewModelScope.launch(Dispatchers.IO) {
            targetDao?.clearTarget()
        }
    }

    fun reanalyzeConcreteTarget() {
        viewModelScope.launch(Dispatchers.IO) {
            val currentTarget = targetDao?.getTargetRaw() ?: return@launch
            _isAnalyzingTarget.value = true

            val result = aiService.analyzeConcreteTarget(
                examType = currentTarget.examType,
                targetName = currentTarget.targetTitle,
                targetDetail = currentTarget.targetScoreOrRank
            )

            result.onSuccess { summary ->
                targetDao?.saveTarget(
                    currentTarget.copy(
                        aiAnalysisSummary = summary,
                        updatedAtMillis = System.currentTimeMillis()
                    )
                )
            }.onFailure {
                // Keep existing target data, clear analysis error flag
            }

            _isAnalyzingTarget.value = false
        }
    }
}

