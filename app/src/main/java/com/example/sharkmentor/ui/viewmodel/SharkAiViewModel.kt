package com.example.sharkmentor.ui.viewmodel

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.sharkmentor.core.util.ImageStorageManager
import com.example.sharkmentor.core.util.PdfBitmapConverter
import com.example.sharkmentor.core.util.SessionManager
import com.example.sharkmentor.data.local.SharkMentorDatabase
import com.example.sharkmentor.data.local.entity.ActivePlanEntity
import com.example.sharkmentor.data.local.entity.ErrorQuestionEntity
import com.example.sharkmentor.data.local.entity.PlanEntity
import com.example.sharkmentor.data.local.entity.PlanFolderEntity
import com.example.sharkmentor.data.local.entity.ResourceEntity
import com.example.sharkmentor.data.local.entity.StudyTaskEntity
import com.example.sharkmentor.data.local.entity.TargetEntity
import com.example.sharkmentor.data.local.entity.TargetPeriod
import com.example.sharkmentor.data.local.entity.TargetType
import com.example.sharkmentor.data.local.entity.TopicGoalEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.example.sharkmentor.service.sync.SyncWorker
import com.example.sharkmentor.worker.TopicTaggingWorker
import com.example.sharkmentor.core.util.GoogleServicesHelper
import com.google.firebase.firestore.SetOptions
import com.example.sharkmentor.data.repository.AgentMemoryRepository
import com.example.sharkmentor.data.repository.AiModelPower
import com.example.sharkmentor.data.repository.AiRouterRepository
import com.example.sharkmentor.data.repository.AutoIntentClassifier
import com.example.sharkmentor.data.repository.NetPredictionRepository
import com.example.sharkmentor.data.repository.NetPredictionRepositoryImpl
import com.example.sharkmentor.data.repository.PlanRepository
import com.example.sharkmentor.data.repository.PlanRepositoryImpl
import com.example.sharkmentor.data.repository.SharkAiCapability
import com.example.sharkmentor.data.repository.SharkAiEngine
import com.example.sharkmentor.data.sync.InstitutionScheduleSyncListener
import com.example.sharkmentor.domain.model.prediction.NetWizardUiState
import com.example.sharkmentor.core.ai.AiProviderChoice
import com.example.sharkmentor.core.ai.GeminiCoreService
import com.example.sharkmentor.core.ai.UserAiPreferences
import com.example.sharkmentor.core.ai.YksCurriculumCatalog
import com.example.sharkmentor.data.repository.AiRouterResult
import com.example.sharkmentor.ui.aichat.AiChatHubUiState
import com.example.sharkmentor.ui.aichat.AiChatMessage
import com.example.sharkmentor.ui.aichat.AiPersona
import com.example.sharkmentor.ui.aichat.PersistedChatSession
import com.example.sharkmentor.ui.aichat.PersistedChatSessionSnapshot
import com.example.sharkmentor.ui.aichat.SharkAiChatSessionStore
import com.example.sharkmentor.ui.aichat.isPersonaAvailable
import com.example.sharkmentor.ui.screens.SelectedAttachmentPreview
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class SharkAiHandoffRequest(
    val imageUri: Uri?,
    val promptText: String,
    val targetEngine: SharkAiEngine
)

/**
 * Coordinates seamless cross-screen handoff from ErrorBankScreen ("Soru Çözücü" / "İkiz Soru")
 * into SharkAiScreen so the standalone AI engine triggers automatically.
 */
object SharkAiHandoffManager {
    private val _pendingHandoff = MutableStateFlow<SharkAiHandoffRequest?>(null)
    val pendingHandoff: StateFlow<SharkAiHandoffRequest?> = _pendingHandoff.asStateFlow()

    fun enqueueFromErrorQuestion(
        imagePath: String?,
        subject: String,
        topic: String?,
        targetEngine: SharkAiEngine
    ) {
        val resolvedUri = imagePath?.takeIf { it.isNotBlank() }?.let { raw ->
            if (raw.startsWith("content://") || raw.startsWith("file://")) {
                Uri.parse(raw)
            } else {
                val file = File(raw)
                if (file.exists()) Uri.fromFile(file) else Uri.parse(raw)
            }
        }
        val topicLabel = topic?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
        val prompt = when (targetEngine) {
            SharkAiEngine.SOLVER_ENGINE ->
                "$subject$topicLabel sorusunu adım adım çöz."
            SharkAiEngine.TWIN_QUESTION_ENGINE ->
                "$subject$topicLabel sorusuna paralel ÖSYM tarzı özgün bir ikiz soru (A-E şıklı) üret ve çözümünü paylaş."
            else ->
                "$subject$topicLabel sorusunu incele."
        }
        _pendingHandoff.value = SharkAiHandoffRequest(
            imageUri = resolvedUri,
            promptText = prompt,
            targetEngine = targetEngine
        )
    }

    fun consumePending(): SharkAiHandoffRequest? {
        val current = _pendingHandoff.value
        _pendingHandoff.value = null
        return current
    }
}

open class SharkAiViewModel(application: Application) : AndroidViewModel(application) {

    private val gson = Gson()
    private val userAiPrefs = UserAiPreferences.initialize(application)
    private val database = SharkMentorDatabase.getDatabase(application)
    private val aiRouterRepository = AiRouterRepository(application.applicationContext, database)
    private val geminiCoreService = GeminiCoreService(application.applicationContext)
    private val repository = PlanRepository(
        database.targetDao(),
        database.sessionDao(),
        database.planFolderDao()
    )
    private val planRepositoryImpl = PlanRepositoryImpl(
        database.planDao(),
        database.pendingDeletionDao()
    )
    private val memoryRepository = AgentMemoryRepository(database.agentMemoryDao())
    private val netPredictionRepository: NetPredictionRepository = NetPredictionRepositoryImpl(
        netProjectionDao = database.netProjectionDao(),
        examNetDao = database.monthlyExamNetDao(),
        goalsDao = database.goalsDao(),
        concreteTargetDao = database.concreteTargetDao()
    )
    private val institutionSyncListener = InstitutionScheduleSyncListener(
        database.institutionScheduleDao()
    )

    private val initialSessionSnapshot = SharkAiChatSessionStore.restoreSession(application)

    private val _chatSessions = MutableStateFlow(initialSessionSnapshot.sessions)
    val chatSessions: StateFlow<List<PersistedChatSession>> = _chatSessions.asStateFlow()

    private val _activeChatSessionId = MutableStateFlow(initialSessionSnapshot.activeSessionId)
    val activeChatSessionId: StateFlow<String> = _activeChatSessionId.asStateFlow()

    private val _inputText = MutableStateFlow(initialSessionSnapshot.draftInputText)
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _selectedAttachmentUri = MutableStateFlow<Uri?>(null)
    val selectedAttachmentUri: StateFlow<Uri?> = _selectedAttachmentUri.asStateFlow()

    private val _attachmentPreview = MutableStateFlow<SelectedAttachmentPreview?>(null)
    val attachmentPreview: StateFlow<SelectedAttachmentPreview?> = _attachmentPreview.asStateFlow()

    private val _modelPower = MutableStateFlow(initialSessionSnapshot.modelPower)
    val modelPower: StateFlow<AiModelPower> = _modelPower.asStateFlow()

    private val _activeModelDisplay = MutableStateFlow(userAiPrefs.getActiveDisplayText())
    val activeModelDisplay: StateFlow<String> = _activeModelDisplay.asStateFlow()

    private val _activeCapability = MutableStateFlow<SharkAiCapability?>(null)
    val activeCapability: StateFlow<SharkAiCapability?> = _activeCapability.asStateFlow()

    private val _detectedEngine = MutableStateFlow(SharkAiEngine.CONVERSATIONAL_PEER)
    val detectedEngine: StateFlow<SharkAiEngine> = _detectedEngine.asStateFlow()

    private val _lockedEngine = MutableStateFlow<SharkAiEngine?>(initialSessionSnapshot.lockedEngine)
    val lockedEngine: StateFlow<SharkAiEngine?> = _lockedEngine.asStateFlow()

    private val _isRefreshingNetProjection = MutableStateFlow(false)
    val isRefreshingNetProjection: StateFlow<Boolean> = _isRefreshingNetProjection.asStateFlow()

    val netWizardState: StateFlow<NetWizardUiState> = netPredictionRepository.getNetProjectionStream()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = NetWizardUiState(isLoading = true)
        )

    private val _uiState = MutableStateFlow(
        AiChatHubUiState(
            messages = initialSessionSnapshot.messages,
            currentPersona = initialSessionSnapshot.currentPersona
        )
    )
    val uiState: StateFlow<AiChatHubUiState> = _uiState.asStateFlow()

    private var activeAiJob: Job? = null
    @Volatile
    private var lastUserPromptText: String = ""

    init {
        observeStateDependencies()
        institutionSyncListener.start()
        refreshAutoDetectedEngine()
        consumePendingHandoffIfAny()
    }

    private fun applySessionSnapshot(snapshot: PersistedChatSessionSnapshot) {
        _chatSessions.value = snapshot.sessions
        _activeChatSessionId.value = snapshot.activeSessionId
        _inputText.value = snapshot.draftInputText
        _modelPower.value = snapshot.modelPower
        _lockedEngine.value = snapshot.lockedEngine
        _activeCapability.value = when (snapshot.lockedEngine) {
            SharkAiEngine.SOLVER_ENGINE -> SharkAiCapability.PROBLEM_SOLVER
            SharkAiEngine.TWIN_QUESTION_ENGINE -> SharkAiCapability.TWIN_QUESTION
            SharkAiEngine.PLANNER_OR_ORGANIZER -> SharkAiCapability.ADD_TO_STUDY_PLAN
            else -> null
        }
        _uiState.value = _uiState.value.copy(
            messages = snapshot.messages,
            currentPersona = snapshot.currentPersona,
            isLoading = false,
            error = null
        )
        refreshAutoDetectedEngine()
    }

    private fun persistCurrentChatState() {
        val current = _uiState.value
        val snapshot = SharkAiChatSessionStore.saveMessagesAndPersona(
            context = getApplication(),
            messages = current.messages,
            persona = current.currentPersona
        )
        _chatSessions.value = snapshot.sessions
        _activeChatSessionId.value = snapshot.activeSessionId
    }

    override fun onCleared() {
        super.onCleared()
        institutionSyncListener.stop()
    }

    private fun refreshAutoDetectedEngine() {
        _detectedEngine.value = AutoIntentClassifier.classify(
            text = _inputText.value,
            hasAttachment = _selectedAttachmentUri.value != null
        )
    }

    fun consumePendingHandoffIfAny() {
        val handoff = SharkAiHandoffManager.consumePending() ?: return
        _lockedEngine.value = handoff.targetEngine
        val snapshot = SharkAiChatSessionStore.saveDraftState(getApplication(), _inputText.value, _lockedEngine.value)
        _chatSessions.value = snapshot.sessions
        sendMessage(
            userInput = handoff.promptText,
            attachmentUri = handoff.imageUri,
            attachmentName = handoff.imageUri?.lastPathSegment ?: "Hata Kumbarası Sorusu",
            engineOverride = handoff.targetEngine
        )
    }

    fun onInputTextChanged(newText: String) {
        _inputText.value = newText
        val snapshot = SharkAiChatSessionStore.saveDraftState(getApplication(), newText, _lockedEngine.value)
        _chatSessions.value = snapshot.sessions
        refreshAutoDetectedEngine()
    }

    fun onAttachmentSelected(uri: Uri?) {
        _selectedAttachmentUri.value = uri
        refreshAutoDetectedEngine()
        if (uri == null) {
            _attachmentPreview.value = null
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val preview = buildAttachmentPreview(
                context = getApplication(),
                resolver = getApplication<Application>().contentResolver,
                uri = uri
            )
            _attachmentPreview.value = preview
        }
    }

    fun clearAttachment() {
        _selectedAttachmentUri.value = null
        _attachmentPreview.value = null
        _activeCapability.value = null
        _lockedEngine.value = null
        val snapshot = SharkAiChatSessionStore.saveDraftState(getApplication(), _inputText.value, null)
        _chatSessions.value = snapshot.sessions
        refreshAutoDetectedEngine()
    }

    fun setModelPower(power: AiModelPower) {
        _modelPower.value = power
        val snapshot = SharkAiChatSessionStore.saveModelPower(getApplication(), power)
        _chatSessions.value = snapshot.sessions
    }

    fun setActiveCapability(capability: SharkAiCapability?) {
        _activeCapability.value = capability
        _lockedEngine.value = SharkAiEngine.fromCapability(capability)
        val snapshot = SharkAiChatSessionStore.saveDraftState(getApplication(), _inputText.value, _lockedEngine.value)
        _chatSessions.value = snapshot.sessions
    }

    fun setLockedEngine(engine: SharkAiEngine?) {
        _lockedEngine.value = engine
        _activeCapability.value = when (engine) {
            SharkAiEngine.SOLVER_ENGINE -> SharkAiCapability.PROBLEM_SOLVER
            SharkAiEngine.TWIN_QUESTION_ENGINE -> SharkAiCapability.TWIN_QUESTION
            SharkAiEngine.PLANNER_OR_ORGANIZER -> SharkAiCapability.ADD_TO_STUDY_PLAN
            else -> null
        }
        val snapshot = SharkAiChatSessionStore.saveDraftState(getApplication(), _inputText.value, engine)
        _chatSessions.value = snapshot.sessions
    }

    /**
     * Starts a fresh chat session ("Yeni Sohbet"), clears displayed messages, resets draft/attachment/engine,
     * and resets an unused session in place instead of creating empty duplicates.
     */
    fun startNewChat() {
        activeAiJob?.cancel()
        activeAiJob = null
        _selectedAttachmentUri.value = null
        _attachmentPreview.value = null
        _activeCapability.value = null
        _lockedEngine.value = null
        _inputText.value = ""
        val snapshot = SharkAiChatSessionStore.createNewSession(getApplication(), keepModelPower = true)
        applySessionSnapshot(snapshot)
        _uiState.value = _uiState.value.copy(
            messages = emptyList(),
            currentPersona = AiPersona.SHARK_COACH,
            isLoading = false,
            error = null
        )
        refreshAutoDetectedEngine()
    }

    /**
     * Switches active chat session to [sessionId].
     */
    fun selectChatSession(sessionId: String) {
        if (sessionId == _activeChatSessionId.value && !_uiState.value.isLoading) return
        activeAiJob?.cancel()
        activeAiJob = null
        _selectedAttachmentUri.value = null
        _attachmentPreview.value = null
        val snapshot = SharkAiChatSessionStore.selectSession(getApplication(), sessionId)
        applySessionSnapshot(snapshot)
    }

    /**
     * Deletes a saved chat session by [sessionId]. If it was active, switches to the latest remaining
     * session or creates a fresh default session automatically.
     */
    fun deleteChatSession(sessionId: String) {
        if (sessionId == _activeChatSessionId.value) {
            activeAiJob?.cancel()
            activeAiJob = null
            _selectedAttachmentUri.value = null
            _attachmentPreview.value = null
        }
        val snapshot = SharkAiChatSessionStore.deleteSession(getApplication(), sessionId)
        applySessionSnapshot(snapshot)
    }

    /**
     * Triggers or locks the standalone `SolverEngine` WITHOUT injecting fake text prefixes into `_inputText`.
     */
    fun triggerProblemSolver(
        userInput: String = _inputText.value,
        attachmentUri: Uri? = _selectedAttachmentUri.value
    ) {
        setLockedEngine(SharkAiEngine.SOLVER_ENGINE)
        if (userInput.isNotBlank() || attachmentUri != null) {
            sendMessage(
                userInput = userInput,
                attachmentUri = attachmentUri,
                attachmentName = _attachmentPreview.value?.displayName,
                capabilityOverride = SharkAiCapability.PROBLEM_SOLVER,
                engineOverride = SharkAiEngine.SOLVER_ENGINE
            )
        }
    }

    /**
     * Triggers or locks the standalone `TwinQuestionEngine` WITHOUT injecting fake text prefixes into `_inputText`.
     */
    fun triggerTwinQuestionGenerator(
        userInput: String = _inputText.value,
        attachmentUri: Uri? = _selectedAttachmentUri.value
    ) {
        setLockedEngine(SharkAiEngine.TWIN_QUESTION_ENGINE)
        if (userInput.isNotBlank() || attachmentUri != null) {
            sendMessage(
                userInput = userInput,
                attachmentUri = attachmentUri,
                attachmentName = _attachmentPreview.value?.displayName,
                capabilityOverride = SharkAiCapability.TWIN_QUESTION,
                engineOverride = SharkAiEngine.TWIN_QUESTION_ENGINE
            )
        }
    }

    /**
     * Triggers or locks the standalone `AutonomousPlannerEngine` WITHOUT injecting fake text prefixes into `_inputText`.
     */
    fun triggerAddToStudyPlan(
        userInput: String = _inputText.value,
        attachmentUri: Uri? = _selectedAttachmentUri.value
    ) {
        setLockedEngine(SharkAiEngine.PLANNER_OR_ORGANIZER)
        if (userInput.isNotBlank() || attachmentUri != null) {
            sendMessage(
                userInput = userInput,
                attachmentUri = attachmentUri,
                attachmentName = _attachmentPreview.value?.displayName,
                capabilityOverride = SharkAiCapability.ADD_TO_STUDY_PLAN,
                engineOverride = SharkAiEngine.PLANNER_OR_ORGANIZER
            )
        }
    }

    fun refreshNetProjection() {
        viewModelScope.launch(Dispatchers.IO) {
            _isRefreshingNetProjection.value = true
            netPredictionRepository.refreshProjection()
            _isRefreshingNetProjection.value = false
        }
    }

    private fun observeStateDependencies() {
        viewModelScope.launch(Dispatchers.IO) {
            SessionManager.isLinkedToInstitution.collect { linked ->
                _uiState.value = _uiState.value.copy(isInstitutional = linked)
            }
        }
        viewModelScope.launch {
            merge(
                userAiPrefs.userGeminiKeys,
                userAiPrefs.selectedUserGeminiModel,
                userAiPrefs.userDeepSeekKey,
                userAiPrefs.selectedDeepSeekModel,
                userAiPrefs.userKimiKey,
                userAiPrefs.selectedKimiModel,
                userAiPrefs.userClaudeKey,
                userAiPrefs.selectedClaudeModel,
                userAiPrefs.userOpenAiKey,
                userAiPrefs.selectedOpenAiModel,
                userAiPrefs.selectedAiProvider
            ).collect {
                _activeModelDisplay.value = userAiPrefs.getActiveDisplayText()
            }
        }
        val prefs = getApplication<Application>().getSharedPreferences("shark_dopamine_prefs", Context.MODE_PRIVATE)
        val updateDopamineState = {
            val dopamineActive = prefs.getBoolean("is_active", false)
            _uiState.value = _uiState.value.copy(isDopamineActive = dopamineActive)
        }
        updateDopamineState()
        prefs.registerOnSharedPreferenceChangeListener { _, key ->
            if (key == "is_active") {
                updateDopamineState()
            }
        }
    }

    fun selectUnifiedMode(
        persona: AiPersona = AiPersona.SHARK_COACH,
        engine: SharkAiEngine? = null
    ): Pair<Boolean, String?> {
        val (available, reason) = switchPersona(persona)
        if (!available) {
            return false to reason
        }
        setLockedEngine(engine)
        return true to null
    }

    fun switchPersona(newPersona: AiPersona): Pair<Boolean, String?> {
        val prefs = getApplication<Application>().getSharedPreferences("shark_dopamine_prefs", Context.MODE_PRIVATE)
        val dopamineActive = prefs.getBoolean("is_active", false)
        _uiState.value = _uiState.value.copy(isDopamineActive = dopamineActive)

        val current = _uiState.value
        val (available, reason) = isPersonaAvailable(newPersona, current.isDopamineActive, current.isInstitutional)
        if (!available) {
            return false to reason
        }

        if (current.currentPersona == newPersona) return true to null

        val newMessages = current.messages + AiChatMessage(
            sender = "system",
            text = "${newPersona.title} moduna geçildi.",
            personaTag = newPersona.id
        )

        _uiState.value = current.copy(
            currentPersona = newPersona,
            messages = newMessages,
            error = null
        )
        persistCurrentChatState()
        return true to null
    }

    fun resetToDefaultPersona() {
        setLockedEngine(null)
        val current = _uiState.value
        if (current.currentPersona == AiPersona.SHARK_COACH) return

        val newMessages = current.messages + AiChatMessage(
            sender = "system",
            text = "Shark Sınav Koçu moduna dönüldü.",
            personaTag = AiPersona.SHARK_COACH.id
        )

        _uiState.value = current.copy(
            currentPersona = AiPersona.SHARK_COACH,
            messages = newMessages,
            error = null
        )
        persistCurrentChatState()
    }

    fun stopGeneration() {
        activeAiJob?.cancel()
        _uiState.value = _uiState.value.copy(isLoading = false)
        persistCurrentChatState()
    }

    fun deleteMessage(messageId: String) {
        val currentMessages = _uiState.value.messages
        _uiState.value = _uiState.value.copy(
            messages = currentMessages.filter { it.id != messageId }
        )
        persistCurrentChatState()
    }

    fun sendMessage(
        userInput: String = _inputText.value,
        attachmentUri: Uri? = _selectedAttachmentUri.value,
        attachmentName: String? = _attachmentPreview.value?.displayName,
        capabilityOverride: SharkAiCapability? = _activeCapability.value,
        engineOverride: SharkAiEngine? = _lockedEngine.value
    ) {
        val text = userInput.trim()
        if ((text.isBlank() && attachmentUri == null) || _uiState.value.isLoading) return

        lastUserPromptText = text
        val resolvedAttachmentName = attachmentName ?: attachmentUri?.let { resolveDisplayName(it) }
        val currentPersona = _uiState.value.currentPersona
        val activePower = _modelPower.value

        // Automatic Mode Detection (`AutoIntentClassifier`) unless manually locked/overridden
        val resolvedEngine: SharkAiEngine = engineOverride
            ?: SharkAiEngine.fromCapability(capabilityOverride)
            ?: if (currentPersona == AiPersona.STUDY_PLANNER) {
                SharkAiEngine.PLANNER_OR_ORGANIZER
            } else {
                AutoIntentClassifier.classify(text, hasAttachment = attachmentUri != null)
            }

        _detectedEngine.value = resolvedEngine

        val displayMessageText = when {
            text.isNotBlank() -> text
            resolvedAttachmentName != null -> resolvedAttachmentName
            else -> "Ekli Dosya"
        }

        val userMsg = AiChatMessage(
            sender = "user",
            text = displayMessageText,
            personaTag = currentPersona.id,
            attachmentUri = attachmentUri,
            attachmentName = resolvedAttachmentName
        )

        _inputText.value = ""
        _selectedAttachmentUri.value = null
        _attachmentPreview.value = null
        _activeCapability.value = null
        _lockedEngine.value = null
        SharkAiChatSessionStore.saveDraftState(getApplication(), "", null)
        refreshAutoDetectedEngine()

        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages + userMsg,
            isLoading = true,
            error = null
        )
        persistCurrentChatState()

        val streamingMsgId = UUID.randomUUID().toString()
        val originatingSessionId = _activeChatSessionId.value

        activeAiJob?.cancel()
        activeAiJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val memorySnapshot = memoryRepository.buildSemanticSnapshot()
                val userStatsAndLibrarySnapshot = withContext(Dispatchers.IO) {
                    val concrete = database.concreteTargetDao().getTargetRaw()
                    val targetInfo = if (concrete != null) {
                        "${concrete.targetTitle} (${concrete.targetScoreOrRank})"
                    } else "Girilmedi"
                    val shortTargets = database.targetDao().getTargetsWithTasksSync(TargetType.SHORT_TERM)
                    val longTargets = database.targetDao().getTargetsWithTasksSync(TargetType.LONG_TERM)
                    val allTargets = (shortTargets + longTargets).map { it.target }
                    val targetSummary = if (allTargets.isNotEmpty()) {
                        allTargets.take(8).joinToString(" | ") { t ->
                            "id=${t.id} \"${t.title}\" (${t.type.name}, ${t.solvedQuestions}/${t.targetQuestions} Soru, %${(t.progress * 100).toInt()})"
                        }
                    } else "Aktif hedef yok"

                    val allResources = database.goalsDao().getAllResourcesRaw()
                    val librarySummary = if (allResources.isNotEmpty()) {
                        allResources.take(12).joinToString(" | ") { r ->
                            "id=${r.id} \"${r.title}\" (Ders: ${r.subject}, İlerleme: ${r.completedUnits}/${r.totalUnits} ${r.unitType})"
                        }
                    } else "Kütüphanede kayıtlı kitap yok"

                    val allTopics = database.goalsDao().getAllTopicsRaw()
                    val topicSummary = if (allTopics.isNotEmpty()) {
                        val bySubject = allTopics.groupBy { it.subject }
                            .entries.take(8)
                            .joinToString(", ") { "${it.key}: ${it.value.size} konu" }
                        "Toplam ${allTopics.size} alt konu ($bySubject)"
                    } else "Henüz konu eklenmedi"

                    """
                    - Net/Sıralama Hedefi: $targetInfo
                    - [MEVCUT KÜTÜPHANE KAYNAKLARI (resources)]: $librarySummary
                    - [MEVCUT HEDEFLER (targets)]: $targetSummary
                    - [KONU HAKİMİYETİ ÖZETİ]: $topicSummary
                    """.trimIndent()
                }

                val projectionReport = if (currentPersona == AiPersona.GOAL_SETTER) {
                    val wizardState = netWizardState.value
                    val tyt = wizardState.tytProjection
                    val ayt = wizardState.aytProjection
                    """
                    [NET TAHMİN SİHİRBAZI RAPORU]
                    - TYT Mevcut: ${tyt?.currentAverageNet ?: 0f} | Hedef: ${tyt?.targetNet ?: 0f} | Projeksiyon: ${tyt?.projectedNet ?: 0f} (Açık: ${tyt?.netGap ?: 0f})
                    - AYT Mevcut: ${ayt?.currentAverageNet ?: 0f} | Hedef: ${ayt?.targetNet ?: 0f} | Projeksiyon: ${ayt?.projectedNet ?: 0f} (Açık: ${ayt?.netGap ?: 0f})
                    - Kritik Eksik Konular: ${tyt?.topPriorityTopics?.joinToString { "${it.subjectName}: ${it.topicName} (+${it.potentialNetBoost} Net)" } ?: "Yok"}
                    TALİMAT: Net tahminlerini kafadan uydurma. Bu rapordaki projeksiyon ve açık değerlerini referans alarak öğrenciye stratejik yol göster.
                    """.trimIndent()
                } else ""

                val schedules = withContext(Dispatchers.IO) { database.institutionScheduleDao().getAllSchedules() }
                val scheduleConstraintPrompt = if (schedules.isNotEmpty()) {
                    val formattedSlots = schedules.groupBy { it.dayOfWeek }.map { (day, classes) ->
                        val busyTimes = classes.joinToString(", ") { "${it.subject} (${it.startTime}-${it.endTime})" }
                        "$day: KİLİTLİ OKUL SAATLERİ: [$busyTimes]"
                    }.joinToString("\n")
                    "\n\nÖĞRENCİNİN SABİT OKUL/DERSHANE PROGRAMI:\n$formattedSlots\nUYARI: Bu saatlerde öğrenci okulda/derstedir. ASLA bu saatlere çalışma planı yazma. Ders bitiminden (örn. 13:00) 30 dk sonra (13:30) çalışma başlatabilirsin."
                } else ""

                val attachmentContext = if (attachmentUri != null) {
                    val mime = getApplication<Application>().contentResolver.getType(attachmentUri) ?: "application/octet-stream"
                    "\n[EKLİ DOSYA: ${resolvedAttachmentName ?: attachmentUri.lastPathSegment} (MIME: $mime)]"
                } else ""

                // ── ROLLING CONTEXT WINDOW (Sohbet Geçmişi: Son 6 Mesaj / 3 Tur) ──
                val historyCandidates = _uiState.value.messages
                    .filter { (it.sender == "user" || it.sender == "ai") && it.id != streamingMsgId }
                    .takeLast(6)

                val openAiHistory = historyCandidates.map { msg ->
                    val cleanContent = msg.text
                        .replace("#ACTION:.*".toRegex(), "")
                        .replace("```json[\\s\\S]*?```".toRegex(), "")
                        .trim()
                        .take(500)
                    com.example.sharkmentor.core.ai.OpenAiChatMessage(
                        role = if (msg.sender == "user") "user" else "assistant",
                        content = if (msg.attachmentUri != null && cleanContent.isBlank()) "[Ekli Belge/Görsel: ${msg.attachmentName ?: "Dosya"}]" else cleanContent
                    )
                }

                val conversationHistoryPrompt = if (historyCandidates.isNotEmpty()) {
                    val historyLines = historyCandidates.joinToString("\n") { msg ->
                        val tag = if (msg.sender == "user") "ÖĞRENCİ" else "SHARK AI"
                        val cleanContent = msg.text
                            .replace("#ACTION:.*".toRegex(), "")
                            .replace("```json[\\s\\S]*?```".toRegex(), "")
                            .trim()
                            .take(350)
                        val fileNote = if (msg.attachmentUri != null) " [Ek: ${msg.attachmentName ?: "Görsel"}]" else ""
                        "[$tag]$fileNote: $cleanContent"
                    }
                    "\n\n[SON SOHBET DİYALOĞU GEÇMİŞİ]:\n$historyLines\nTALİMAT: Öğrencinin 'bunu çöz', '2. soru', 'az önceki formül/plan' gibi atıflarını yukarıdaki son konuşulanları referans alarak cevapla."
                } else ""

                val compressedSystemPrompt = """
                    ${getSystemPromptForPersona(currentPersona, activePower)}

                    [SİSTEMİK KİMLİK: SharkMentor Otonom Hub - Motor: ${resolvedEngine.displayName} - Mod: ${currentPersona.title} - Güç: ${activePower.label}]
                    [ÖĞRENCİ, KÜTÜPHANE VE HEDEF BAĞLAMI]:
                    $userStatsAndLibrarySnapshot
                    [AKTİF HAFIZA: $memorySnapshot]$attachmentContext
                    $projectionReport$scheduleConstraintPrompt$conversationHistoryPrompt
                    Talimat: Öğrencinin mevcut kütüphane kitaplarına, hedeflerine, geçmiş kararlarına ve son diyalog geçmişine sadık kal. Mevcut bir kitabın ilerlemesi söylendiğinde yeni kitap açma, UPDATE_RESOURCE_PROGRESS kullan.
                """.trimIndent()

                val effectivePrompt = if (attachmentUri != null && text.isBlank()) {
                    when (resolvedEngine) {
                        SharkAiEngine.TWIN_QUESTION_ENGINE ->
                            "Eklenen dosyadaki ($resolvedAttachmentName) sorunun kazanımını ve çözüm algoritmasını birebir koruyarak ÖSYM tarzı özgün bir ikiz soru (A-E şıklı) üret ve çözümünü ekle."
                        SharkAiEngine.SOLVER_ENGINE ->
                            "Eklenen dosyadaki ($resolvedAttachmentName) soruyu hatasız oku ve adım adım çöz."
                        else ->
                            "Eklenen dosyayı ($resolvedAttachmentName) incele ve analiz et."
                    }
                } else {
                    text
                }

                val onStreamChunk: (String) -> Unit = streamCallback@{ partialText ->
                    if (_activeChatSessionId.value != originatingSessionId) {
                        return@streamCallback
                    }
                    // Strip in-progress or completed action tags and markdown json code blocks
                    var cleanPartial = partialText
                        .replace("#ACTION:(\\{.*?\\})#".toRegex(RegexOption.DOT_MATCHES_ALL), "")
                        .replace("#ACTION:.*".toRegex(RegexOption.DOT_MATCHES_ALL), "")
                        .replace("```json[\\s\\S]*?```".toRegex(), "")
                        .replace("```json[\\s\\S]*".toRegex(), "")
                        .replace("```[\\s\\S]*?```".toRegex(), "")
                        .trim()

                    val displayText = if (cleanPartial.isEmpty() && (partialText.contains("\"schedule\"") || partialText.contains("CREATE_WEEKLY_PLAN"))) {
                        "🗓️ Haftalık çalışma planın hazırlanıyor..."
                    } else if (cleanPartial.isEmpty() && partialText.contains("INSERT_TOPICS_BATCH")) {
                        "📚 Konu hakimiyeti listesi ekleniyor..."
                    } else if (cleanPartial.startsWith("{") || cleanPartial.startsWith("[")) {
                        "🗓️ Plan ve görevler hazırlanıyor..."
                    } else {
                        cleanPartial
                    }

                    if (displayText.isNotEmpty()) {
                        val currentList = _uiState.value.messages
                        val existingIdx = currentList.indexOfFirst { it.id == streamingMsgId }
                        val updatedMsg = AiChatMessage(
                            id = streamingMsgId,
                            sender = "ai",
                            text = displayText,
                            personaTag = currentPersona.id
                        )
                        val nextList = if (existingIdx >= 0) {
                            currentList.toMutableList().apply { set(existingIdx, updatedMsg) }
                        } else {
                            currentList + updatedMsg
                        }
                        _uiState.value = _uiState.value.copy(
                            messages = nextList,
                            isLoading = false
                        )
                    }
                }

                // Hybrid Multi-Provider Routing:
                // - Multimodal/PDF/Images always use Gemini Vision (gemini-3.6-flash).
                // - Text-only honors UserAiPreferences.selectedAiProvider (GEMINI -> GeminiCoreService 3.6 Flash, AUTO/DEEPSEEK/KIMI -> DeepSeekClient/AiRouterRepository).
                val routerResult = if (attachmentUri != null) {
                    val visionContext = when (resolvedEngine) {
                        SharkAiEngine.SOLVER_ENGINE ->
                            "${AiRouterRepository.SOLVER_ENGINE_SYSTEM_PROMPT}\n$compressedSystemPrompt"
                        SharkAiEngine.TWIN_QUESTION_ENGINE ->
                            "${AiRouterRepository.TWIN_QUESTION_SYSTEM_PROMPT}\n$compressedSystemPrompt"
                        SharkAiEngine.PLANNER_OR_ORGANIZER ->
                            "${AiRouterRepository.AUTONOMOUS_PLANNER_SYSTEM_PROMPT}\n$compressedSystemPrompt"
                        else ->
                            compressedSystemPrompt
                    }
                    aiRouterRepository.executeGeminiVision(
                        prompt = effectivePrompt,
                        systemInstruction = visionContext,
                        attachmentUri = attachmentUri,
                        activeEngine = resolvedEngine,
                        modelPower = activePower,
                        onPartialText = onStreamChunk
                    ).getOrThrow()
                } else {
                    val textInstruction = when (resolvedEngine) {
                        SharkAiEngine.SOLVER_ENGINE ->
                            "${AiRouterRepository.SOLVER_ENGINE_SYSTEM_PROMPT}\n$compressedSystemPrompt"
                        SharkAiEngine.TWIN_QUESTION_ENGINE ->
                            "${AiRouterRepository.TWIN_QUESTION_SYSTEM_PROMPT}\n$compressedSystemPrompt"
                        SharkAiEngine.PLANNER_OR_ORGANIZER ->
                            "${AiRouterRepository.AUTONOMOUS_PLANNER_SYSTEM_PROMPT}\n$compressedSystemPrompt"
                        SharkAiEngine.CONVERSATIONAL_PEER ->
                            compressedSystemPrompt
                    }
                    aiRouterRepository.executeTextStream(
                        activeEngine = resolvedEngine,
                        prompt = effectivePrompt,
                        systemInstruction = textInstruction,
                        chatHistory = openAiHistory,
                        modelPower = activePower,
                        onPartialText = onStreamChunk
                    ).getOrThrow()
                }

                if (_activeChatSessionId.value != originatingSessionId) {
                    return@launch
                }

                handleAiResponse(
                    rawResponse = routerResult.text,
                    toolPlanFolderId = routerResult.createdPlanFolderId,
                    streamingMessageId = streamingMsgId
                )

                // YALNIZCA aktif mod Soru Çözümü veya Benzer Soru ise Antrenman Bankasına otomatik kaydet
                val isExplicitQuestionMode = (resolvedEngine == SharkAiEngine.SOLVER_ENGINE ||
                    resolvedEngine == SharkAiEngine.TWIN_QUESTION_ENGINE ||
                    capabilityOverride == SharkAiCapability.PROBLEM_SOLVER ||
                    capabilityOverride == SharkAiCapability.TWIN_QUESTION) &&
                    resolvedEngine != SharkAiEngine.CONVERSATIONAL_PEER &&
                    resolvedEngine != SharkAiEngine.PLANNER_OR_ORGANIZER

                if (attachmentUri != null && isExplicitQuestionMode) {
                    autoSaveQuestionToErrorBank(
                        attachmentUri = attachmentUri,
                        resolvedAttachmentName = resolvedAttachmentName,
                        userPrompt = effectivePrompt,
                        aiResponseText = routerResult.text
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                if (_activeChatSessionId.value != originatingSessionId) {
                    return@launch
                }
                if (attachmentUri != null) {
                    Log.e("SharkAiVision", "Vision error: ${e.javaClass.simpleName} - ${e.message}", e)
                } else {
                    Log.e("SharkAiViewModel", "Error calling AI Router: ${e.javaClass.simpleName} - ${e.message}", e)
                }
                val currentMessages = _uiState.value.messages
                val alreadyStreamedMsg = currentMessages.firstOrNull { it.id == streamingMsgId }

                // Eliminate stream resets: never wipe out already-streamed tokens on network blips
                if (alreadyStreamedMsg != null && alreadyStreamedMsg.text.isNotBlank()) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = null
                    )
                    persistCurrentChatState()
                } else {
                    val remainingMessages = currentMessages.filter { it.id != streamingMsgId }
                    val errDetail = e.localizedMessage?.takeIf { it.isNotBlank() } ?: e.message ?: "Bağlantı kurulamadı"
                    val errorMsg = AiChatMessage(
                        sender = "ai",
                        text = if (attachmentUri != null) {
                            "Görsel işlenemedi ($errDetail)."
                        } else {
                            "Bağlantı kurulamadı ($errDetail)."
                        },
                        personaTag = currentPersona.id
                    )
                    _uiState.value = _uiState.value.copy(
                        messages = remainingMessages + errorMsg,
                        isLoading = false
                    )
                    persistCurrentChatState()
                }
            }
        }
    }

    private fun resolveDisplayName(uri: Uri): String {
        val resolver = getApplication<Application>().contentResolver
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx != -1 && cursor.moveToFirst()) {
                    val name = cursor.getString(idx)
                    if (!name.isNullOrBlank()) return name
                }
            }
        }
        return uri.lastPathSegment ?: "Dosya"
    }

    private suspend fun buildAttachmentPreview(
        context: Context,
        resolver: ContentResolver,
        uri: Uri
    ): SelectedAttachmentPreview {
        val mimeType = resolver.getType(uri)
        val displayName = resolveDisplayName(uri)
        val isPdf = mimeType == "application/pdf" || displayName.endsWith(".pdf", ignoreCase = true)
        val isImage = mimeType?.startsWith("image/") == true ||
            displayName.endsWith(".png", ignoreCase = true) ||
            displayName.endsWith(".jpg", ignoreCase = true) ||
            displayName.endsWith(".jpeg", ignoreCase = true) ||
            displayName.endsWith(".webp", ignoreCase = true)

        val thumbnail: ImageBitmap? = when {
            isImage -> runCatching {
                ImageStorageManager.prepareOptimizedBitmapForAi(
                    context = context,
                    uri = uri,
                    enhanceContrast = false
                )?.asImageBitmap()
            }.getOrNull()
            isPdf -> runCatching {
                PdfBitmapConverter.convertPdfUriToBitmaps(context, uri, maxPages = 1)
                    .firstOrNull()
                    ?.asImageBitmap()
            }.getOrNull()
            else -> null
        }

        return SelectedAttachmentPreview(
            uri = uri,
            displayName = displayName,
            mimeType = mimeType ?: if (isPdf) "application/pdf" else "image/jpeg",
            thumbnail = thumbnail
        )
    }

    data class ProcessActionResult(
        val cleanText: String,
        val createdPlanFolderId: String? = null,
        val isPlanCard: Boolean = false
    )

    private fun extractActionsFromResponse(rawResponse: String): List<Pair<String, Map<String, Any>>> {
        val results = mutableListOf<Pair<String, Map<String, Any>>>()
        val actionMarker = "#ACTION:"
        var searchIdx = 0

        while (searchIdx < rawResponse.length) {
            val foundIdx = rawResponse.indexOf(actionMarker, searchIdx, ignoreCase = true)
            if (foundIdx == -1) break

            val braceStart = rawResponse.indexOf('{', foundIdx + actionMarker.length)
            if (braceStart == -1) {
                searchIdx = foundIdx + actionMarker.length
                continue
            }

            var depth = 0
            var braceEnd = -1
            var inQuotes = false
            var escape = false

            for (i in braceStart until rawResponse.length) {
                val c = rawResponse[i]
                if (escape) {
                    escape = false
                    continue
                }
                if (c == '\\') {
                    escape = true
                    continue
                }
                if (c == '"') {
                    inQuotes = !inQuotes
                    continue
                }
                if (!inQuotes) {
                    if (c == '{') depth++
                    else if (c == '}') {
                        depth--
                        if (depth == 0) {
                            braceEnd = i
                            break
                        }
                    }
                }
            }

            if (braceEnd != -1) {
                val jsonCandidate = rawResponse.substring(braceStart, braceEnd + 1)
                var fullEnd = braceEnd + 1
                while (fullEnd < rawResponse.length && (rawResponse[fullEnd] == '`' || rawResponse[fullEnd] == ' ' || rawResponse[fullEnd] == '\t' || rawResponse[fullEnd] == '\r' || rawResponse[fullEnd] == '\n')) {
                    fullEnd++
                }
                if (fullEnd < rawResponse.length && rawResponse[fullEnd] == '#') {
                    fullEnd++
                }
                val fullMatchText = rawResponse.substring(foundIdx, fullEnd)

                runCatching {
                    @Suppress("UNCHECKED_CAST")
                    val map = gson.fromJson(jsonCandidate, Map::class.java) as? Map<String, Any>
                    if (map != null) {
                        results.add(Pair(fullMatchText, map))
                    }
                }
                searchIdx = fullEnd
            } else {
                searchIdx = foundIdx + actionMarker.length
            }
        }

        return results
    }

    private suspend fun processActionAndRecordMemory(
        persona: AiPersona,
        rawResponse: String
    ): ProcessActionResult {
        var cleanText = rawResponse
        var createdFolderId: String? = null
        var isPlan = false

        try {
            val actionPairs = extractActionsFromResponse(rawResponse)

            for ((fullMatch, actionMap) in actionPairs) {
                cleanText = cleanText.replace(fullMatch, "").trim()
                val type = (actionMap["type"] as? String)?.uppercase()

                when (type) {
                    "CREATE_WEEKLY_PLAN", "CREATE_PLAN", "INSERT_PLAN", "WEEKLY_PLAN", "ADD_PLAN", "PLAN" -> {
                        val title = (actionMap["title"] as? String)?.ifBlank { "Haftalık Çalışma Programı" }
                            ?: "Haftalık Çalışma Programı"
                        val summary = (actionMap["summary"] as? String ?: actionMap["weeklyFocusSummary"] as? String)?.ifBlank { title } ?: title
                        val rawSchedule = (actionMap["schedule"] as? List<*>)
                            ?: (actionMap["tasks"] as? List<*>)
                            ?: (actionMap["plan"] as? List<*>)
                            ?: (actionMap["days"] as? List<*>)
                            ?: emptyList<Any>()
                        val parsedTasks = mutableListOf<PlanEntity>()

                        rawSchedule.forEachIndexed { idx, item ->
                            val taskMap = item as? Map<*, *> ?: return@forEachIndexed
                            val subj = (taskMap["subject"] as? String ?: taskMap["course"] as? String ?: "Ders").ifBlank { "Ders" }
                            val top = (taskMap["topic"] as? String ?: taskMap["title"] as? String ?: "").trim()
                            val time = (taskMap["timeSlot"] as? String ?: taskMap["time"] as? String ?: taskMap["scheduledTime"] as? String ?: "09:00 - 10:30").ifBlank { "09:00 - 10:30" }
                            val questions = (taskMap["targetQuestions"] as? Number)?.toInt()
                                ?: (taskMap["questions"] as? Number)?.toInt()
                                ?: (taskMap["count"] as? Number)?.toInt()
                                ?: 30
                            val day = (taskMap["dayOfWeek"] as? Number)?.toInt()
                                ?: (taskMap["day"] as? Number)?.toInt()
                                ?: (taskMap["dayIndex"] as? Number)?.toInt()
                                ?: (idx % 7 + 1)

                            parsedTasks.add(
                                PlanEntity(
                                    id = UUID.randomUUID().toString(),
                                    folderId = "",
                                    title = top.ifBlank { subj },
                                    subject = subj,
                                    topic = top,
                                    dayOfWeek = day.coerceIn(1, 7),
                                    scheduledTime = time,
                                    targetQuestions = if (questions > 0) questions else 30,
                                    orderIndex = idx
                                )
                            )
                        }

                        if (parsedTasks.isNotEmpty()) {
                            val newFolderId = withContext(Dispatchers.IO) {
                                val savedFolderId = planRepositoryImpl.saveGeneratedPlan(
                                    folderTitle = if (title.length > 38) title.take(38) + "…" else title,
                                    tasks = parsedTasks
                                )

                                // Dual persistence: active_study_plans & study_plan_tasks (StudyPlanDao)
                                val dayNames = listOf("Pazartesi", "Salı", "Çarşamba", "Perşembe", "Cuma", "Cumartesi", "Pazar")
                                val activePlan = ActivePlanEntity(
                                    id = "CURRENT_ACTIVE_PLAN",
                                    title = title,
                                    strategyNote = summary,
                                    totalTargetQuestions = parsedTasks.sumOf { it.targetQuestions },
                                    updatedAtTimestamp = System.currentTimeMillis()
                                )
                                val studyTasks = parsedTasks.mapIndexed { idx, p ->
                                    val dName = dayNames.getOrElse(p.dayOfWeek - 1) { "Pazartesi" }
                                    StudyTaskEntity(
                                        localId = 0,
                                        planId = "CURRENT_ACTIVE_PLAN",
                                        dayName = dName,
                                        dayIndex = (p.dayOfWeek - 1).coerceIn(0, 6),
                                        time = p.scheduledTime,
                                        subject = p.subject,
                                        topic = p.topic.ifBlank { p.subject },
                                        goal = "${p.targetQuestions} Soru Hedefi",
                                        isCompleted = false
                                    )
                                }
                                database.studyPlanDao().replaceActivePlanWithTasks(activePlan, studyTasks)

                                // Remote cloud sync
                                runCatching {
                                    val firestore = GoogleServicesHelper.getFirebaseFirestore(getApplication())
                                    val uid = GoogleServicesHelper.getCurrentUser(context = getApplication())?.uid
                                    if (uid != null && firestore != null) {
                                        val planMap = hashMapOf<String, Any>(
                                            "id" to "CURRENT_ACTIVE_PLAN",
                                            "title" to title,
                                            "strategyNote" to summary,
                                            "totalTargetQuestions" to activePlan.totalTargetQuestions,
                                            "updatedAtTimestamp" to activePlan.updatedAtTimestamp,
                                            "userId" to uid,
                                            "studentId" to uid,
                                            "tasks" to studyTasks.map { task ->
                                                hashMapOf(
                                                    "dayIndex" to task.dayIndex,
                                                    "dayName" to task.dayName,
                                                    "subject" to task.subject,
                                                    "time" to task.time,
                                                    "topic" to task.topic,
                                                    "goal" to task.goal,
                                                    "isCompleted" to task.isCompleted
                                                )
                                            }
                                        )
                                        firestore.collection("students").document(uid).collection("plans").document("CURRENT_ACTIVE_PLAN").set(planMap, SetOptions.merge())
                                        firestore.collection("users").document(uid).collection("plans").document("CURRENT_ACTIVE_PLAN").set(planMap, SetOptions.merge())
                                    }
                                }
                                SyncWorker.enqueueImmediateSync(getApplication())
                                savedFolderId
                            }
                            createdFolderId = newFolderId
                            isPlan = true
                            memoryRepository.recordInsight(
                                persona = persona,
                                memoryType = "DECISION",
                                content = "Haftalık Plan Oluşturuldu: $title (${parsedTasks.size} Görev)"
                            )
                            if (!cleanText.contains("#ACTION:NAV_PLANS")) {
                                cleanText += "\n\n#ACTION:NAV_PLANS"
                            }
                        }
                    }

                    "INSERT_PLAN_TASK", "INSERT_TASK", "ADD_TASK", "ADD_PLAN_TASK" -> {
                        val subject = (actionMap["subject"] as? String ?: actionMap["course"] as? String ?: "Ders").ifBlank { "Ders" }
                        val topic = (actionMap["topic"] as? String ?: actionMap["title"] as? String ?: "").trim()
                        val questions = (actionMap["questions"] as? Number)?.toInt()
                            ?: (actionMap["targetQuestions"] as? Number)?.toInt()
                            ?: 30
                        val day = (actionMap["day"] as? Number)?.toInt()
                            ?: (actionMap["dayOfWeek"] as? Number)?.toInt()
                            ?: 1
                        val time = (actionMap["time"] as? String ?: actionMap["timeSlot"] as? String ?: "09:00 - 10:30").ifBlank { "09:00 - 10:30" }

                        withContext(Dispatchers.IO) {
                            val folders = database.planFolderDao().getFoldersCreatedAfter(0)
                            val folderId = folders.firstOrNull()?.id ?: run {
                                val newF = PlanFolderEntity(
                                    id = UUID.randomUUID().toString(),
                                    title = "Haftalık Çalışma Programı",
                                    createdAt = System.currentTimeMillis(),
                                    endTimestamp = System.currentTimeMillis() + (7L * 24 * 3600 * 1000),
                                    source = "AI_PLANNER"
                                )
                                database.planFolderDao().insertFolder(newF)
                                newF.id
                            }
                            val task = PlanEntity(
                                id = UUID.randomUUID().toString(),
                                folderId = folderId,
                                title = topic.ifBlank { subject },
                                subject = subject,
                                topic = topic,
                                dayOfWeek = day.coerceIn(1, 7),
                                scheduledTime = time,
                                targetQuestions = if (questions > 0) questions else 30,
                                orderIndex = 0
                            )
                            database.planDao().insertTasks(listOf(task))

                            val dayNames = listOf("Pazartesi", "Salı", "Çarşamba", "Perşembe", "Cuma", "Cumartesi", "Pazar")
                            val studyTask = StudyTaskEntity(
                                localId = 0,
                                planId = "CURRENT_ACTIVE_PLAN",
                                dayName = dayNames.getOrElse(day.coerceIn(1, 7) - 1) { "Pazartesi" },
                                dayIndex = (day.coerceIn(1, 7) - 1).coerceIn(0, 6),
                                time = time,
                                subject = subject,
                                topic = topic.ifBlank { subject },
                                goal = "$questions Soru Hedefi",
                                isCompleted = false
                            )
                            database.studyPlanDao().insertTasks(listOf(studyTask))

                            runCatching {
                                val firestore = GoogleServicesHelper.getFirebaseFirestore(getApplication())
                                val uid = GoogleServicesHelper.getCurrentUser(context = getApplication())?.uid
                                if (uid != null && firestore != null) {
                                    firestore.collection("users").document(uid).collection("plans").document(task.id).set(task)
                                }
                            }
                            SyncWorker.enqueueImmediateSync(getApplication())
                        }
                        memoryRepository.recordInsight(
                            persona = persona,
                            memoryType = "DECISION",
                            content = "Görev Eklendi: Gün $day $subject - $topic ($questions Soru)"
                        )
                        if (!cleanText.contains("#ACTION:NAV_PLANS")) {
                            cleanText += "\n\n#ACTION:NAV_PLANS"
                        }
                    }

                    "INSERT_TARGET" -> {
                        val title = (actionMap["title"] as? String ?: "Hedef").trim()
                        val desc = (actionMap["description"] as? String ?: "").trim()
                        val questions = (actionMap["targetQuestions"] as? Number)?.toInt()
                            ?: (actionMap["questions"] as? Number)?.toInt() ?: 50
                        val rawType = actionMap["type"] as? String ?: "SHORT_TERM"
                        val targetType = if (rawType.contains("LONG", ignoreCase = true)) TargetType.LONG_TERM else TargetType.SHORT_TERM
                        val rawPeriod = (actionMap["period"] as? String ?: "").uppercase(Locale.ROOT)
                        val targetPeriod = when {
                            rawPeriod.contains("SIX") -> TargetPeriod.SIX_MONTH
                            rawPeriod.contains("MONTH") -> TargetPeriod.MONTHLY
                            rawPeriod.contains("DAY") || rawPeriod.contains("DAILY") -> TargetPeriod.DAILY
                            targetType == TargetType.LONG_TERM -> TargetPeriod.MONTHLY
                            else -> TargetPeriod.WEEKLY
                        }
                        val priority = actionMap["priority"] as? String ?: "HIGH"
                        val category = (actionMap["category"] as? String ?: actionMap["subject"] as? String ?: "HEDEF").ifBlank { "HEDEF" }

                        val targetEntity = TargetEntity(
                            title = title,
                            description = desc,
                            type = targetType,
                            period = targetPeriod,
                            deadlineMillis = System.currentTimeMillis() + (7L * 24 * 3600 * 1000),
                            targetQuestions = questions,
                            priority = priority,
                            category = category
                        )
                        repository.addTarget(
                            target = targetEntity,
                            tasks = if (desc.isNotBlank()) listOf(desc) else listOf("$title ($questions Soru)")
                        )
                        SyncWorker.enqueueImmediateSync(getApplication())
                        memoryRepository.recordInsight(
                            persona = persona,
                            memoryType = "DECISION",
                            content = "Hedef Eklendi: $title ($questions Soru)"
                        )
                        if (!cleanText.contains("#ACTION:NAV_TARGETS")) {
                            cleanText += "\n\n#ACTION:NAV_TARGETS"
                        }
                    }

                    "INSERT_TARGETS_BATCH", "ADD_TARGETS_BATCH", "CREATE_TARGETS_BATCH" -> {
                        val rawTargets = (actionMap["targets"] as? List<*>)
                            ?: (actionMap["items"] as? List<*>)
                            ?: emptyList<Any>()
                        var insertedCount = 0

                        withContext(Dispatchers.IO) {
                            rawTargets.forEach { item ->
                                val tMap = item as? Map<*, *> ?: return@forEach
                                val title = (tMap["title"] as? String ?: tMap["name"] as? String ?: "").trim()
                                if (title.isBlank()) return@forEach
                                val desc = (tMap["description"] as? String ?: "").trim()
                                val questions = (tMap["targetQuestions"] as? Number)?.toInt()
                                    ?: (tMap["questions"] as? Number)?.toInt() ?: 50
                                val rawType = tMap["type"] as? String ?: "SHORT_TERM"
                                val targetType = if (rawType.contains("LONG", ignoreCase = true)) TargetType.LONG_TERM else TargetType.SHORT_TERM
                                val rawPeriod = (tMap["period"] as? String ?: "").uppercase(Locale.ROOT)
                                val targetPeriod = when {
                                    rawPeriod.contains("SIX") -> TargetPeriod.SIX_MONTH
                                    rawPeriod.contains("MONTH") -> TargetPeriod.MONTHLY
                                    rawPeriod.contains("DAY") || rawPeriod.contains("DAILY") -> TargetPeriod.DAILY
                                    targetType == TargetType.LONG_TERM -> TargetPeriod.MONTHLY
                                    else -> TargetPeriod.WEEKLY
                                }
                                val priority = tMap["priority"] as? String ?: "HIGH"
                                val category = (tMap["category"] as? String ?: tMap["subject"] as? String ?: "HEDEF").ifBlank { "HEDEF" }

                                val targetEntity = TargetEntity(
                                    title = title,
                                    description = desc,
                                    type = targetType,
                                    period = targetPeriod,
                                    deadlineMillis = System.currentTimeMillis() + (7L * 24 * 3600 * 1000),
                                    targetQuestions = questions,
                                    priority = priority,
                                    category = category
                                )
                                repository.addTarget(
                                    target = targetEntity,
                                    tasks = if (desc.isNotBlank()) listOf(desc) else listOf("$title ($questions Soru)")
                                )
                                insertedCount++
                            }
                            if (insertedCount > 0) {
                                SyncWorker.enqueueImmediateSync(getApplication())
                            }
                        }

                        if (insertedCount > 0) {
                            memoryRepository.recordInsight(
                                persona = persona,
                                memoryType = "DECISION",
                                content = "Toplu Hedef Eklendi ($insertedCount adet)"
                            )
                            if (!cleanText.contains("#ACTION:NAV_TARGETS")) {
                                cleanText += "\n\n#ACTION:NAV_TARGETS"
                            }
                        }
                    }

                    "UPDATE_TARGET_PROGRESS", "UPDATE_TARGET", "SET_TARGET_PROGRESS" -> {
                        val targetId = (actionMap["targetId"] as? Number)?.toLong()
                            ?: (actionMap["id"] as? Number)?.toLong() ?: 0L
                        val title = (actionMap["title"] as? String ?: "").trim()
                        val solvedInput = (actionMap["solvedQuestions"] as? Number)?.toInt()
                            ?: (actionMap["completedQuestions"] as? Number)?.toInt()
                            ?: (actionMap["questions"] as? Number)?.toInt()
                        val explicitProgress = (actionMap["progress"] as? Number)?.toFloat()
                        val explicitCompleted = actionMap["isCompleted"] as? Boolean
                        val isIncremental = actionMap["isIncremental"] as? Boolean ?: false
                        val targetNet = (actionMap["targetNet"] as? Number)?.toInt()
                            ?: (actionMap["targetValue"] as? Number)?.toInt()

                        val updatedTarget = withContext(Dispatchers.IO) {
                            val existing = if (targetId > 0L) {
                                database.targetDao().getTargetById(targetId)
                            } else {
                                val allActive = (database.targetDao().getTargetsWithTasksSync(TargetType.SHORT_TERM) +
                                    database.targetDao().getTargetsWithTasksSync(TargetType.LONG_TERM)).map { it.target }
                                findMatchingTarget(allActive, title)
                            }

                            if (existing != null) {
                                val nextSolved = when {
                                    solvedInput != null && isIncremental -> (existing.solvedQuestions + solvedInput).coerceAtLeast(0)
                                    solvedInput != null -> solvedInput.coerceAtLeast(0)
                                    else -> existing.solvedQuestions
                                }
                                val nextTotal = if (existing.targetQuestions > 0) {
                                    existing.targetQuestions.coerceAtLeast(nextSolved)
                                } else {
                                    (solvedInput ?: 50).coerceAtLeast(1)
                                }
                                val computedProgress = when {
                                    explicitProgress != null -> if (explicitProgress > 1f) (explicitProgress / 100f).coerceIn(0f, 1f) else explicitProgress.coerceIn(0f, 1f)
                                    nextTotal > 0 -> (nextSolved.toFloat() / nextTotal.toFloat()).coerceIn(0f, 1f)
                                    else -> existing.progress
                                }
                                val nextCompleted = explicitCompleted ?: (computedProgress >= 1f)
                                val modified = existing.copy(
                                    solvedQuestions = nextSolved,
                                    targetQuestions = nextTotal,
                                    progress = computedProgress,
                                    isCompleted = nextCompleted,
                                    targetValue = targetNet?.toString() ?: existing.targetValue,
                                    isSynced = false,
                                    lastModifiedMillis = System.currentTimeMillis()
                                )
                                database.targetDao().updateTarget(modified)
                                SyncWorker.enqueueImmediateSync(getApplication())
                                modified
                            } else null
                        }

                        memoryRepository.recordInsight(
                            persona = persona,
                            memoryType = "GOAL_STATE",
                            content = if (updatedTarget != null) {
                                "Hedef İlerlemesi Güncellendi: ${updatedTarget.title} (${updatedTarget.solvedQuestions}/${updatedTarget.targetQuestions} Soru)"
                            } else {
                                "Hedef Güncellendi: $title (${targetNet ?: solvedInput ?: 0})"
                            }
                        )
                        if (!cleanText.contains("#ACTION:NAV_TARGETS")) {
                            cleanText += "\n\n#ACTION:NAV_TARGETS"
                        }
                    }

                    "UPDATE_RESOURCE_PROGRESS", "SET_RESOURCE_PROGRESS", "UPDATE_RESOURCE", "UPDATE_BOOK_PROGRESS" -> {
                        val resourceId = (actionMap["resourceId"] as? Number)?.toLong()
                            ?: (actionMap["id"] as? Number)?.toLong() ?: 0L
                        val title = (actionMap["title"] as? String ?: actionMap["book"] as? String ?: "").trim()
                        val subject = (actionMap["subject"] as? String ?: "Genel").trim()
                        val completedInput = (actionMap["completedUnits"] as? Number)?.toInt()
                            ?: (actionMap["progress"] as? Number)?.toInt()
                            ?: (actionMap["page"] as? Number)?.toInt()
                            ?: (actionMap["currentUnits"] as? Number)?.toInt()
                            ?: 0
                        val totalInput = (actionMap["totalUnits"] as? Number)?.toInt()
                        val unitTypeInput = (actionMap["unitType"] as? String)?.takeIf { it.isNotBlank() }
                        val isIncremental = actionMap["isIncremental"] as? Boolean ?: false

                        withContext(Dispatchers.IO) {
                            val allResources = database.goalsDao().getAllResourcesRaw()
                            val existing = if (resourceId > 0L) {
                                allResources.firstOrNull { it.id == resourceId } ?: findMatchingResource(allResources, title, subject)
                            } else {
                                findMatchingResource(allResources, title, subject)
                            }

                            if (existing != null) {
                                val newCompleted = if (isIncremental) {
                                    (existing.completedUnits + completedInput).coerceAtLeast(0)
                                } else {
                                    completedInput.coerceAtLeast(0)
                                }
                                val newTotal = (totalInput ?: existing.totalUnits).coerceAtLeast(newCompleted.coerceAtLeast(1))
                                val newUnitType = unitTypeInput ?: existing.unitType
                                val now = System.currentTimeMillis()
                                val updated = existing.copy(
                                    completedUnits = newCompleted.coerceIn(0, newTotal),
                                    totalUnits = newTotal,
                                    unitType = newUnitType,
                                    updatedAtMillis = now,
                                    lastModifiedMillis = now,
                                    isSynced = false
                                )
                                database.goalsDao().updateResource(updated)
                                database.goalsDao().setProgress(existing.id, updated.completedUnits, now)

                                runCatching {
                                    val firestore = GoogleServicesHelper.getFirebaseFirestore(getApplication())
                                    val uid = GoogleServicesHelper.getCurrentUser(context = getApplication())?.uid
                                    if (uid != null && firestore != null) {
                                        val docId = updated.cloudId ?: updated.id.toString()
                                        val resData = hashMapOf<String, Any>(
                                            "title" to updated.title,
                                            "subject" to updated.subject,
                                            "totalUnits" to updated.totalUnits,
                                            "currentUnits" to updated.completedUnits,
                                            "completedUnits" to updated.completedUnits,
                                            "unitType" to updated.unitType,
                                            "studentId" to uid,
                                            "userId" to uid,
                                            "updatedAt" to now
                                        )
                                        firestore.collection("students").document(uid).collection("resources").document(docId).set(resData, SetOptions.merge())
                                        firestore.collection("resources").document(docId).set(resData, SetOptions.merge())
                                    }
                                }
                                SyncWorker.enqueueImmediateSync(getApplication())
                                memoryRepository.recordInsight(
                                    persona = persona,
                                    memoryType = "DECISION",
                                    content = "Kaynak İlerlemesi Güncellendi: ${updated.title} (${updated.completedUnits}/${updated.totalUnits} ${updated.unitType})"
                                )
                            } else if (title.isNotBlank()) {
                                // Only create if the book truly does not exist in the library yet
                                val institutionId = com.example.sharkmentor.data.local.preferences.UserPreferences.getInstance(getApplication()).institutionId ?: "INDIVIDUAL"
                                val resolvedTotal = (totalInput ?: 250).coerceAtLeast(completedInput.coerceAtLeast(40))
                                val resolvedUnitType = unitTypeInput ?: if (resolvedTotal >= 100 || completedInput >= 50) "Sayfa" else "Test"
                                val resEntity = ResourceEntity(
                                    title = title,
                                    subject = subject,
                                    totalUnits = resolvedTotal,
                                    completedUnits = completedInput.coerceIn(0, resolvedTotal),
                                    unitType = resolvedUnitType,
                                    institutionId = institutionId,
                                    isSynced = false
                                )
                                database.goalsDao().insertResource(resEntity)
                                SyncWorker.enqueueImmediateSync(getApplication())
                            }
                        }
                        if (!cleanText.contains("#ACTION:NAV_RESOURCES")) {
                            cleanText += "\n\n#ACTION:NAV_RESOURCES"
                        }
                    }

                    "INSERT_RESOURCE" -> {
                        val title = (actionMap["title"] as? String ?: "Kaynak Kitap").trim()
                        val subject = (actionMap["subject"] as? String ?: "Genel").trim()
                        val totalUnits = (actionMap["totalUnits"] as? Number)?.toInt() ?: 40
                        val completedUnits = (actionMap["completedUnits"] as? Number)?.toInt()
                            ?: (actionMap["currentUnits"] as? Number)?.toInt()
                            ?: (actionMap["progress"] as? Number)?.toInt()
                            ?: 0
                        val unitType = actionMap["unitType"] as? String ?: "Test"

                        withContext(Dispatchers.IO) {
                            val allResources = database.goalsDao().getAllResourcesRaw()
                            val existing = findMatchingResource(allResources, title, subject)
                            val now = System.currentTimeMillis()

                            if (existing != null) {
                                // Do NOT open a duplicate book! Update existing ResourceEntity
                                val nextCompleted = if (completedUnits > 0) completedUnits else existing.completedUnits
                                val nextTotal = maxOf(existing.totalUnits, totalUnits, nextCompleted)
                                val updated = existing.copy(
                                    completedUnits = nextCompleted.coerceIn(0, nextTotal),
                                    totalUnits = nextTotal,
                                    unitType = unitType.ifBlank { existing.unitType },
                                    updatedAtMillis = now,
                                    lastModifiedMillis = now,
                                    isSynced = false
                                )
                                database.goalsDao().updateResource(updated)
                                database.goalsDao().setProgress(existing.id, updated.completedUnits, now)
                                SyncWorker.enqueueImmediateSync(getApplication())
                            } else {
                                val institutionId = com.example.sharkmentor.data.local.preferences.UserPreferences.getInstance(getApplication()).institutionId ?: "INDIVIDUAL"
                                val resolvedTotal = totalUnits.coerceAtLeast(completedUnits.coerceAtLeast(1))
                                val resEntity = ResourceEntity(
                                    title = title,
                                    subject = subject,
                                    totalUnits = resolvedTotal,
                                    completedUnits = completedUnits.coerceIn(0, resolvedTotal),
                                    unitType = unitType,
                                    institutionId = institutionId,
                                    isSynced = false
                                )
                                val insertedId = database.goalsDao().insertResource(resEntity)
                                val finalId = if (insertedId > 0L) insertedId.toString() else resEntity.id.toString()
                                runCatching {
                                    val firestore = GoogleServicesHelper.getFirebaseFirestore(getApplication())
                                    val uid = GoogleServicesHelper.getCurrentUser(context = getApplication())?.uid
                                    if (uid != null && firestore != null) {
                                        val resData = hashMapOf<String, Any>(
                                            "title" to title,
                                            "subject" to subject,
                                            "totalUnits" to resolvedTotal,
                                            "currentUnits" to resEntity.completedUnits,
                                            "completedUnits" to resEntity.completedUnits,
                                            "unitType" to unitType,
                                            "studentId" to uid,
                                            "userId" to uid,
                                            "createdAt" to now
                                        )
                                        firestore.collection("students").document(uid).collection("resources").document(finalId).set(resData, SetOptions.merge())
                                        firestore.collection("resources").document(finalId).set(resData, SetOptions.merge())
                                    }
                                }
                                SyncWorker.enqueueImmediateSync(getApplication())
                            }
                        }
                        memoryRepository.recordInsight(
                            persona = persona,
                            memoryType = "DECISION",
                            content = "Kaynak Kaydedildi/Güncellendi: $title ($subject - $completedUnits/$totalUnits $unitType)"
                        )
                        if (!cleanText.contains("#ACTION:NAV_RESOURCES")) {
                            cleanText += "\n\n#ACTION:NAV_RESOURCES"
                        }
                    }

                    "INSERT_TOPICS_BATCH", "ADD_TOPICS_BATCH", "CREATE_TOPICS_BATCH", "BULK_TOPICS" -> {
                        val preset = actionMap["preset"] as? String
                        val exam = actionMap["exam"] as? String ?: "TYT"
                        val defaultTargetLevel = (actionMap["targetLevel"] as? Number)?.toInt() ?: 5
                        val rawTopicsList = (actionMap["topics"] as? List<*>)
                            ?: (actionMap["items"] as? List<*>)
                            ?: emptyList<Any>()
                        val rawSubjectsList = (actionMap["subjects"] as? List<*>) ?: emptyList<Any>()

                        val collectedPairs = mutableListOf<Triple<String, String, Int>>()

                        // 1. Expand preset / exam if requested (e.g. TYT_ALL, AYT_ALL, or user asked for "TYT tüm konular")
                        val presetPairs = YksCurriculumCatalog.expandFromPresetOrPrompt(
                            preset = preset,
                            exam = exam,
                            userPrompt = lastUserPromptText
                        )
                        presetPairs.forEach { (subj, top) ->
                            collectedPairs.add(Triple(subj, top, defaultTargetLevel))
                        }

                        // 2. Parse explicit `topics` array from AI (and expand any placeholder/empty subject entries!)
                        rawTopicsList.forEach { entry ->
                            when (entry) {
                                is Map<*, *> -> {
                                    val subj = (entry["subject"] as? String ?: entry["course"] as? String ?: exam).trim().ifBlank { "Genel" }
                                    val top = (entry["topic"] as? String ?: entry["title"] as? String ?: entry["name"] as? String ?: "").trim()
                                    val subTopicsArr = entry["topics"] as? List<*> ?: entry["subtopics"] as? List<*>
                                    val lvl = (entry["targetLevel"] as? Number)?.toInt() ?: defaultTargetLevel

                                    if (!subTopicsArr.isNullOrEmpty()) {
                                        subTopicsArr.forEach { st ->
                                            val stName = st?.toString()?.trim().orEmpty()
                                            if (stName.isNotBlank() && !YksCurriculumCatalog.isPlaceholderOrBroadTopic(stName)) {
                                                collectedPairs.add(Triple(subj, stName, lvl))
                                            }
                                        }
                                    } else if (YksCurriculumCatalog.isPlaceholderOrBroadTopic(top)) {
                                        val expanded = YksCurriculumCatalog.expandSubjectTopics(subj, exam)
                                        if (expanded.isNotEmpty()) {
                                            expanded.forEach { (s, t) -> collectedPairs.add(Triple(s, t, lvl)) }
                                        }
                                    } else {
                                        collectedPairs.add(Triple(subj, top, lvl))
                                    }
                                }
                            }
                        }

                        // 3. Parse optional `subjects` array (`[{"subject": "TYT Matematik", "topics": ["..."]}]`)
                        rawSubjectsList.forEach { sEntry ->
                            val sMap = sEntry as? Map<*, *> ?: return@forEach
                            val subj = (sMap["subject"] as? String ?: sMap["name"] as? String ?: exam).trim()
                            val subList = (sMap["topics"] as? List<*>) ?: emptyList<Any>()
                            if (subList.isEmpty()) {
                                YksCurriculumCatalog.expandSubjectTopics(subj, exam).forEach { (s, t) ->
                                    collectedPairs.add(Triple(s, t, defaultTargetLevel))
                                }
                            } else {
                                subList.forEach { tItem ->
                                    val tName = tItem?.toString()?.trim().orEmpty()
                                    if (tName.isNotBlank()) {
                                        collectedPairs.add(Triple(subj, tName, defaultTargetLevel))
                                    }
                                }
                            }
                        }

                        val insertedCount = batchInsertTopicGoalsDeduped(collectedPairs)
                        if (insertedCount > 0) {
                            memoryRepository.recordInsight(
                                persona = persona,
                                memoryType = "DECISION",
                                content = "Toplu Konu Hakimiyeti Eklendi: $insertedCount alt konu"
                            )
                            if (!cleanText.contains("#ACTION:NAV_TOPICS")) {
                                cleanText += "\n\n#ACTION:NAV_TOPICS"
                            }
                        }
                    }

                    "INSERT_TOPIC", "UPDATE_TOPIC_PROGRESS", "UPDATE_TOPIC" -> {
                        val subject = (actionMap["subject"] as? String ?: "Genel").trim()
                        val topic = (actionMap["topic"] as? String ?: "Konu").trim()
                        val currentLevel = (actionMap["currentLevel"] as? Number)?.toInt()
                            ?: (actionMap["level"] as? Number)?.toInt() ?: 1
                        val targetLevel = (actionMap["targetLevel"] as? Number)?.toInt() ?: 5

                        // If AI emitted a broad placeholder (e.g. "Tüm Konular" / "Genel"), expand into full sub-topics!
                        if (YksCurriculumCatalog.isPlaceholderOrBroadTopic(topic) || YksCurriculumCatalog.isPlaceholderOrBroadTopic(subject)) {
                            val expandedFromPrompt = YksCurriculumCatalog.expandFromPresetOrPrompt(
                                preset = null,
                                exam = subject,
                                userPrompt = "$subject $topic $lastUserPromptText"
                            ).ifEmpty {
                                YksCurriculumCatalog.expandSubjectTopics(subject)
                            }
                            if (expandedFromPrompt.isNotEmpty()) {
                                val batchCount = batchInsertTopicGoalsDeduped(
                                    expandedFromPrompt.map { (s, t) -> Triple(s, t, targetLevel) }
                                )
                                memoryRepository.recordInsight(
                                    persona = persona,
                                    memoryType = "DECISION",
                                    content = "Müfredat Konuları Eklendi ($batchCount alt konu)"
                                )
                                if (!cleanText.contains("#ACTION:NAV_TOPICS")) {
                                    cleanText += "\n\n#ACTION:NAV_TOPICS"
                                }
                                continue
                            }
                        }

                        withContext(Dispatchers.IO) {
                            val existingTopics = database.goalsDao().getAllTopicsRaw()
                            val existing = existingTopics.firstOrNull {
                                it.subject.equals(subject, ignoreCase = true) &&
                                    it.topic.equals(topic, ignoreCase = true)
                            }
                            val now = System.currentTimeMillis()
                            if (existing != null) {
                                val updated = existing.copy(
                                    currentLevel = currentLevel.coerceIn(0, targetLevel),
                                    targetLevel = targetLevel.coerceAtLeast(1),
                                    isSynced = false,
                                    updatedAtMillis = now
                                )
                                database.goalsDao().updateTopicGoal(updated)
                            } else {
                                val institutionId = com.example.sharkmentor.data.local.preferences.UserPreferences.getInstance(getApplication()).institutionId ?: "INDIVIDUAL"
                                val topicEntity = TopicGoalEntity(
                                    subject = subject,
                                    topic = topic,
                                    currentLevel = currentLevel.coerceIn(0, targetLevel),
                                    targetLevel = targetLevel,
                                    institutionId = institutionId,
                                    isSynced = false,
                                    updatedAtMillis = now
                                )
                                val insertedId = database.goalsDao().insertTopicGoal(topicEntity)
                                val finalId = if (insertedId > 0L) insertedId.toString() else topicEntity.id.toString()
                                runCatching {
                                    val firestore = GoogleServicesHelper.getFirebaseFirestore(getApplication())
                                    val uid = GoogleServicesHelper.getCurrentUser(context = getApplication())?.uid
                                    if (uid != null && firestore != null) {
                                        val topicData = hashMapOf<String, Any>(
                                            "subject" to subject,
                                            "topic" to topic,
                                            "currentLevel" to currentLevel.coerceIn(0, targetLevel),
                                            "targetLevel" to targetLevel,
                                            "studentId" to uid,
                                            "userId" to uid,
                                            "updatedAt" to now
                                        )
                                        firestore.collection("students").document(uid).collection("topics").document(finalId).set(topicData, SetOptions.merge())
                                        firestore.collection("topics").document(finalId).set(topicData, SetOptions.merge())
                                    }
                                }
                            }
                            SyncWorker.enqueueImmediateSync(getApplication())
                        }
                        memoryRepository.recordInsight(
                            persona = persona,
                            memoryType = "DECISION",
                            content = "Konu Hedefi İşlendi: $subject - $topic (Seviye $currentLevel/$targetLevel)"
                        )
                        if (!cleanText.contains("#ACTION:NAV_TOPICS")) {
                            cleanText += "\n\n#ACTION:NAV_TOPICS"
                        }
                    }
                }
            }

            // ── FALLBACK: Kullanıcı "TYT tüm konular" / "AYT tüm konular" istediyse ve AI tekil/eksik bıraktıysa ──
            if (!cleanText.contains("#ACTION:NAV_TOPICS")) {
                val promptLower = lastUserPromptText.lowercase(Locale("tr"))
                val asksBulkTopics = (promptLower.contains("tüm konular") || promptLower.contains("tum konular") || promptLower.contains("bütün konular") || promptLower.contains("müfredat")) &&
                    (promptLower.contains("tyt") || promptLower.contains("ayt")) &&
                    (promptLower.contains("ekle") || promptLower.contains("oluştur") || promptLower.contains("kaydet") || promptLower.contains("aç"))
                if (asksBulkTopics) {
                    val autoExpanded = YksCurriculumCatalog.expandFromPresetOrPrompt(
                        preset = null,
                        exam = null,
                        userPrompt = lastUserPromptText
                    )
                    if (autoExpanded.isNotEmpty()) {
                        val added = batchInsertTopicGoalsDeduped(
                            autoExpanded.map { (s, t) -> Triple(s, t, 5) }
                        )
                        if (added > 0) {
                            cleanText += "\n\n✅ **Toplam $added ders ve alt konu** Konu Hakimiyeti tablosuna eksiksiz eklendi.\n\n#ACTION:NAV_TOPICS"
                        }
                    }
                }
            }

            // ── FALLBACK: Model #ACTION: etiketi koymadan saf JSON veya Markdown JSON schedule döndürdüyse ──
            if (!isPlan) {
                val jsonCandidate = extractBalancedJson(rawResponse)
                if (jsonCandidate != null) {
                    try {
                        val jsonObj = JSONObject(jsonCandidate)
                        val summary = jsonObj.optString("weeklyFocusSummary", jsonObj.optString("summary", "7 Günlük Çalışma Programı"))
                        val title = jsonObj.optString("title", summary)
                        val scheduleArray = jsonObj.optJSONArray("schedule")
                            ?: jsonObj.optJSONArray("tasks")
                            ?: jsonObj.optJSONArray("plan")
                            ?: jsonObj.optJSONArray("days")
                        val parsedTasks = mutableListOf<PlanEntity>()

                        if (scheduleArray != null) {
                            for (i in 0 until scheduleArray.length()) {
                                val item = scheduleArray.optJSONObject(i) ?: continue
                                val subj = item.optString("subject", item.optString("course", "Ders")).ifBlank { "Ders" }
                                val top = item.optString("topic", item.optString("title", "")).trim()
                                val time = item.optString("timeSlot", item.optString("time", item.optString("scheduledTime", "09:00 - 10:30"))).ifBlank { "09:00 - 10:30" }
                                val questions = item.optInt("targetQuestions", item.optInt("questions", 30))
                                val day = item.optInt("dayOfWeek", item.optInt("day", item.optInt("dayIndex", (i % 7 + 1))))

                                parsedTasks.add(
                                    PlanEntity(
                                        id = UUID.randomUUID().toString(),
                                        folderId = "",
                                        title = top.ifBlank { subj },
                                        subject = subj,
                                        topic = top,
                                        dayOfWeek = day.coerceIn(1, 7),
                                        scheduledTime = time,
                                        targetQuestions = if (questions > 0) questions else 30,
                                        orderIndex = i
                                    )
                                )
                            }
                        }

                        if (parsedTasks.isNotEmpty()) {
                            val newFolderId = withContext(Dispatchers.IO) {
                                val savedFolderId = planRepositoryImpl.saveGeneratedPlan(
                                    folderTitle = if (title.length > 38) title.take(38) + "…" else title,
                                    tasks = parsedTasks
                                )

                                val dayNames = listOf("Pazartesi", "Salı", "Çarşamba", "Perşembe", "Cuma", "Cumartesi", "Pazar")
                                val activePlan = ActivePlanEntity(
                                    id = "CURRENT_ACTIVE_PLAN",
                                    title = title,
                                    strategyNote = summary,
                                    totalTargetQuestions = parsedTasks.sumOf { it.targetQuestions },
                                    updatedAtTimestamp = System.currentTimeMillis()
                                )
                                val studyTasks = parsedTasks.mapIndexed { idx, p ->
                                    val dName = dayNames.getOrElse(p.dayOfWeek - 1) { "Pazartesi" }
                                    StudyTaskEntity(
                                        localId = 0,
                                        planId = "CURRENT_ACTIVE_PLAN",
                                        dayName = dName,
                                        dayIndex = (p.dayOfWeek - 1).coerceIn(0, 6),
                                        time = p.scheduledTime,
                                        subject = p.subject,
                                        topic = p.topic.ifBlank { p.subject },
                                        goal = "${p.targetQuestions} Soru Hedefi",
                                        isCompleted = false
                                    )
                                }
                                database.studyPlanDao().replaceActivePlanWithTasks(activePlan, studyTasks)

                                runCatching {
                                    val firestore = GoogleServicesHelper.getFirebaseFirestore(getApplication())
                                    val uid = GoogleServicesHelper.getCurrentUser(context = getApplication())?.uid
                                    if (uid != null && firestore != null) {
                                        val planMap = hashMapOf<String, Any>(
                                            "id" to "CURRENT_ACTIVE_PLAN",
                                            "title" to title,
                                            "strategyNote" to summary,
                                            "totalTargetQuestions" to activePlan.totalTargetQuestions,
                                            "updatedAtTimestamp" to activePlan.updatedAtTimestamp,
                                            "userId" to uid,
                                            "studentId" to uid,
                                            "tasks" to studyTasks.map { task ->
                                                hashMapOf(
                                                    "dayIndex" to task.dayIndex,
                                                    "dayName" to task.dayName,
                                                    "subject" to task.subject,
                                                    "time" to task.time,
                                                    "topic" to task.topic,
                                                    "goal" to task.goal,
                                                    "isCompleted" to task.isCompleted
                                                )
                                            }
                                        )
                                        firestore.collection("students").document(uid).collection("plans").document("CURRENT_ACTIVE_PLAN").set(planMap, SetOptions.merge())
                                        firestore.collection("users").document(uid).collection("plans").document("CURRENT_ACTIVE_PLAN").set(planMap, SetOptions.merge())
                                    }
                                }
                                SyncWorker.enqueueImmediateSync(getApplication())
                                savedFolderId
                            }
                            createdFolderId = newFolderId
                            isPlan = true
                            cleanText = cleanText.replace(jsonCandidate, "").trim()

                            val userSummary = if (summary.isNotBlank()) "\n\n📌 **Haftalık Odak Stratejisi:** $summary" else ""
                            cleanText = "🗓️ **7 Günlük Kişisel Çalışma Programın Hazırlandı!**$userSummary\n\nProgramın gün gün planlandı, hedef soru sayıları belirlendi ve doğrudan **Planlar** sekmesine kaydedildi.\n\n#ACTION:NAV_PLANS"

                            memoryRepository.recordInsight(
                                persona = persona,
                                memoryType = "DECISION",
                                content = "Haftalık Plan Oluşturuldu: $title (${parsedTasks.size} Görev)"
                            )
                        }
                    } catch (e: Exception) {
                        Log.w("SharkAiViewModel", "Fallback schedule parsing error: ${e.message}")
                    }
                }
            }

            if (persona.isTemporaryMemory) {
                memoryRepository.recordInsight(
                    persona = persona,
                    memoryType = "EMOTIONAL_LOG",
                    content = cleanText.take(150)
                )
            }
        } catch (e: Exception) {
            Log.e("SharkAiViewModel", "Error processing action/memory", e)
        }

        // Clean out any raw markdown code blocks from the final chat display
        cleanText = cleanText
            .replace("```json[\\s\\S]*?```".toRegex(), "")
            .replace("```[\\s\\S]*?```".toRegex(), "")
            .trim()

        if (cleanText.isBlank() && isPlan) {
            cleanText = "🗓️ **Haftalık Çalışma Programın Oluşturuldu!**\nProgram Planlar sekmesine gün gün dağıtıldı ve başarıyla kaydedildi.\n\n#ACTION:NAV_PLANS"
        }

        return ProcessActionResult(
            cleanText = cleanText,
            createdPlanFolderId = createdFolderId,
            isPlanCard = isPlan
        )
    }

    private suspend fun batchInsertTopicGoalsDeduped(
        candidates: List<Triple<String, String, Int>>
    ): Int = withContext(Dispatchers.IO) {
        if (candidates.isEmpty()) return@withContext 0
        val existingTopics = database.goalsDao().getAllTopicsRaw()
        val existingKeySet = existingTopics.map {
            "${it.subject.lowercase(Locale("tr")).trim()}::${it.topic.lowercase(Locale("tr")).trim()}"
        }.toMutableSet()

        val institutionId = com.example.sharkmentor.data.local.preferences.UserPreferences.getInstance(getApplication()).institutionId ?: "INDIVIDUAL"
        val now = System.currentTimeMillis()
        val entitiesToInsert = mutableListOf<TopicGoalEntity>()

        for ((rawSubj, rawTop, targetLvl) in candidates) {
            val subj = rawSubj.trim()
            val top = rawTop.trim()
            if (subj.isBlank() || top.isBlank() || YksCurriculumCatalog.isPlaceholderOrBroadTopic(top)) continue
            val key = "${subj.lowercase(Locale("tr"))}::${top.lowercase(Locale("tr"))}"
            if (existingKeySet.add(key)) {
                entitiesToInsert.add(
                    TopicGoalEntity(
                        subject = subj,
                        topic = top,
                        currentLevel = 0,
                        targetLevel = targetLvl.coerceIn(1, 5),
                        institutionId = institutionId,
                        isSynced = false,
                        updatedAtMillis = now
                    )
                )
            }
        }

        if (entitiesToInsert.isNotEmpty()) {
            database.goalsDao().insertTopicGoals(entitiesToInsert)
            SyncWorker.enqueueImmediateSync(getApplication())
        }
        entitiesToInsert.size
    }

    private fun normalizeForMatch(input: String): String {
        return input.lowercase(Locale("tr"))
            .replace(Regex("[^a-z0-9çğıöşü\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun findMatchingResource(
        resources: List<ResourceEntity>,
        queryTitle: String,
        querySubject: String = ""
    ): ResourceEntity? {
        if (resources.isEmpty()) return null
        val normQuery = normalizeForMatch(queryTitle)
        if (normQuery.isBlank()) {
            return if (querySubject.isNotBlank() && querySubject != "Genel") {
                resources.firstOrNull { it.subject.equals(querySubject, ignoreCase = true) }
            } else null
        }

        // 1. Exact or substring match
        resources.firstOrNull { normalizeForMatch(it.title) == normQuery }?.let { return it }
        resources.firstOrNull {
            val normRes = normalizeForMatch(it.title)
            normRes.contains(normQuery) || normQuery.contains(normRes)
        }?.let { return it }

        // 2. Token overlap match (e.g. "3D Matematik" matches "3D TYT Matematik Soru Bankası")
        val queryTokens = normQuery.split(" ").filter { it.length >= 2 }
        if (queryTokens.isNotEmpty()) {
            val best = resources.maxByOrNull { res ->
                val resNorm = normalizeForMatch("${res.title} ${res.subject}")
                queryTokens.count { token -> resNorm.contains(token) }
            }
            if (best != null) {
                val resNorm = normalizeForMatch("${best.title} ${best.subject}")
                val matchCount = queryTokens.count { token -> resNorm.contains(token) }
                if (matchCount >= 1 && (matchCount >= queryTokens.size / 2 + 1 || queryTokens.size == 1)) {
                    return best
                }
            }
        }
        return null
    }

    private fun findMatchingTarget(
        targets: List<TargetEntity>,
        queryTitle: String
    ): TargetEntity? {
        if (targets.isEmpty()) return null
        val normQuery = normalizeForMatch(queryTitle)
        if (normQuery.isBlank()) return targets.firstOrNull()

        targets.firstOrNull { normalizeForMatch(it.title) == normQuery }?.let { return it }
        targets.firstOrNull {
            val normT = normalizeForMatch(it.title)
            normT.contains(normQuery) || normQuery.contains(normT)
        }?.let { return it }

        val queryTokens = normQuery.split(" ").filter { it.length >= 2 }
        if (queryTokens.isNotEmpty()) {
            val best = targets.maxByOrNull { t ->
                val tNorm = normalizeForMatch("${t.title} ${t.category}")
                queryTokens.count { token -> tNorm.contains(token) }
            }
            if (best != null) {
                val tNorm = normalizeForMatch("${best.title} ${best.category}")
                if (queryTokens.count { token -> tNorm.contains(token) } >= 1) {
                    return best
                }
            }
        }
        return null
    }

    private fun extractBalancedJson(raw: String): String? {
        val text = raw
            .replace(Regex("```(?:json)?\\s*"), "")
            .replace("```", "")
            .trim()

        var depth = 0
        var start = -1

        for (i in text.indices) {
            when (text[i]) {
                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth == 0 && start != -1) {
                        val candidate = text.substring(start, i + 1)
                        if (candidate.contains("\"schedule\"") || candidate.contains("\"tasks\"") || candidate.contains("CREATE_WEEKLY_PLAN") || candidate.contains("CREATE_PLAN")) return candidate
                        start = -1
                    }
                }
            }
        }
        return null
    }

    private suspend fun handleAiResponse(
        rawResponse: String,
        toolPlanFolderId: String? = null,
        streamingMessageId: String? = null
    ) {
        val actionResult = processActionAndRecordMemory(_uiState.value.currentPersona, rawResponse)
        var finalCleanText = actionResult.cleanText
        var planFolderId = actionResult.createdPlanFolderId ?: toolPlanFolderId
        var isPlanCard = actionResult.isPlanCard

        // Fallback: If CREATE_WEEKLY_PLAN was output as raw JSON instead of action tag
        if (planFolderId == null) {
            val jsonCandidate = extractBalancedJson(rawResponse)
            if (jsonCandidate != null) {
                try {
                    val root = JSONObject(jsonCandidate)
                    val summary = root.optString("weeklyFocusSummary", root.optString("summary", "Haftalık Çalışma Planı")).trim()
                        .ifBlank { "Haftalık Çalışma Planı" }
                    val scheduleArray = root.optJSONArray("schedule")
                        ?: root.optJSONArray("tasks")
                        ?: root.optJSONArray("plan")
                        ?: root.optJSONArray("days")

                    if (scheduleArray != null && scheduleArray.length() > 0) {
                        val parsedTasks = mutableListOf<PlanEntity>()
                        for (i in 0 until scheduleArray.length()) {
                            val item = scheduleArray.getJSONObject(i)
                            val subject = item.optString("subject", item.optString("course", "Ders")).ifBlank { "Ders" }
                            val topic = item.optString("topic", item.optString("title", "")).trim()
                            val timeSlot = item.optString(
                                "timeSlot",
                                item.optString("time", item.optString("scheduledTime", "09:00 - 10:30"))
                            ).ifBlank { "09:00 - 10:30" }
                            val rawQ = item.optInt("targetQuestions", item.optInt("questions", 30))

                            parsedTasks.add(
                                PlanEntity(
                                    id = UUID.randomUUID().toString(),
                                    folderId = "",
                                    title = topic.ifBlank { subject },
                                    subject = subject,
                                    topic = topic,
                                    dayOfWeek = item.optInt("dayOfWeek", item.optInt("day", (i % 7 + 1))).coerceIn(1, 7),
                                    scheduledTime = timeSlot,
                                    targetQuestions = if (rawQ > 0) rawQ else 30,
                                    orderIndex = i
                                )
                            )
                        }

                        val newFolderId = withContext(Dispatchers.IO) {
                            val savedFolderId = planRepositoryImpl.saveGeneratedPlan(
                                folderTitle = if (summary.length > 38) summary.take(38) + "…" else summary,
                                tasks = parsedTasks
                            )

                            val dayNames = listOf("Pazartesi", "Salı", "Çarşamba", "Perşembe", "Cuma", "Cumartesi", "Pazar")
                            val activePlan = ActivePlanEntity(
                                id = "CURRENT_ACTIVE_PLAN",
                                title = summary,
                                strategyNote = summary,
                                totalTargetQuestions = parsedTasks.sumOf { it.targetQuestions },
                                updatedAtTimestamp = System.currentTimeMillis()
                            )
                            val studyTasks = parsedTasks.mapIndexed { idx, p ->
                                val dName = dayNames.getOrElse(p.dayOfWeek - 1) { "Pazartesi" }
                                StudyTaskEntity(
                                    localId = 0,
                                    planId = "CURRENT_ACTIVE_PLAN",
                                    dayName = dName,
                                    dayIndex = (p.dayOfWeek - 1).coerceIn(0, 6),
                                    time = p.scheduledTime,
                                    subject = p.subject,
                                    topic = p.topic.ifBlank { p.subject },
                                    goal = "${p.targetQuestions} Soru Hedefi",
                                    isCompleted = false
                                )
                            }
                            database.studyPlanDao().replaceActivePlanWithTasks(activePlan, studyTasks)

                            runCatching {
                                val firestore = GoogleServicesHelper.getFirebaseFirestore(getApplication())
                                val uid = GoogleServicesHelper.getCurrentUser(context = getApplication())?.uid
                                if (uid != null && firestore != null) {
                                    val planMap = hashMapOf<String, Any>(
                                        "id" to "CURRENT_ACTIVE_PLAN",
                                        "title" to summary,
                                        "strategyNote" to summary,
                                        "totalTargetQuestions" to activePlan.totalTargetQuestions,
                                        "updatedAtTimestamp" to activePlan.updatedAtTimestamp,
                                        "userId" to uid,
                                        "studentId" to uid,
                                        "tasks" to studyTasks.map { task ->
                                            hashMapOf(
                                                "dayIndex" to task.dayIndex,
                                                "dayName" to task.dayName,
                                                "subject" to task.subject,
                                                "time" to task.time,
                                                "topic" to task.topic,
                                                "goal" to task.goal,
                                                "isCompleted" to task.isCompleted
                                            )
                                        }
                                    )
                                    firestore.collection("students").document(uid).collection("plans").document("CURRENT_ACTIVE_PLAN").set(planMap, SetOptions.merge())
                                    firestore.collection("users").document(uid).collection("plans").document("CURRENT_ACTIVE_PLAN").set(planMap, SetOptions.merge())
                                }
                            }
                            SyncWorker.enqueueImmediateSync(getApplication())
                            savedFolderId
                        }
                        planFolderId = newFolderId
                        isPlanCard = true
                        finalCleanText = if (finalCleanText.isBlank()) summary else finalCleanText
                    }
                } catch (e: Exception) {
                    Log.e("SharkAiViewModel", "JSON parse fallback failed: ${e.message}", e)
                }
            }
        }

        val baseMessages = if (streamingMessageId != null) {
            _uiState.value.messages.filter { it.id != streamingMessageId }
        } else {
            _uiState.value.messages
        }

        val aiMsg = AiChatMessage(
            id = streamingMessageId ?: UUID.randomUUID().toString(),
            sender = "ai",
            text = finalCleanText.ifBlank { "İşlem tamamlandı." },
            personaTag = _uiState.value.currentPersona.id,
            planFolderId = planFolderId,
            isPlanCard = isPlanCard
        )

        _uiState.value = _uiState.value.copy(
            messages = baseMessages + aiMsg,
            isLoading = false
        )
        persistCurrentChatState()
    }

    private fun getSystemPromptForPersona(
        persona: AiPersona,
        modelPower: AiModelPower = AiModelPower.FLASH
    ): String {
        val mandatoryPersonaRules = AiRouterRepository.MANDATORY_PERSONA_RULES
        val depthRule = AiRouterRepository.getDepthDirective(modelPower)

        val actionInstruction = """
            
            AKSİYON VE EYLEM TALİMATLARI (HEM METİN HEM GÖRSEL ANALİZİNDE GEÇERLİDİR):
            - Eğer öğrenci çalışma planı, haftalık program veya ders çalışma çizelgesi istiyorsa mesajının EN SONUNA plan eylemini ekle:
            #ACTION:{"type":"CREATE_WEEKLY_PLAN","title":"Haftalık Çalışma Programı","summary":"Haftalık odak stratejisi","schedule":[{"dayOfWeek":1,"timeSlot":"09:00 - 10:30","subject":"Matematik","topic":"Türev","targetQuestions":40}]}#
            - Eğer öğrenci "TYT tüm konular", "AYT tüm konular" veya bir dersin tüm konularını/müfredatını eklemek istiyorsa ASLA ders başına tek boş klasör açma; INSERT_TOPICS_BATCH kullan:
            #ACTION:{"type":"INSERT_TOPICS_BATCH","exam":"TYT","preset":"TYT_ALL","topics":[{"subject":"TYT Türkçe","topic":"Sözcükte Anlam","targetLevel":5},{"subject":"TYT Matematik","topic":"Temel Kavramlar","targetLevel":5}]}#
            - Eğer öğrenci tek bir konu hedefi eklemek veya seviyesini güncellemek istiyorsa:
            #ACTION:{"type":"INSERT_TOPIC","subject":"Ders","topic":"Konu Adı","currentLevel":1,"targetLevel":5}#
            - Eğer öğrenci mevcut kütüphanesindeki bir kitabı ("X kitabını 45. sayfaya/teste kadar çözdüm işaretle") güncellemek istiyorsa YENİ KİTAP EKLEME, mevcut kitabı UPDATE_RESOURCE_PROGRESS ile güncelle:
            #ACTION:{"type":"UPDATE_RESOURCE_PROGRESS","resourceId":0,"title":"Kitap Adı","completedUnits":45,"unitType":"Sayfa","isIncremental":false}#
            - Eğer öğrenci kütüphanede henüz olmayan YENİ bir kaynak/kitap eklemek istiyorsa:
            #ACTION:{"type":"INSERT_RESOURCE","title":"Kitap Adı","subject":"Ders","totalUnits":40,"completedUnits":0,"unitType":"Test"}#
            - Eğer öğrenci tekil veya toplu hedef belirlemek ya da mevcut hedef ilerlemesini güncellemek istiyorsa:
            #ACTION:{"type":"INSERT_TARGET","title":"Hedef Başlığı","description":"Açıklama","type":"SHORT_TERM","period":"WEEKLY","targetQuestions":100,"priority":"HIGH"}#
            #ACTION:{"type":"INSERT_TARGETS_BATCH","targets":[{"title":"Hedef Başlığı","description":"Açıklama","type":"SHORT_TERM","period":"WEEKLY","targetQuestions":100,"priority":"HIGH"}]}#
            #ACTION:{"type":"UPDATE_TARGET_PROGRESS","targetId":0,"title":"Hedef Başlığı","solvedQuestions":50,"isCompleted":false,"isIncremental":false}#
            - Eğer öğrencinin hedefi yoksa veya hedeflerini güncellemesi gerekiyorsa mesajın en sonuna '#ACTION:NAV_TARGETS' ekle.
            - Eğer konuşma bittiyse ve öğrenciyi çalışmaya teşvik ediyorsan ('Hadi başlayalım', 'Masaya geçelim' vb.) mesajın sonuna '#ACTION:NAV_DESK' ekle.
            - Eğer plan oluşturduysan veya planı incelemesini istiyorsan mesajın sonuna '#ACTION:NAV_PLANS' ekle.
        """.trimIndent()

        val lengthGuide = "Açıklamaları net, anlaşılır ve dengeli tut; kısa selamlamalarda maksimum 1 cümle kur."

        val basePrompt = when (persona) {
            AiPersona.SHARK_COACH -> """
                Sen SharkMentor'un kıdemli sınav koçu ve akran mentörüsün.
                MİSYONUN: Öğrenciye net analizi, stratejik yönlendirme ve odak desteği sağla.
                TONUN: Doğal, net, sıfır laf kalabalığı, analitik ve samimi ($lengthGuide).
                $mandatoryPersonaRules$depthRule
            """.trimIndent()

            AiPersona.GOAL_SETTER -> """
                Sen SharkMentor Hedef Belirleme AI koçusun.
                MİSYONUN: Öğrencinin muğlak isteklerini somut net hedeflerine ve kısa/uzun vadeli sıralama rotalarına dönüştür.
                TONUN: Odaklı, net, sayısal ve aksiyon odaklı ($lengthGuide).
                $mandatoryPersonaRules$depthRule
            """.trimIndent()

            AiPersona.ZEN_CALM -> """
                Sen SharkMentor Empatik Zen Sakinleşme Koçusun.
                MİSYONUN: Sınav kaygısı, stres ve tükenmişlik hisseden öğrenciye psikolojik zemin sağla, nefes porsiyonlaması ver.
                TONUN: Şefkatli, ferahlatıcı, sakin ve içten ($lengthGuide).
                $mandatoryPersonaRules$depthRule
            """.trimIndent()

            AiPersona.DOPAMINE_COACH -> """
                Sen SharkMentor Dopamin Orucu ve Nöral Odak Koçusun.
                MİSYONUN: Öğrencinin sosyal medya, erteleme ve uyarım dürtülerini yönetmesine yardımcı ol, irade sürtünmesini azalt.
                TONUN: Kararlı, nörolojik gerçeklere dayalı ve net ($lengthGuide).
                $mandatoryPersonaRules$depthRule
            """.trimIndent()

            AiPersona.STUDY_PLANNER -> """
                Sen SharkMentor Otonom Çalışma Planlayıcısısın.
                GÖREV: Öğrencinin talebine dayanarak 7 günlük (Pazartesi-Pazar) operasyonel çalışma planı hazırla.
                1. Önce öğrenciye 2-3 samimi cümleyle bu haftaki çalışma stratejisini, eksiklerini nasıl toparlayacağını açıkla.
                2. Ardından mesajın en sonuna çalışma programını aşağıdaki sistem eylem formatında ekle:
                #ACTION:{"type":"CREATE_WEEKLY_PLAN","title":"Haftalık Çalışma Programı","summary":"Bu haftaki stratejik yönelim özeti","schedule":[{"dayOfWeek":1,"timeSlot":"09:00 - 10:30","subject":"Matematik","topic":"Türev","targetQuestions":40,"focusStrategy":"Konu Pekiştirme"}]}#
                $mandatoryPersonaRules$depthRule
            """.trimIndent()
        }

        return basePrompt + actionInstruction
    }

    /**
     * Shark AI'a atılan soru görselini/belgesini arka planda (Dispatchers.IO)
     * asenkron ve sıfır gecikmeyle (UI veya AI modellerini hiç yavaşlatmadan)
     * Antrenman Hata Bankasına (ErrorQuestionEntity) kaydeder.
     */
    private fun autoSaveQuestionToErrorBank(
        attachmentUri: Uri,
        resolvedAttachmentName: String?,
        userPrompt: String,
        aiResponseText: String
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>().applicationContext
                val resolver = context.contentResolver
                val mimeType = resolver.getType(attachmentUri) ?: ""
                val isImage = mimeType.startsWith("image/") ||
                    resolvedAttachmentName?.endsWith(".jpg", ignoreCase = true) == true ||
                    resolvedAttachmentName?.endsWith(".jpeg", ignoreCase = true) == true ||
                    resolvedAttachmentName?.endsWith(".png", ignoreCase = true) == true ||
                    resolvedAttachmentName?.endsWith(".webp", ignoreCase = true) == true
                val isPdf = mimeType == "application/pdf" || resolvedAttachmentName?.endsWith(".pdf", ignoreCase = true) == true

                if (!isImage && !isPdf) return@launch

                // 1. Resmi optimize ederek kalıcı dahili depolamaya kaydet
                val savedImagePath = if (isPdf) {
                    val bitmaps = PdfBitmapConverter.convertPdfUriToBitmaps(context, attachmentUri, maxPages = 1)
                    val firstPage = bitmaps.firstOrNull()
                    if (firstPage != null) {
                        val file = File(context.filesDir, "shark_ai_q_${System.currentTimeMillis()}.jpg")
                        file.outputStream().use { out ->
                            firstPage.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
                        }
                        file.absolutePath
                    } else null
                } else {
                    ImageStorageManager.processAndSaveImage(context, attachmentUri, isQuestion = true)
                }

                if (savedImagePath.isNullOrBlank()) return@launch

                // 2. İçeriğin gerçekten bir soru / sınav sorusu olduğunu doğrula (alakasız sıradan görselleri engelle)
                val combinedText = "$userPrompt $resolvedAttachmentName $aiResponseText".lowercase(java.util.Locale("tr"))
                val hasQuestionSignal = combinedText.contains("soru") ||
                    combinedText.contains("çözüm") ||
                    combinedText.contains("cozum") ||
                    combinedText.contains("doğru cevap") ||
                    combinedText.contains("dogru cevap") ||
                    combinedText.contains("cevap:") ||
                    combinedText.contains("şık") ||
                    combinedText.contains("seçenek") ||
                    combinedText.contains("adım 1") ||
                    combinedText.contains("1. adım") ||
                    combinedText.contains("denklem") ||
                    Regex("""\b[a-eA-E]\)""").containsMatchIn(aiResponseText) ||
                    Regex("""\b[a-eA-E]\s*şıkkı""").containsMatchIn(aiResponseText)

                if (!hasQuestionSignal) {
                    Log.d("SharkAiAutoSave", "Görsel bir soru çözümü niteliği taşımıyor, Antrenman Bankasına kaydedilmedi.")
                    return@launch
                }

                // 3. Metin analiziyle Ders ve Konuyu tespit et
                val detectedSubject = detectSubjectFromContext(combinedText)
                val detectedTopic = detectTopicFromContext(combinedText, detectedSubject)

                // 4. Room veritabanına ekle
                val db = SharkMentorDatabase.getDatabase(context)
                val institutionId = com.example.sharkmentor.data.local.preferences.UserPreferences.getInstance(context).institutionId
                val entity = ErrorQuestionEntity(
                    subject = detectedSubject,
                    questionImageUri = savedImagePath,
                    solutionImageUri = null,
                    topic = detectedTopic,
                    aiTopic = detectedTopic,
                    dueDateMillis = System.currentTimeMillis(),
                    institutionId = institutionId,
                    isSynced = false
                )
                db.errorQuestionDao().insertQuestion(entity)

                // 5. Arka plan senkronizasyonu
                SyncWorker.enqueueImmediateSync(context)
                if (detectedTopic == "Shark AI Soruları" || detectedTopic == "Genel") {
                    TopicTaggingWorker.enqueue(context)
                }
                Log.d("SharkAiAutoSave", "Soru arka planda Antrenman Bankasına eklendi: Ders=$detectedSubject, Konu=$detectedTopic")
            } catch (e: Exception) {
                Log.w("SharkAiAutoSave", "Soru Antrenman Bankasına kaydedilirken arka plan hatası: ${e.message}")
            }
        }
    }

    private fun detectSubjectFromContext(text: String): String {
        return when {
            text.contains("geometri") || text.contains("üçgen") || text.contains("çember") || text.contains("dörtgen") || text.contains("analitik") -> "Geometri"
            text.contains("matematik") || text.contains("türev") || text.contains("integral") || text.contains("fonksiyon") || text.contains("polinom") || text.contains("logaritma") || text.contains("olasılık") || text.contains("trigonometri") || text.contains("denklem") -> "Matematik"
            text.contains("fizik") || text.contains("kuvvet") || text.contains("dinamik") || text.contains("elektrik") || text.contains("manyetizma") || text.contains("optik") || text.contains("dalga") || text.contains("termodinamik") || text.contains("vektör") -> "Fizik"
            text.contains("kimya") || text.contains("periyodik") || text.contains("organik") || text.contains("asit") || text.contains("gazlar") || text.contains("mol") || text.contains("çözelti") || text.contains("tepkime") -> "Kimya"
            text.contains("biyoloji") || text.contains("hücre") || text.contains("dna") || text.contains("genetik") || text.contains("fotosentez") || text.contains("ekosistem") || text.contains("sistemler") -> "Biyoloji"
            text.contains("tarih") || text.contains("osmanlı") || text.contains("inkılap") || text.contains("selçuklu") || text.contains("savaş") -> "Tarih"
            text.contains("coğrafya") || text.contains("harita") || text.contains("iklim") || text.contains("nüfus") || text.contains("yerşekilleri") -> "Coğrafya"
            text.contains("türkçe") || text.contains("edebiyat") || text.contains("paragraf") || text.contains("dil bilgisi") || text.contains("yazım") || text.contains("noktalama") || text.contains("şiir") || text.contains("divan") || text.contains("tanzimat") || text.contains("yazar") -> "Türkçe / Edebiyat"
            text.contains("felsefe") || text.contains("din") -> "Felsefe / Din"
            else -> "Matematik"
        }
    }

    private fun detectTopicFromContext(text: String, subject: String): String {
        return when (subject) {
            "Matematik" -> when {
                text.contains("türev") -> "Türev"
                text.contains("integral") -> "İntegral"
                text.contains("limit") -> "Limit ve Süreklilik"
                text.contains("trigonometri") -> "Trigonometri"
                text.contains("logaritma") -> "Logaritma"
                text.contains("dizi") || text.contains("seri") -> "Diziler"
                text.contains("polinom") -> "Polinomlar"
                text.contains("fonksiyon") -> "Fonksiyonlar"
                text.contains("olasılık") || text.contains("kombinasyon") || text.contains("permütasyon") -> "Olasılık ve Kombinasyon"
                text.contains("parabol") -> "Parabol"
                text.contains("eşitsizlik") -> "Eşitsizlikler"
                text.contains("üslü") || text.contains("köklü") -> "Üslü ve Köklü Sayılar"
                text.contains("çarpanlara ayırma") -> "Çarpanlara Ayırma"
                text.contains("küme") -> "Kümeler"
                text.contains("problem") -> "Problemler"
                text.contains("temel kavram") || text.contains("sayı") -> "Temel Kavramlar"
                else -> "Shark AI Soruları"
            }
            "Geometri" -> when {
                text.contains("üçgen") -> "Üçgenler"
                text.contains("çember") || text.contains("daire") -> "Çember ve Daire"
                text.contains("dörtgen") || text.contains("kare") || text.contains("dikdörtgen") -> "Dörtgenler"
                text.contains("analitik") -> "Analitik Geometri"
                text.contains("katı cisim") || text.contains("prizma") || text.contains("koni") -> "Katı Cisimler"
                else -> "Shark AI Soruları"
            }
            "Fizik" -> when {
                text.contains("kuvvet") || text.contains("hareket") || text.contains("newton") -> "Kuvvet ve Hareket"
                text.contains("elektrik") || text.contains("manyetizma") || text.contains("akım") -> "Elektrik ve Manyetizma"
                text.contains("optik") || text.contains("mercek") || text.contains("ayna") -> "Optik"
                text.contains("dalga") -> "Dalgalar"
                text.contains("iş") || text.contains("güç") || text.contains("enerji") -> "İş, Güç ve Enerji"
                text.contains("termodinamik") || text.contains("ısı") || text.contains("sıcaklık") -> "Termodinamik"
                text.contains("basınç") || text.contains("kaldırma") -> "Basınç ve Kaldırma Kuvveti"
                text.contains("modern fizik") || text.contains("radyoaktivite") -> "Modern Fizik"
                else -> "Shark AI Soruları"
            }
            "Kimya" -> when {
                text.contains("organik") -> "Organik Kimya"
                text.contains("periyodik") || text.contains("atom") -> "Atom ve Periyodik Sistem"
                text.contains("gaz") -> "Gazlar"
                text.contains("sıvı çözelti") || text.contains("çözelti") || text.contains("derişim") -> "Sıvı Çözeltiler"
                text.contains("kimyasal hesaplama") || text.contains("mol") -> "Mol ve Hesaplamalar"
                text.contains("termodinamik") || text.contains("entalpi") || text.contains("enerji") -> "Kimyasal Tepkimelerde Enerji"
                text.contains("hız") -> "Tepkime Hızları"
                text.contains("denge") -> "Kimyasal Denge"
                text.contains("asit") || text.contains("baz") -> "Asitler ve Bazlar"
                text.contains("elektrokimya") || text.contains("redoks") || text.contains("pil") -> "Elektrokimya"
                else -> "Shark AI Soruları"
            }
            "Biyoloji" -> when {
                text.contains("hücre") -> "Hücre ve Organeller"
                text.contains("dna") || text.contains("rna") || text.contains("genetik") -> "Genetik ve Nükleik Asitler"
                text.contains("fotosentez") || text.contains("kemosentez") || text.contains("solunum") -> "Hücresel Solunum ve Fotosentez"
                text.contains("sinir") || text.contains("endokrin") || text.contains("duyu") -> "Denetleyici ve Düzenleyici Sistemler"
                text.contains("dolaşım") || text.contains("bağışıklık") -> "Dolaşım ve Bağışıklık Sistemi"
                text.contains("sindirim") -> "Sindirim Sistemi"
                text.contains("boşaltım") -> "Boşaltım Sistemi"
                text.contains("destek") || text.contains("hareket") -> "Destek ve Hareket Sistemi"
                text.contains("ekosistem") || text.contains("çevre") -> "Ekosistem Ekolojisi"
                text.contains("kalıtım") -> "Kalıtım"
                else -> "Shark AI Soruları"
            }
            "Türkçe / Edebiyat", "Türkçe", "Edebiyat" -> when {
                text.contains("paragraf") -> "Paragrafta Anlam"
                text.contains("cümle") -> "Cümlede Anlam"
                text.contains("sözcük") -> "Sözcükte Anlam"
                text.contains("yazım") -> "Yazım Kuralları"
                text.contains("noktalama") -> "Noktalama İşaretleri"
                text.contains("ses bilgisi") -> "Ses Bilgisi"
                text.contains("fiil") || text.contains("eylem") -> "Fiiller ve Ek Fiil"
                text.contains("öge") -> "Cümlenin Ögeleri"
                text.contains("anlatım bozukluğu") -> "Anlatım Bozuklukları"
                text.contains("divan") -> "Divan Edebiyatı"
                text.contains("tanzimat") -> "Tanzimat Edebiyatı"
                text.contains("halk") -> "Halk Edebiyatı"
                text.contains("cumhuriyet") -> "Cumhuriyet Dönemi Edebiyatı"
                text.contains("servetifünun") || text.contains("fecriati") -> "Servetifünun ve Fecriati"
                text.contains("şiir") -> "Şiir Bilgisi"
                else -> "Shark AI Soruları"
            }
            "Tarih" -> when {
                text.contains("osmanlı") -> "Osmanlı Tarihi"
                text.contains("inkılap") || text.contains("atatürk") || text.contains("kurtuluş savaşı") -> "İnkılap Tarihi ve Atatürkçülük"
                text.contains("ilk türk") || text.contains("orta asya") -> "İlk Türk Devletleri"
                text.contains("islam") -> "Türk-İslam Tarihi"
                text.contains("dünya savaşı") -> "20. Yüzyıl ve Dünya Savaşları"
                else -> "Shark AI Soruları"
            }
            "Coğrafya" -> when {
                text.contains("iklim") -> "İklim Bilgisi"
                text.contains("harita") -> "Harita Bilgisi"
                text.contains("nüfus") || text.contains("yerleşme") -> "Nüfus ve Yerleşme"
                text.contains("yer şekilleri") || text.contains("iç kuvvetler") || text.contains("dış kuvvetler") -> "Yer Şekilleri ve Kuvvetler"
                text.contains("ekonomik") || text.contains("tarım") || text.contains("sanayi") -> "Ekonomik Coğrafya"
                else -> "Shark AI Soruları"
            }
            "Felsefe / Din", "Felsefe", "Din" -> when {
                text.contains("din") || text.contains("inanç") || text.contains("ibadet") || text.contains("kuran") -> "Din Kültürü ve Ahlak Bilgisi"
                text.contains("bilgi felsefesi") || text.contains("epistemoloji") -> "Bilgi Felsefesi"
                text.contains("varlık felsefesi") || text.contains("ontoloji") -> "Varlık Felsefesi"
                text.contains("ahlak felsefesi") || text.contains("etik") -> "Ahlak Felsefesi"
                text.contains("siyaset felsefesi") -> "Siyaset Felsefesi"
                else -> "Felsefe ve Din"
            }
            else -> "Shark AI Soruları"
        }
    }
}
