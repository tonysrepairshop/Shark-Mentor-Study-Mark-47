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
import com.example.sharkmentor.ui.aichat.AiChatHubUiState
import com.example.sharkmentor.ui.aichat.AiChatMessage
import com.example.sharkmentor.ui.aichat.AiPersona
import com.example.sharkmentor.ui.aichat.isPersonaAvailable
import com.example.sharkmentor.ui.screens.SelectedAttachmentPreview
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val database = SharkMentorDatabase.getDatabase(application)
    private val aiRouterRepository = AiRouterRepository(application.applicationContext, database)
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

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _selectedAttachmentUri = MutableStateFlow<Uri?>(null)
    val selectedAttachmentUri: StateFlow<Uri?> = _selectedAttachmentUri.asStateFlow()

    private val _attachmentPreview = MutableStateFlow<SelectedAttachmentPreview?>(null)
    val attachmentPreview: StateFlow<SelectedAttachmentPreview?> = _attachmentPreview.asStateFlow()

    private val _modelPower = MutableStateFlow(AiModelPower.FLASH)
    val modelPower: StateFlow<AiModelPower> = _modelPower.asStateFlow()

    private val _activeCapability = MutableStateFlow<SharkAiCapability?>(null)
    val activeCapability: StateFlow<SharkAiCapability?> = _activeCapability.asStateFlow()

    private val _detectedEngine = MutableStateFlow(SharkAiEngine.CONVERSATIONAL_PEER)
    val detectedEngine: StateFlow<SharkAiEngine> = _detectedEngine.asStateFlow()

    private val _lockedEngine = MutableStateFlow<SharkAiEngine?>(null)
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
        AiChatHubUiState(messages = emptyList())
    )
    val uiState: StateFlow<AiChatHubUiState> = _uiState.asStateFlow()

    private var activeAiJob: Job? = null

    init {
        observeStateDependencies()
        institutionSyncListener.start()
        consumePendingHandoffIfAny()
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
        sendMessage(
            userInput = handoff.promptText,
            attachmentUri = handoff.imageUri,
            attachmentName = handoff.imageUri?.lastPathSegment ?: "Hata Kumbarası Sorusu",
            engineOverride = handoff.targetEngine
        )
    }

    fun onInputTextChanged(newText: String) {
        _inputText.value = newText
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
        refreshAutoDetectedEngine()
    }

    fun setModelPower(power: AiModelPower) {
        _modelPower.value = power
    }

    fun setActiveCapability(capability: SharkAiCapability?) {
        _activeCapability.value = capability
        _lockedEngine.value = SharkAiEngine.fromCapability(capability)
    }

    fun setLockedEngine(engine: SharkAiEngine?) {
        _lockedEngine.value = engine
        _activeCapability.value = when (engine) {
            SharkAiEngine.SOLVER_ENGINE -> SharkAiCapability.PROBLEM_SOLVER
            SharkAiEngine.TWIN_QUESTION_ENGINE -> SharkAiCapability.TWIN_QUESTION
            SharkAiEngine.PLANNER_OR_ORGANIZER -> SharkAiCapability.ADD_TO_STUDY_PLAN
            else -> null
        }
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
    }

    fun stopGeneration() {
        activeAiJob?.cancel()
        _uiState.value = _uiState.value.copy(isLoading = false)
    }

    fun deleteMessage(messageId: String) {
        val currentMessages = _uiState.value.messages
        _uiState.value = _uiState.value.copy(
            messages = currentMessages.filter { it.id != messageId }
        )
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
        refreshAutoDetectedEngine()

        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages + userMsg,
            isLoading = true,
            error = null
        )

        val streamingMsgId = UUID.randomUUID().toString()

        activeAiJob?.cancel()
        activeAiJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val memorySnapshot = memoryRepository.buildSemanticSnapshot()
                val userStatsSnapshot = withContext(Dispatchers.IO) {
                    val concrete = database.concreteTargetDao().getTargetRaw()
                    val targetInfo = if (concrete != null) {
                        "${concrete.targetTitle} (${concrete.targetScoreOrRank})"
                    } else "Girilmedi"
                    val activeTargets = database.targetDao().getTargetsWithTasksSync(TargetType.SHORT_TERM)
                    val targetNames = activeTargets.take(3).joinToString { it.target.title }
                    "Net/Sıralama Hedefi: $targetInfo | Aktif Planlar: ${targetNames.ifEmpty { "Yok" }}"
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
                    ${getSystemPromptForPersona(currentPersona)}

                    [SİSTEMİK KİMLİK: SharkMentor Otonom Hub - Motor: ${resolvedEngine.displayName} - Mod: ${currentPersona.title} - Güç: ${activePower.label}]
                    [ÖĞRENCİ ÖZETİ: $userStatsSnapshot]
                    [AKTİF HAFIZA: $memorySnapshot]$attachmentContext
                    $projectionReport$scheduleConstraintPrompt$conversationHistoryPrompt
                    Talimat: Öğrencinin geçmiş kararlarına, son diyalog geçmişine ve hafıza kayıtlarına sadık kal.
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

                val onStreamChunk: (String) -> Unit = { partialText ->
                    // Strip in-progress or completed action tags and markdown json code blocks
                    var cleanPartial = partialText
                        .replace("#ACTION:(\\{.*?\\})#".toRegex(RegexOption.DOT_MATCHES_ALL), "")
                        .replace("#ACTION:.*".toRegex(RegexOption.DOT_MATCHES_ALL), "")
                        .replace("```json[\\s\\S]*?```".toRegex(), "")
                        .replace("```json[\\s\\S]*".toRegex(), "")
                        .replace("```[\\s\\S]*?```".toRegex(), "")
                        .trim()

                    val displayText = if (cleanPartial.isEmpty() && (partialText.contains("\"schedule\"") || partialText.contains("CREATE_WEEKLY_PLAN"))) {
                        "🗓️ 7 Günlük Çalışma Programın hazırlanıyor ve günlere dağıtılıyor..."
                    } else if (cleanPartial.startsWith("{") || cleanPartial.startsWith("[")) {
                        "🗓️ Program ve görevler organize ediliyor..."
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
                // If message has an image/PDF attached -> Route directly to Gemini Vision.
                // If message is text-only (Chat, Socratic Hint, Study Plan) -> Route directly to DeepSeek Stream (with Kimi fallback).
                val routerResult = if (attachmentUri != null) {
                    val visionContext = when (resolvedEngine) {
                        SharkAiEngine.SOLVER_ENGINE ->
                            "${AiRouterRepository.SOLVER_ENGINE_SYSTEM_PROMPT}\n$compressedSystemPrompt"
                        SharkAiEngine.TWIN_QUESTION_ENGINE ->
                            "${AiRouterRepository.TWIN_QUESTION_SYSTEM_PROMPT}\n$compressedSystemPrompt"
                        else ->
                            compressedSystemPrompt
                    }
                    aiRouterRepository.executeGeminiVision(
                        prompt = effectivePrompt,
                        systemInstruction = visionContext,
                        attachmentUri = attachmentUri,
                        activeEngine = resolvedEngine,
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
            } catch (e: Exception) {
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
                } else {
                    val remainingMessages = currentMessages.filter { it.id != streamingMsgId }
                    val errDetail = e.localizedMessage?.takeIf { it.isNotBlank() } ?: e.message ?: "Bağlantı kurulamadı"
                    val errorMsg = AiChatMessage(
                        sender = "ai",
                        text = if (attachmentUri != null) {
                            "Görsel incelenirken bir sorun oluştu: $errDetail. Lütfen tekrar deneyin."
                        } else {
                            "Şu an bağlantı kurulamadı veya yoğunluk var ($errDetail). Lütfen tekrar deneyin."
                        },
                        personaTag = currentPersona.id
                    )
                    _uiState.value = _uiState.value.copy(
                        messages = remainingMessages + errorMsg,
                        isLoading = false
                    )
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
                        val title = actionMap["title"] as? String ?: "Hedef"
                        val desc = actionMap["description"] as? String ?: ""
                        val questions = (actionMap["targetQuestions"] as? Number)?.toInt()
                            ?: (actionMap["questions"] as? Number)?.toInt() ?: 50
                        val rawType = actionMap["type"] as? String ?: "SHORT_TERM"
                        val targetType = if (rawType.contains("LONG", ignoreCase = true)) TargetType.LONG_TERM else TargetType.SHORT_TERM
                        val targetPeriod = if (targetType == TargetType.LONG_TERM) TargetPeriod.MONTHLY else TargetPeriod.WEEKLY
                        val priority = actionMap["priority"] as? String ?: "HIGH"

                        val targetEntity = TargetEntity(
                            title = title,
                            description = desc,
                            type = targetType,
                            period = targetPeriod,
                            deadlineMillis = System.currentTimeMillis() + (7L * 24 * 3600 * 1000),
                            targetQuestions = questions,
                            priority = priority,
                            category = "HEDEF"
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

                    "INSERT_RESOURCE" -> {
                        val title = actionMap["title"] as? String ?: "Kaynak Kitap"
                        val subject = actionMap["subject"] as? String ?: "Genel"
                        val totalUnits = (actionMap["totalUnits"] as? Number)?.toInt() ?: 40
                        val unitType = actionMap["unitType"] as? String ?: "Test"

                        withContext(Dispatchers.IO) {
                            val institutionId = com.example.sharkmentor.data.local.preferences.UserPreferences.getInstance(getApplication()).institutionId ?: "INDIVIDUAL"
                            val resEntity = ResourceEntity(
                                title = title,
                                subject = subject,
                                totalUnits = totalUnits,
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
                                        "totalUnits" to totalUnits,
                                        "currentUnits" to 0,
                                        "completedUnits" to 0,
                                        "unitType" to unitType,
                                        "studentId" to uid,
                                        "userId" to uid,
                                        "createdAt" to System.currentTimeMillis()
                                    )
                                    firestore.collection("students").document(uid).collection("resources").document(finalId).set(resData, SetOptions.merge())
                                    firestore.collection("resources").document(finalId).set(resData, SetOptions.merge())
                                }
                            }
                            SyncWorker.enqueueImmediateSync(getApplication())
                        }
                        memoryRepository.recordInsight(
                            persona = persona,
                            memoryType = "DECISION",
                            content = "Kaynak Eklendi: $title ($subject - $totalUnits $unitType)"
                        )
                        if (!cleanText.contains("#ACTION:NAV_RESOURCES")) {
                            cleanText += "\n\n#ACTION:NAV_RESOURCES"
                        }
                    }

                    "INSERT_TOPIC" -> {
                        val subject = actionMap["subject"] as? String ?: "Genel"
                        val topic = actionMap["topic"] as? String ?: "Konu"
                        val targetLevel = (actionMap["targetLevel"] as? Number)?.toInt() ?: 5

                        withContext(Dispatchers.IO) {
                            val institutionId = com.example.sharkmentor.data.local.preferences.UserPreferences.getInstance(getApplication()).institutionId ?: "INDIVIDUAL"
                            val topicEntity = TopicGoalEntity(
                                subject = subject,
                                topic = topic,
                                currentLevel = 1,
                                targetLevel = targetLevel,
                                institutionId = institutionId,
                                isSynced = false
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
                                        "currentLevel" to 1,
                                        "targetLevel" to targetLevel,
                                        "studentId" to uid,
                                        "userId" to uid,
                                        "updatedAt" to System.currentTimeMillis()
                                    )
                                    firestore.collection("students").document(uid).collection("topics").document(finalId).set(topicData, SetOptions.merge())
                                    firestore.collection("topics").document(finalId).set(topicData, SetOptions.merge())
                                }
                            }
                            SyncWorker.enqueueImmediateSync(getApplication())
                        }
                        memoryRepository.recordInsight(
                            persona = persona,
                            memoryType = "DECISION",
                            content = "Konu Hedefi Eklendi: $subject - $topic"
                        )
                        if (!cleanText.contains("#ACTION:NAV_TOPICS")) {
                            cleanText += "\n\n#ACTION:NAV_TOPICS"
                        }
                    }

                    "UPDATE_TARGET" -> {
                        val title = actionMap["title"] as? String ?: "Net Hedefi"
                        val targetNet = (actionMap["targetNet"] as? Double)?.toInt()
                            ?: (actionMap["targetValue"] as? Double)?.toInt() ?: 30

                        memoryRepository.recordInsight(
                            persona = persona,
                            memoryType = "GOAL_STATE",
                            content = "Hedef Güncellendi: $title ($targetNet Net)"
                        )
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
    }

    private fun getSystemPromptForPersona(persona: AiPersona): String {
        val mandatoryPersonaRules = AiRouterRepository.MANDATORY_PERSONA_RULES

        val actionInstruction = """
            
            AKSİYON VE EYLEM TALİMATLARI:
            - Eğer öğrenci çalışma planı, haftalık program veya ders çalışma çizelgesi istiyorsa mesajının EN SONUNA plan eylemini ekle:
            #ACTION:{"type":"CREATE_WEEKLY_PLAN","title":"Haftalık Çalışma Programı","summary":"Haftalık odak stratejisi","schedule":[{"dayOfWeek":1,"timeSlot":"09:00 - 10:30","subject":"Matematik","topic":"Türev","targetQuestions":40}]}#
            - Eğer öğrenci hedef belirlemek istiyorsa:
            #ACTION:{"type":"INSERT_TARGET","title":"Hedef Başlığı","description":"Açıklama","type":"SHORT_TERM","period":"WEEKLY","targetQuestions":100,"priority":"HIGH"}#
            - Eğer öğrenci kaynak veya kitap eklemek istiyorsa:
            #ACTION:{"type":"INSERT_RESOURCE","title":"Kitap Adı","subject":"Ders","totalUnits":40,"unitType":"Test"}#
            - Eğer öğrenci konu hedefi eklemek istiyorsa:
            #ACTION:{"type":"INSERT_TOPIC","subject":"Ders","topic":"Konu Adı","targetLevel":5}#
            - Eğer öğrencinin hedefi yoksa veya hedeflerini güncellemesi gerekiyorsa mesajın en sonuna '#ACTION:NAV_TARGETS' ekle.
            - Eğer konuşma bittiyse ve öğrenciyi çalışmaya teşvik ediyorsan ('Hadi başlayalım', 'Masaya geçelim' vb.) mesajın sonuna '#ACTION:NAV_DESK' ekle.
            - Eğer plan oluşturduysan veya planı incelemesini istiyorsan mesajın sonuna '#ACTION:NAV_PLANS' ekle.
        """.trimIndent()

        val basePrompt = when (persona) {
            AiPersona.SHARK_COACH -> """
                Sen SharkMentor'un kıdemli sınav koçu ve akran mentörüsün.
                MİSYONUN: Öğrenciye net analizi, stratejik yönlendirme ve odak desteği sağla.
                TONUN: Doğal, net, sıfır laf kalabalığı, analitik ve samimi (Standart açıklamalarda 4-6 kısa cümle, selamlamada maksimum 1 cümle).
                $mandatoryPersonaRules
            """.trimIndent()

            AiPersona.GOAL_SETTER -> """
                Sen SharkMentor Hedef Belirleme AI koçusun.
                MİSYONUN: Öğrencinin muğlak isteklerini somut net hedeflerine ve kısa/uzun vadeli sıralama rotalarına dönüştür.
                TONUN: Odaklı, net, sayısal ve aksiyon odaklı (4-6 kısa cümle).
                $mandatoryPersonaRules
            """.trimIndent()

            AiPersona.ZEN_CALM -> """
                Sen SharkMentor Empatik Zen Sakinleşme Koçusun.
                MİSYONUN: Sınav kaygısı, stres ve tükenmişlik hisseden öğrenciye psikolojik zemin sağla, nefes porsiyonlaması ver.
                TONUN: Şefkatli, ferahlatıcı, sakin ve içten (Maksimum 3 kısa cümle).
                $mandatoryPersonaRules
            """.trimIndent()

            AiPersona.DOPAMINE_COACH -> """
                Sen SharkMentor Dopamin Orucu ve Nöral Odak Koçusun.
                MİSYONUN: Öğrencinin sosyal medya, erteleme ve uyarım dürtülerini yönetmesine yardımcı ol, irade sürtünmesini azalt.
                TONUN: Kararlı, nörolojik gerçeklere dayalı ve net (Maksimum 4-5 kısa cümle).
                $mandatoryPersonaRules
            """.trimIndent()

            AiPersona.STUDY_PLANNER -> """
                Sen SharkMentor Otonom Çalışma Planlayıcısısın.
                GÖREV: Öğrencinin talebine dayanarak 7 günlük (Pazartesi-Pazar) operasyonel çalışma planı hazırla.
                1. Önce öğrenciye 2-3 samimi cümleyle bu haftaki çalışma stratejisini, eksiklerini nasıl toparlayacağını açıkla.
                2. Ardından mesajın en sonuna çalışma programını aşağıdaki sistem eylem formatında ekle:
                #ACTION:{"type":"CREATE_WEEKLY_PLAN","title":"Haftalık Çalışma Programı","summary":"Bu haftaki stratejik yönelim özeti","schedule":[{"dayOfWeek":1,"timeSlot":"09:00 - 10:30","subject":"Matematik","topic":"Türev","targetQuestions":40,"focusStrategy":"Konu Pekiştirme"}]}#
                $mandatoryPersonaRules
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
