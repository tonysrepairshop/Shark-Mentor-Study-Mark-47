package com.example.sharkmentor.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
        badgeText = "⚡ Altın %100: +100 Puan & +15 dk",
        shortLabel = "Altın %100",
        timeRangeLabel = "0-15 dk",
        points = 100,
        bonusMinutes = 15,
        percentage = 100,
        colorHex = 0xFFF59E0B
    ),
    SILVER_70(
        badgeText = "🥈 Gümüş %70: +70 Puan & +10 dk",
        shortLabel = "Gümüş %70",
        timeRangeLabel = "15-60 dk",
        points = 70,
        bonusMinutes = 10,
        percentage = 70,
        colorHex = 0xFF94A3B8
    ),
    BRONZE_40(
        badgeText = "🥉 Bronz %40: +40 Puan & +6 dk",
        shortLabel = "Bronz %40",
        timeRangeLabel = "60-105 dk",
        points = 40,
        bonusMinutes = 6,
        percentage = 40,
        colorHex = 0xFFD97706
    ),
    EXPIRED_0(
        badgeText = "⌛ Süre Doldu (%0 Ödül)",
        shortLabel = "Süre Doldu",
        timeRangeLabel = "105+ dk",
        points = 0,
        bonusMinutes = 0,
        percentage = 0,
        colorHex = 0xFFEF4444
    )
}

/**
 * Lig Sohbeti Soru Çözüm Ekranı (QuestionSolveScreen) için ViewModel.
 * 105 dakikalık canlı geri sayım sayacını, kademeli ödül hesaplamasını ve
 * adım adım çözüm metni durumunu yönetir.
 */
class QuestionSolveViewModel : ViewModel() {

    companion object {
        const val TOTAL_SOLVE_WINDOW_MINUTES = 105L
        const val TOTAL_SOLVE_WINDOW_MILLIS = TOTAL_SOLVE_WINDOW_MINUTES * 60_000L

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

    private val _explanationText = MutableStateFlow("")
    val explanationText: StateFlow<String> = _explanationText.asStateFlow()

    private val _isSubmitting = MutableStateFlow(false)
    val isSubmitting: StateFlow<Boolean> = _isSubmitting.asStateFlow()

    private val _submitError = MutableStateFlow<String?>(null)
    val submitError: StateFlow<String?> = _submitError.asStateFlow()

    private val _isSubmitted = MutableStateFlow(false)
    val isSubmitted: StateFlow<Boolean> = _isSubmitted.asStateFlow()

    private var countdownJob: Job? = null

    fun initializeQuestion(
        questionId: String,
        subject: String,
        topic: String,
        imageUrl: String,
        sharedAtMillis: Long,
        expiresAtMillis: Long = sharedAtMillis + TOTAL_SOLVE_WINDOW_MILLIS
    ) {
        val effectiveSharedAt = if (sharedAtMillis > 0L) sharedAtMillis else System.currentTimeMillis()
        val effectiveExpiresAt = if (expiresAtMillis > effectiveSharedAt) {
            expiresAtMillis
        } else {
            effectiveSharedAt + TOTAL_SOLVE_WINDOW_MILLIS
        }

        val isSameQuestion = _questionId.value == questionId &&
            _sharedAtMillis.value == effectiveSharedAt &&
            _expiresAtMillis.value == effectiveExpiresAt &&
            countdownJob?.isActive == true

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

    fun updateExplanation(text: String) {
        _explanationText.value = text
        if (_submitError.value != null) {
            _submitError.value = null
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
        super.onCleared()
    }
}
