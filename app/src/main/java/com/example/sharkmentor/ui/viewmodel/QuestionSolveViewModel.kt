package com.example.sharkmentor.ui.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sharkmentor.data.repository.AiRouterRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * 105 dakikalık soru çözüm penceresi için kademeli ödül dilimleri:
 * - 0 - 15 dk: Altın %100 (+100 Puan & +15 dk Bonus)
 * - 15 - 60 dk: Gümüş %70 (+70 Puan & +10 dk Bonus)
 * - 60 - 105 dk: Bronz %40 (+40 Puan & +6 dk Bonus)
 * - > 105 dk: Süre Doldu (%0 Ödül)
 */
enum class SolveRewardTier(
    val badgeText: String,
    val shortLabel: String,
    val timeRangeLabel: String,
    val points: Int,
    val bonusMinutes: Int,
    val percentage: Int,
    val colorHex: Long
) {
    GOLD_100(
        badgeText = "Altın %100: +100 Puan & +15 dk",
        shortLabel = "Altın %100",
        timeRangeLabel = "0-15 dk",
        points = 100,
        bonusMinutes = 15,
        percentage = 100,
        colorHex = 0xFFF59E0B
    ),
    SILVER_70(
        badgeText = "Gümüş %70: +70 Puan & +10 dk",
        shortLabel = "Gümüş %70",
        timeRangeLabel = "15-60 dk",
        points = 70,
        bonusMinutes = 10,
        percentage = 70,
        colorHex = 0xFF94A3B8
    ),
    BRONZE_40(
        badgeText = "Bronz %40: +40 Puan & +6 dk",
        shortLabel = "Bronz %40",
        timeRangeLabel = "60-105 dk",
        points = 40,
        bonusMinutes = 6,
        percentage = 40,
        colorHex = 0xFFD97706
    ),
    EXPIRED_0(
        badgeText = "Süre Doldu (%0 Ödül)",
        shortLabel = "Süre Doldu",
        timeRangeLabel = "105+ dk",
        points = 0,
        bonusMinutes = 0,
        percentage = 0,
        colorHex = 0xFFEF4444
    )
}

/**
 * Soru Çözüm Ekranındaki 4 ana araç sekmesi:
 * 1. PEN: Kalem (basınç hassasiyetli çizim)
 * 2. TEXT_BOX: Metin Kutusu (en az 25 karakter kuralı)
 * 3. CAMERA_GALLERY: Kamera / Galeri (kağıda çözülen fotoğrafı seçme)
 * 4. SHARK_AI: Shark AI (AI çözüm kilidi onay diyaloğu)
 */
enum class SolveWorkspaceTool(val label: String, val emoji: String = "") {
    PEN("Kalem", ""),
    TEXT_BOX("Metin Kutusu", ""),
    CAMERA_GALLERY("Kamera / Galeri", ""),
    SHARK_AI("Shark AI", "")
}

/**
 * Lig Sohbeti Soru Çözüm Ekranı (QuestionSolveScreen) için ViewModel.
 * 105 dakikalık canlı geri sayım sayacını, kademeli ödül hesaplamasını,
 * 4 araç durumunu (Kalem, Metin Kutusu >=25 karakter, Kamera/Galeri, Shark AI Kilidi) yönetir.
 */
class QuestionSolveViewModel : ViewModel() {

    companion object {
        const val TOTAL_SOLVE_WINDOW_MINUTES = 105L
        const val TOTAL_SOLVE_WINDOW_MILLIS = TOTAL_SOLVE_WINDOW_MINUTES * 60_000L
        const val MIN_EXPLANATION_LENGTH = 25

        private const val GOLD_MAX_ELAPSED_MILLIS = 15L * 60_000L
        private const val SILVER_MAX_ELAPSED_MILLIS = 60L * 60_000L
        private const val BRONZE_MAX_ELAPSED_MILLIS = 105L * 60_000L

        fun calculateTier(
            sharedAtMillis: Long,
            expiresAtMillis: Long = sharedAtMillis + TOTAL_SOLVE_WINDOW_MILLIS,
            nowMillis: Long = System.currentTimeMillis()
        ): SolveRewardTier {
            if (nowMillis >= expiresAtMillis) {
                return SolveRewardTier.EXPIRED_0
            }
            val elapsedMillis = (nowMillis - sharedAtMillis).coerceAtLeast(0L)
            return when {
                elapsedMillis <= GOLD_MAX_ELAPSED_MILLIS -> SolveRewardTier.GOLD_100
                elapsedMillis <= SILVER_MAX_ELAPSED_MILLIS -> SolveRewardTier.SILVER_70
                elapsedMillis < BRONZE_MAX_ELAPSED_MILLIS -> SolveRewardTier.BRONZE_40
                else -> SolveRewardTier.EXPIRED_0
            }
        }

        fun formatRemainingTime(totalSeconds: Long): String {
            val clamped = totalSeconds.coerceAtLeast(0L)
            val minutes = clamped / 60L
            val seconds = clamped % 60L
            return "%02d:%02d".format(minutes, seconds)
        }
    }

    private val _questionId = MutableStateFlow("")
    val questionId: StateFlow<String> = _questionId.asStateFlow()

    private val _subject = MutableStateFlow("")
    val subject: StateFlow<String> = _subject.asStateFlow()

    private val _topic = MutableStateFlow("")
    val topic: StateFlow<String> = _topic.asStateFlow()

    private val _imageUrl = MutableStateFlow("")
    val imageUrl: StateFlow<String> = _imageUrl.asStateFlow()

    private val _sharedAtMillis = MutableStateFlow(0L)
    val sharedAtMillis: StateFlow<Long> = _sharedAtMillis.asStateFlow()

    private val _expiresAtMillis = MutableStateFlow(0L)
    val expiresAtMillis: StateFlow<Long> = _expiresAtMillis.asStateFlow()

    private val _remainingSeconds = MutableStateFlow(TOTAL_SOLVE_WINDOW_MINUTES * 60L)
    val remainingSeconds: StateFlow<Long> = _remainingSeconds.asStateFlow()

    private val _elapsedSeconds = MutableStateFlow(0L)
    val elapsedSeconds: StateFlow<Long> = _elapsedSeconds.asStateFlow()

    private val _currentTier = MutableStateFlow(SolveRewardTier.GOLD_100)
    val currentTier: StateFlow<SolveRewardTier> = _currentTier.asStateFlow()

    private val _activeTool = MutableStateFlow(SolveWorkspaceTool.PEN)
    val activeTool: StateFlow<SolveWorkspaceTool> = _activeTool.asStateFlow()

    private val _explanationText = MutableStateFlow("")
    val explanationText: StateFlow<String> = _explanationText.asStateFlow()

    private val _paperSolutionBitmap = MutableStateFlow<Bitmap?>(null)
    val paperSolutionBitmap: StateFlow<Bitmap?> = _paperSolutionBitmap.asStateFlow()

    private val _isAiLocked = MutableStateFlow(false)
    val isAiLocked: StateFlow<Boolean> = _isAiLocked.asStateFlow()

    private val _aiSolutionText = MutableStateFlow("")
    val aiSolutionText: StateFlow<String> = _aiSolutionText.asStateFlow()

    private val _isAiLoading = MutableStateFlow(false)
    val isAiLoading: StateFlow<Boolean> = _isAiLoading.asStateFlow()

    private val _aiError = MutableStateFlow<String?>(null)
    val aiError: StateFlow<String?> = _aiError.asStateFlow()

    private val _isSubmitting = MutableStateFlow(false)
    val isSubmitting: StateFlow<Boolean> = _isSubmitting.asStateFlow()

    private val _submitError = MutableStateFlow<String?>(null)
    val submitError: StateFlow<String?> = _submitError.asStateFlow()

    private val _isSubmitted = MutableStateFlow(false)
    val isSubmitted: StateFlow<Boolean> = _isSubmitted.asStateFlow()

    private var countdownJob: Job? = null
    private var aiJob: Job? = null

    fun initializeQuestion(
        questionId: String,
        subject: String,
        topic: String,
        imageUrl: String,
        sharedAtMillis: Long,
        expiresAtMillis: Long = sharedAtMillis + TOTAL_SOLVE_WINDOW_MILLIS,
        initialAiLocked: Boolean = false
    ) {
        val effectiveSharedAt = if (sharedAtMillis > 0L) sharedAtMillis else System.currentTimeMillis()
        val effectiveExpiresAt = if (expiresAtMillis > effectiveSharedAt) {
            expiresAtMillis
        } else {
            effectiveSharedAt + TOTAL_SOLVE_WINDOW_MILLIS
        }

        val isDifferentQuestion = _questionId.value != questionId
        val isSameQuestion = !isDifferentQuestion &&
            _sharedAtMillis.value == effectiveSharedAt &&
            _expiresAtMillis.value == effectiveExpiresAt &&
            countdownJob?.isActive == true

        if (isDifferentQuestion) {
            aiJob?.cancel()
            _explanationText.value = ""
            _paperSolutionBitmap.value = null
            _activeTool.value = SolveWorkspaceTool.PEN
            _isAiLocked.value = initialAiLocked
            _aiSolutionText.value = ""
            _isAiLoading.value = false
            _aiError.value = null
            _isSubmitting.value = false
            _submitError.value = null
            _isSubmitted.value = false
        } else if (initialAiLocked) {
            _isAiLocked.value = true
        }

        _questionId.value = questionId
        _subject.value = subject
        _topic.value = topic
        _imageUrl.value = imageUrl
        _sharedAtMillis.value = effectiveSharedAt
        _expiresAtMillis.value = effectiveExpiresAt

        if (!isSameQuestion) {
            startCountdown(effectiveSharedAt, effectiveExpiresAt)
        }
    }

    private fun startCountdown(sharedAtMillis: Long, expiresAtMillis: Long) {
        countdownJob?.cancel()
        updateTimerSnapshot(sharedAtMillis, expiresAtMillis)
        countdownJob = viewModelScope.launch {
            while (isActive) {
                updateTimerSnapshot(sharedAtMillis, expiresAtMillis)
                if (_remainingSeconds.value <= 0L) {
                    break
                }
                delay(1_000L)
            }
        }
    }

    private fun updateTimerSnapshot(sharedAtMillis: Long, expiresAtMillis: Long) {
        val now = System.currentTimeMillis()
        val remainingMillis = (expiresAtMillis - now).coerceAtLeast(0L)
        val elapsedMillis = (now - sharedAtMillis).coerceAtLeast(0L)
        _remainingSeconds.value = (remainingMillis + 999L) / 1_000L
        _elapsedSeconds.value = elapsedMillis / 1_000L
        _currentTier.value = calculateTier(
            sharedAtMillis = sharedAtMillis,
            expiresAtMillis = expiresAtMillis,
            nowMillis = now
        )
    }

    fun selectTool(tool: SolveWorkspaceTool) {
        _activeTool.value = tool
    }

    fun updateExplanation(text: String) {
        _explanationText.value = text
        if (_submitError.value != null) {
            _submitError.value = null
        }
    }

    fun setPaperSolutionBitmap(bitmap: Bitmap?) {
        _paperSolutionBitmap.value = bitmap
        if (bitmap != null && _submitError.value != null) {
            _submitError.value = null
        }
    }

    fun confirmAiLock() {
        _isAiLocked.value = true
        _submitError.value = null
    }

    fun requestSharkAiSolution(context: Context, questionNote: String = "", forceReload: Boolean = false) {
        if (!forceReload && (_isAiLoading.value || _aiSolutionText.value.isNotBlank())) {
            return
        }
        aiJob?.cancel()
        _isAiLoading.value = true
        _aiError.value = null
        if (forceReload) {
            _aiSolutionText.value = ""
        }

        val currentImg = _imageUrl.value.trim()
        val attachmentUri: Uri? = when {
            currentImg.isBlank() -> null
            currentImg.startsWith("/") -> Uri.fromFile(File(currentImg))
            else -> runCatching { Uri.parse(currentImg) }.getOrNull()
        }

        val subj = _subject.value.ifBlank { "Ders" }
        val tpc = _topic.value.ifBlank { "Genel" }
        val prompt = buildString {
            append("Bu $subj ($tpc) sorusunu görseldeki verileri eksiksiz okuyarak adım adım çöz ve doğru cevabı net biçimde belirt.")
            if (questionNote.isNotBlank()) {
                append("\nÖğrenci notu: $questionNote")
            }
        }

        aiJob = viewModelScope.launch {
            val router = AiRouterRepository(context.applicationContext)
            val result = router.executeSolverEngine(
                userPrompt = prompt,
                contextInstruction = "Ders: $subj | Konu: $tpc",
                attachmentUri = attachmentUri,
                onPartialText = { partial ->
                    _aiSolutionText.value = partial
                }
            )
            result.onSuccess { res ->
                if (res.text.isNotBlank()) {
                    _aiSolutionText.value = res.text
                }
                _isAiLoading.value = false
            }.onFailure { err ->
                _isAiLoading.value = false
                _aiError.value = err.localizedMessage ?: "Shark AI çözümü oluşturulamadı."
            }
        }
    }

    fun setError(message: String?) {
        _submitError.value = message
    }

    fun clearError() {
        _submitError.value = null
    }

    fun markSubmitting(submitting: Boolean) {
        _isSubmitting.value = submitting
    }

    fun markSubmitted() {
        _isSubmitting.value = false
        _isSubmitted.value = true
    }

    override fun onCleared() {
        countdownJob?.cancel()
        aiJob?.cancel()
        super.onCleared()
    }
}
