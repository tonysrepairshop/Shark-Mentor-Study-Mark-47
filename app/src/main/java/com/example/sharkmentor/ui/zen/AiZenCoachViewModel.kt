package com.example.sharkmentor.ui.zen

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.client.generativeai.GenerativeModel
import com.example.sharkmentor.core.ai.GeminiAiFactory
import com.example.sharkmentor.core.ai.AiTaskType
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

class AiZenCoachViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("shark_ai_zen_coach_prefs", Context.MODE_PRIVATE)
    private val PREF_LAST_QUESTION_TIME = "last_question_timestamp"
    private val COOLDOWN_DURATION_MS = 60 * 60 * 1000L // 60 Minutes

    private val _messages = MutableStateFlow<List<ChatMessage>>(
        listOf(
            ChatMessage(
                text = "Selam! Ben Shark Zen KoÃ§un. SÄ±nav yolculuÄŸunda bazen yorulmak, bunalmak veya kaygÄ±lanmak Ã§ok insani. Zihnini veya kalbini zorlayan her ne varsa buraya dÃ¶kebilirsin; seni dinliyorum.",
                isUser = false
            )
        )
    )
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _remainingCooldownSeconds = MutableStateFlow(0L)
    val remainingCooldownSeconds: StateFlow<Long> = _remainingCooldownSeconds.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private var tickerJob: Job? = null

    // Empathetic & Grounded Zen Coach Prompt (Gemini 3.6 Flash)
    private val generativeModel by lazy {
        val systemPrompt = """
            Sen SharkMentor platformunun 'Empatik Zen KoÃ§u'sun.
            
            MÄ°SYONUN:
            Ã–ÄŸrenciler sÄ±nav hazÄ±rlÄ±ÄŸÄ± sÃ¼recinde yoÄŸun stres, yetersizlik hissi, tÃ¼kenmiÅŸlik ve kaygÄ± yaÅŸayabilir. Bu alan onlarÄ±n duygularÄ±nÄ± rahatÃ§a paylaÅŸÄ±p zihinsel yÃ¼klerini hafifletebileceÄŸi gÃ¼venli bir alandÄ±r.

            DAVRANIÅ KURALLARI:
            1. Asla didaktik, sorgulayÄ±cÄ± veya ders veren bir tonda olma (Sokratik sorgulama yapma).
            2. Ã–ÄŸrencinin hislerini iÃ§tenlikle onayla ve anla ("BÃ¶yle hissetmen Ã§ok doÄŸal", "BÃ¼yÃ¼k bir emek veriyorsun ve bu aÄŸÄ±rlÄ±k normal").
            3. Samimi, insaflÄ±, ÅŸefkatli, sakinleÅŸtirici ve umut veren bir dil kullan.
            4. CevaplarÄ±n kÄ±sa, ferahlatÄ±cÄ± ve net olsun (Maksimum 3-4 cÃ¼mle). Uzun paragraflarla Ã¶ÄŸrenciyi yorma.
            5. Ã–ÄŸrenciye bir nefes aldÄ±r, omzundaki yÃ¼kÃ¼ hafiflet ve masaya sakin bir zihinle dÃ¶nmesi iÃ§in nazik bir cesaret ver.
        """.trimIndent()
        
        GeminiAiFactory.createModel(
            taskType = AiTaskType.LIGHT_INTERACTION,
            systemPrompt = systemPrompt
        )
    }

    init {
        checkAndStartCooldownTicker()
    }

    private fun checkAndStartCooldownTicker() {
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch {
            while (true) {
                val lastTime = prefs.getLong(PREF_LAST_QUESTION_TIME, 0L)
                val elapsed = System.currentTimeMillis() - lastTime
                val remaining = COOLDOWN_DURATION_MS - elapsed

                if (remaining > 0) {
                    _remainingCooldownSeconds.value = remaining / 1000
                } else {
                    _remainingCooldownSeconds.value = 0
                }
                delay(1000)
            }
        }
    }

    fun sendQuestion(questionText: String) {
        if (questionText.isBlank() || _remainingCooldownSeconds.value > 0 || _isLoading.value) return

        // 1. Record User Message
        val updatedList = _messages.value.toMutableList().apply {
            add(ChatMessage(text = questionText.trim(), isUser = true))
        }
        _messages.value = updatedList

        // 2. Persist Cooldown
        prefs.edit().putLong(PREF_LAST_QUESTION_TIME, System.currentTimeMillis()).apply()
        checkAndStartCooldownTicker()

        // 3. Query Gemini 3.6 Flash
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _isLoading.value = true
            try {
                val response = generativeModel.generateContent(questionText)
                val aiReply = response.text ?: "Derin bir nefes al. YanÄ±ndayÄ±m, bu his geÃ§ici ve sen dÃ¼ÅŸÃ¼ndÃ¼ÄŸÃ¼nden daha gÃ¼Ã§lÃ¼sÃ¼n."
                _messages.value = _messages.value + ChatMessage(text = aiReply, isUser = false)
            } catch (e: Exception) {
                _messages.value = _messages.value + ChatMessage(
                    text = "Åu an baÄŸlantÄ± kurulamadÄ± ama unutma: YalnÄ±z deÄŸilsin. BirkaÃ§ yudum su al ve derin bir nefesle devam et.",
                    isUser = false
                )
            } finally {
                _isLoading.value = false
            }
        }
    }
}

