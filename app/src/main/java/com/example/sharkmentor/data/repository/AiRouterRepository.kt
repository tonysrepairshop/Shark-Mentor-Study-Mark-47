package com.example.sharkmentor.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.example.sharkmentor.BuildConfig
import com.example.sharkmentor.core.ai.ApiKeyRotator
import com.example.sharkmentor.core.ai.DeepSeekClient
import com.example.sharkmentor.core.util.ImageStorageManager
import com.example.sharkmentor.core.util.PdfBitmapConverter
import com.example.sharkmentor.data.local.SharkMentorDatabase
import com.example.sharkmentor.data.local.entity.ErrorQuestionEntity
import com.example.sharkmentor.data.local.entity.PlanEntity
import com.example.sharkmentor.data.local.entity.TargetEntity
import com.example.sharkmentor.data.local.entity.TargetPeriod
import com.example.sharkmentor.data.local.entity.TargetType
import com.example.sharkmentor.data.local.entity.TrainingFolderEntity
import com.example.sharkmentor.data.local.entity.TrainingQuestionEntity
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.FunctionCallPart
import com.google.ai.client.generativeai.type.FunctionDeclaration
import com.google.ai.client.generativeai.type.GenerationConfig
import com.google.ai.client.generativeai.type.QuotaExceededException
import com.google.ai.client.generativeai.type.RequestOptions
import com.google.ai.client.generativeai.type.ResponseStoppedException
import com.google.ai.client.generativeai.type.Schema
import com.google.ai.client.generativeai.type.Tool
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.UUID

/**
 * Speed/Depth Presets:
 * - Hızlı: gemini-3.6-flash, temperature = 0.3f
 * - Detaylı: gemini-3.6-flash, temperature = 0.45f
 */
enum class AiModelPower(
    val id: String,
    val label: String,
    val description: String,
    val primaryModel: String,
    val temperature: Float,
    val maxOutputTokens: Int,
    val topP: Float
) {
    FLASH(
        id = "fast_3_6",
        label = "Hızlı",
        description = "Anlık ve sıfır laf kalabalığı yanıt",
        primaryModel = "gemini-3.6-flash",
        temperature = 0.3f,
        maxOutputTokens = 4096,
        topP = 0.8f
    ),
    PRO(
        id = "deep_3_6",
        label = "Detaylı",
        description = "Adım adım derin problem çözümü ve sentez",
        primaryModel = "gemini-3.6-flash",
        temperature = 0.45f,
        maxOutputTokens = 8192,
        topP = 0.9f
    );

    fun toGenerationConfig(overrideMaxTokens: Int? = null): GenerationConfig = generationConfig {
        temperature = this@AiModelPower.temperature
        maxOutputTokens = overrideMaxTokens ?: this@AiModelPower.maxOutputTokens
        topP = this@AiModelPower.topP
    }
}

enum class SharkAiCapability(
    val id: String,
    val title: String,
    val subtitle: String
) {
    PROBLEM_SOLVER(
        id = "problem_solver",
        title = "Soru Çözümü",
        subtitle = "Adım adım detaylı soru çözümü"
    ),
    TWIN_QUESTION(
        id = "twin_question",
        title = "Benzer Soru",
        subtitle = "Yüklenen soruya benzer yeni soru"
    ),
    ADD_TO_STUDY_PLAN(
        id = "add_to_study_plan",
        title = "Plana Ekle",
        subtitle = "Çalışma programına entegre et"
    )
}

/**
 * The 4 Standalone Specialist AI Engines in SharkMentor.
 */
enum class SharkAiEngine(
    val id: String,
    val displayName: String,
    val autoBadgeText: String,
    val lockedBadgeText: String
) {
    SOLVER_ENGINE(
        id = "solver_engine",
        displayName = "Soru Çözümü",
        autoBadgeText = "Mod: Soru Çözümü",
        lockedBadgeText = "Mod: Soru Çözümü"
    ),
    TWIN_QUESTION_ENGINE(
        id = "twin_question_engine",
        displayName = "Benzer Soru",
        autoBadgeText = "Mod: Benzer Soru",
        lockedBadgeText = "Mod: Benzer Soru"
    ),
    PLANNER_OR_ORGANIZER(
        id = "planner_or_organizer",
        displayName = "Plana Ekle",
        autoBadgeText = "Mod: Plana Ekle",
        lockedBadgeText = "Mod: Plana Ekle"
    ),
    CONVERSATIONAL_PEER(
        id = "conversational_peer",
        displayName = "Sohbet",
        autoBadgeText = "Mod: Sohbet",
        lockedBadgeText = "Mod: Sohbet"
    );

    companion object {
        fun fromCapability(capability: SharkAiCapability?): SharkAiEngine? = when (capability) {
            SharkAiCapability.PROBLEM_SOLVER -> SOLVER_ENGINE
            SharkAiCapability.TWIN_QUESTION -> TWIN_QUESTION_ENGINE
            SharkAiCapability.ADD_TO_STUDY_PLAN -> PLANNER_OR_ORGANIZER
            null -> null
        }
    }
}

/**
 * Automatic Mode Detection (`AutoIntentClassifier`):
 * Inspects user text and attachment state before calling the API without appending fake text prefixes.
 */
object AutoIntentClassifier {
    private val TWIN_KEYWORDS = listOf(
        "ikiz",
        "benzer soru",
        "benzerini üret",
        "yeni soru üret",
        "benzer bir soru",
        "paralel soru",
        "izomorfik"
    )

    private val SOLVER_KEYWORDS = listOf(
        "çöz",
        "soru",
        "cevabı ne",
        "cevabi ne",
        "anlamadım",
        "anlamadim",
        "nasıl bulunur",
        "nasil bulunur",
        "kaçtır",
        "kactir",
        "işlemi",
        "şıkkı",
        "formülü"
    )

    private val PLANNER_KEYWORDS = listOf(
        "plan",
        "program",
        "takvim",
        "hata bankası",
        "hata bankasi",
        "hata kumbarası",
        "hata kumbarasi",
        "klasörle",
        "klasorle",
        "ekle"
    )

    private val MATH_SYMBOL_REGEX = Regex("([=\\+\\^√∫∑π÷×]|\\d+\\s*[\\+\\-\\*/=]\\s*\\d+)")

    fun classify(text: String, hasAttachment: Boolean = false): SharkAiEngine {
        val normalized = text.lowercase(Locale("tr")).trim()

        // Rule A (Twin Question)
        if (TWIN_KEYWORDS.any { normalized.contains(it) }) {
            return SharkAiEngine.TWIN_QUESTION_ENGINE
        }

        // Rule B (Problem Solver / Scanner)
        // Image attachment alone does NOT force SolverEngine; user decides via prompt or action chips
        if (SOLVER_KEYWORDS.any { normalized.contains(it) } ||
            MATH_SYMBOL_REGEX.containsMatchIn(normalized)
        ) {
            return SharkAiEngine.SOLVER_ENGINE
        }

        // Rule C (Planner / Error Bank Organizer)
        if (PLANNER_KEYWORDS.any { normalized.contains(it) }) {
            return SharkAiEngine.PLANNER_OR_ORGANIZER
        }

        // Rule D (Default - Conversational Peer)
        return SharkAiEngine.CONVERSATIONAL_PEER
    }
}

data class AiRouterResult(
    val text: String,
    val executedToolNotes: List<String> = emptyList(),
    val createdPlanFolderId: String? = null,
    val engineUsed: SharkAiEngine = SharkAiEngine.CONVERSATIONAL_PEER
)

class AiRouterRepository(
    private val context: Context,
    private val database: SharkMentorDatabase = SharkMentorDatabase.getDatabase(context.applicationContext)
) {

    companion object {
        private val VISION_MODEL_TIERS = listOf(
            "gemini-3.6-flash"
        )
        private val FAST_MODEL_TIERS = listOf(
            "gemini-3.6-flash"
        )
        private val DEEP_MODEL_TIERS = listOf(
            "gemini-3.6-flash"
        )

        const val MANDATORY_PERSONA_RULES = """
KESİN DAVRANIŞ VE ÜSLUP KURALLARI (ASLA İHLAL ETME):
1. SIFIR YALAKALIK & GİRİŞ YASAĞI: "Harika bir soru!", "Çok güzel düşünmüşsün!", "Merhaba! Sana bugün nasıl yardımcı olabilirim?" gibi yapay övgüleri ve kurumsal giriş cümlelerini KESİNLİKLE KULLANMA. Doğrudan konuya veya çözüme gir.
2. ORANTILI UZUNLUK:
   - Kullanıcı yalnızca "Merhaba", "Selam", "Nasılsın" gibi kısa bir selam verirse MAKSİMUM 1 KISA CÜMLE ile yanıt ver (Örn: "Selam! Bugün hangi derse veya soruya odaklanıyoruz?"). Selamlamaya asla paragraflarca yanıt yazma.
   - Çok adımlı matematik/fen işlemi çözmüyorsan standart açıklamalarını 4-6 kısa ve net cümleyle sınırla.
3. SOHBET KANCASI (KAPIYI KAPATMA):
   - Yanıtlarını "Başarılar dilerim, başka sorun varsa sorabilirsin" gibi sohbeti bitiren klişelerle BİTİRME.
   - Bunun yerine öğrenciyi akışta tutan tek cümlelik doğal bir takip sorusu (kanca) sor (Örn: "Bu adımda kafana yatmayan bir yer var mı, yoksa benzer bir ikiz soruyla pekiştirelim mi?").
"""

        const val SOLVER_ENGINE_SYSTEM_PROMPT = """
Sen YKS/LGS düzeyinde uzman, pedagojik ve analitik bir Soru Çözüm Motorusun.
Görevin görseldeki veya metindeki soruyu hatasız okumak ve doğrudan anlaşılır işlem adımlarıyla çözmektir.

ANLAŞILIRLIK VE MATEMATİKSEL YAZIM KURALLARI (ÇOK ÖNEMLİ):
1. TELEFON EKRANI İÇİN TEMİZ SEMBOLLER:
   - Kesinlikle karmaşık LaTeX kodları (\frac, \sqrt, \times, \cdot, \Delta, \pm vb.) YAZMA! Bunlar telefonda bozuk kod olarak görünür.
   - Doğrudan temiz Unicode sembolleri kullan:
     * Kesirler: a/b veya (a / b)
     * Karekök: √x veya √(ifade)
     * Çarpma ve bölme: · veya × ve ÷
     * Üs ve indisler: ², ³, ⁴, ⁿ, ⁻¹, x₁, x₂
     * Bağıntılar: =, ≠, ≈, ≤, ≥, ±, Δ, π, °
2. BİÇİMLENDİRME DÜZENİ:
   📌 **SORUNUN ÖZETİ & VERİLENLER:**
   • Verilenleri ve sorulanı 1-2 maddede özetle.

   🔍 **ADIM ADIM ÇÖZÜM:**
   • **1. Adım:** İlk işlem ve mantığı
   • **2. Adım:** Devam eden işlemler
   • **3. Adım:** Sadeleştirme veya son işlem

   🎯 **DOĞRU CEVAP & SONUÇ:**
   • **Doğru Seçenek: [Şık ve Değer]** (Örn: **Doğru Cevap: C (24)**)
   • 1 cümlelik pratik kontrol veya kilit ipucu.
$MANDATORY_PERSONA_RULES
"""

        const val TWIN_QUESTION_ENGINE_SYSTEM_PROMPT = """
Sen ÖSYM / MEB tarzı Özgün İkiz Soru Üretim Motorusun.
Kullanıcının gönderdiği sorunun veya konunun kazanımını ve çözüm algoritmasını birebir koruyarak, sayıları ve hikayeyi değiştirip yepyeni, özgün bir çoktan seçmeli soru (A, B, C, D, E şıklı) üret.

ANLAŞILIRLIK VE BİÇİMLENDİRME KURALLARI:
1. TELEFON EKRANI İÇİN TEMİZ SEMBOLLER:
   - Kesinlikle LaTeX kodları (\frac, \sqrt, \cdot vb.) KULLANMA. Temiz semboller (√, ², ³, ×, ÷, ±, Δ, π, a/b) kullan.
2. DÜZEN VE FORMAT:
   📝 **ÖZGÜN İKİZ SORU:**
   [Soru metni ve hikayesi]
   A) ...
   B) ...
   C) ...
   D) ...
   E) ...

   💡 **ÇÖZÜM ALGORİTMASI:**
   • **1. Adım:** ...
   • **2. Adım:** ...

   🎯 **DOĞRU CEVAP:**
   • **[Şık ve Değeri]** (Örn: **Doğru Cevap: B**)
$MANDATORY_PERSONA_RULES
"""
        const val TWIN_QUESTION_SYSTEM_PROMPT = TWIN_QUESTION_ENGINE_SYSTEM_PROMPT

        const val AUTONOMOUS_PLANNER_SYSTEM_PROMPT = """
Sen SharkMentor Otonom Programlayıcı ve Koç Motorusun.
Öğrencinin haftalık çalışma planını hazırlar, takvime görev ekler, kaynak/kitap ve konu hedefleri atarsın.

KURALLAR VE EYLEM YÖNETİMİ:
1. Sohbet metninde öğrenciye hitaben 2-4 cümlelik samimi, motive edici ve net bir Türkçe açıklama/özet yaz.
2. Yapılan veya önerilen işlemi sisteme işlemek için mesajının EN SONUNA ilgili JSON eylem bloğunu ekle:

HAFTALIK 7 GÜNLÜK PLAN OLUŞTURMA:
#ACTION:{"type":"CREATE_WEEKLY_PLAN","title":"Haftalık Çalışma Programı","summary":"Strateji özeti","schedule":[{"dayOfWeek":1,"timeSlot":"09:00 - 10:30","subject":"Matematik","topic":"Türev","targetQuestions":40}]}#

TEKİL GÖREV EKLEME:
#ACTION:{"type":"INSERT_PLAN_TASK","day":1,"time":"16:00 - 17:30","subject":"Fizik","topic":"Kuvvet ve Hareket","questions":30}#

HEDEF EKLEME (Net veya Soru Hedefi):
#ACTION:{"type":"INSERT_TARGET","title":"TYT Türkçe 35+ Net","description":"Haftalık deneme ve paragraf rutini","type":"SHORT_TERM","period":"WEEKLY","targetQuestions":150,"priority":"HIGH"}#

KAYNAK / KİTAP EKLEME:
#ACTION:{"type":"INSERT_RESOURCE","title":"3D AYT Matematik Soru Bankası","subject":"Matematik","totalUnits":48,"unitType":"Test"}#

KONU HEDEFİ EKLEME:
#ACTION:{"type":"INSERT_TOPIC","subject":"Biyoloji","topic":"Kalıtım ve Genetik","targetLevel":5}#
$MANDATORY_PERSONA_RULES
"""

        const val CONVERSATIONAL_PEER_SYSTEM_PROMPT =
            "Sen SharkMentor Akran Koç Motorusun. Öğrenciyle doğal, hızlı, samimi ve analitik bir akran mentör gibi konuşursun.\n$MANDATORY_PERSONA_RULES"
    }

    private val errorQuestionDao = database.errorQuestionDao()
    private val trainingFolderDao = database.trainingFolderDao()
    private val trainingQuestionDao = database.trainingQuestionDao()
    private val planDao = database.planDao()
    private val planRepositoryImpl = PlanRepositoryImpl(database.planDao(), database.pendingDeletionDao())
    private val targetRepository = PlanRepository(
        database.targetDao(),
        database.sessionDao(),
        database.planFolderDao()
    )

    val organizeErrorBankDeclaration: FunctionDeclaration = FunctionDeclaration(
        name = "organizeErrorBank",
        description = "Organizes the student's Error Bank (Hata Kumbarası) by classifying untagged questions by subject/topic, creating a training folder, or saving an attached question into the error bank.",
        parameters = listOf(
            Schema.str(
                name = "subject",
                description = "Subject name such as Matematik, Fizik, Kimya, Biyoloji, Türkçe, Tarih, Coğrafya"
            ),
            Schema.str(
                name = "topic",
                description = "Sub-topic name such as Türev, İntegral, Optik, Paragraf"
            ),
            Schema.str(
                name = "folderTitle",
                description = "Title for the organized training folder"
            )
        ),
        requiredParameters = listOf("subject", "topic")
    )

    val addStudyPlanItemDeclaration: FunctionDeclaration = FunctionDeclaration(
        name = "addStudyPlanItem",
        description = "Adds a new study task or session item directly into the student's active Study Plan (Çalışma Planı).",
        parameters = listOf(
            Schema.str(
                name = "subject",
                description = "Subject name (e.g. Matematik, Fizik, Türkçe)"
            ),
            Schema.str(
                name = "topic",
                description = "Topic or study focus (e.g. Türev Uygulamaları, Paragraf Rutini)"
            ),
            Schema.int(
                name = "dayOfWeek",
                description = "Day of week from 1 (Monday / Pazartesi) to 7 (Sunday / Pazar)"
            ),
            Schema.str(
                name = "scheduledTime",
                description = "Time slot formatted as HH:mm - HH:mm (e.g. 16:00 - 17:30)"
            ),
            Schema.int(
                name = "targetQuestions",
                description = "Target number of questions to solve (minimum 20, default 30)"
            )
        ),
        requiredParameters = listOf("subject", "topic", "dayOfWeek")
    )

    val autonomousSharkTool: Tool = Tool(
        functionDeclarations = listOf(
            organizeErrorBankDeclaration,
            addStudyPlanItemDeclaration
        )
    )

    /**
     * Standalone Engine 1: `SolverEngine` (Soru Çözücü Modülü)
     */
    suspend fun executeSolverEngine(
        userPrompt: String,
        contextInstruction: String = "",
        attachmentUri: Uri? = null,
        modelPower: AiModelPower = AiModelPower.FLASH,
        onPartialText: (String) -> Unit = {}
    ): Result<AiRouterResult> {
        val cleanPrompt = userPrompt.trim().ifBlank {
            "Görseldeki veya belgedeki soruyu hatasız oku ve adım adım çöz."
        }
        val fullSystemInstruction = buildString {
            appendLine(SOLVER_ENGINE_SYSTEM_PROMPT)
            if (contextInstruction.isNotBlank()) {
                appendLine(contextInstruction)
            }
        }.trim()

        // Solver questions need full step-by-step math room without truncation
        val maxTokens = 2048

        return streamEnginePipeline(
            activeEngine = SharkAiEngine.SOLVER_ENGINE,
            prompt = cleanPrompt,
            systemInstruction = fullSystemInstruction,
            attachmentUri = attachmentUri,
            modelPower = modelPower,
            overrideMaxTokens = maxTokens,
            onPartialText = onPartialText
        )
    }

    /**
     * Standalone Engine 2: `TwinQuestionEngine` (İkiz Soru Üretici Modülü)
     */
    suspend fun executeTwinQuestionEngine(
        userPrompt: String,
        contextInstruction: String = "",
        attachmentUri: Uri? = null,
        modelPower: AiModelPower = AiModelPower.FLASH,
        onPartialText: (String) -> Unit = {}
    ): Result<AiRouterResult> {
        val cleanPrompt = userPrompt.trim().ifBlank {
            "Paylaşılan sorunun kazanımını ve çözüm algoritmasını birebir koruyarak ÖSYM tarzı özgün bir ikiz soru (A-E şıklı) üret ve çözümünü ekle."
        }
        val fullSystemInstruction = buildString {
            appendLine(TWIN_QUESTION_ENGINE_SYSTEM_PROMPT)
            if (contextInstruction.isNotBlank()) {
                appendLine(contextInstruction)
            }
        }.trim()

        val maxTokens = 2048

        return streamEnginePipeline(
            activeEngine = SharkAiEngine.TWIN_QUESTION_ENGINE,
            prompt = cleanPrompt,
            systemInstruction = fullSystemInstruction,
            attachmentUri = attachmentUri,
            modelPower = modelPower,
            overrideMaxTokens = maxTokens,
            onPartialText = onPartialText
        )
    }

    /**
     * Standalone Engine 3: `AutonomousPlannerEngine` (Programlayıcı & Klasörleyici)
     */
    suspend fun executeAutonomousPlannerEngine(
        userPrompt: String,
        contextInstruction: String = "",
        attachmentUri: Uri? = null,
        modelPower: AiModelPower = AiModelPower.FLASH,
        onPartialText: (String) -> Unit = {}
    ): Result<AiRouterResult> {
        val cleanPrompt = userPrompt.trim().ifBlank {
            "Bu konuyu çalışma planıma ekle veya hata kumbaramı organize et."
        }
        val fullSystemInstruction = buildString {
            appendLine(AUTONOMOUS_PLANNER_SYSTEM_PROMPT)
            if (contextInstruction.isNotBlank()) {
                appendLine(contextInstruction)
            }
        }.trim()

        val maxTokens = 2048

        return streamEnginePipeline(
            activeEngine = SharkAiEngine.PLANNER_OR_ORGANIZER,
            prompt = cleanPrompt,
            systemInstruction = fullSystemInstruction,
            attachmentUri = attachmentUri,
            modelPower = modelPower,
            overrideMaxTokens = maxTokens,
            onPartialText = onPartialText
        )
    }

    /**
     * Standalone Engine 4: `ConversationalPeerEngine` (Akran Koç Modülü)
     */
    suspend fun executeConversationalPeerEngine(
        userPrompt: String,
        contextInstruction: String = "",
        attachmentUri: Uri? = null,
        modelPower: AiModelPower = AiModelPower.FLASH,
        onPartialText: (String) -> Unit = {}
    ): Result<AiRouterResult> {
        val fullSystemInstruction = buildString {
            appendLine(CONVERSATIONAL_PEER_SYSTEM_PROMPT)
            if (contextInstruction.isNotBlank()) {
                appendLine(contextInstruction)
            }
        }.trim()

        return streamEnginePipeline(
            activeEngine = SharkAiEngine.CONVERSATIONAL_PEER,
            prompt = userPrompt,
            systemInstruction = fullSystemInstruction,
            attachmentUri = attachmentUri,
            modelPower = modelPower,
            overrideMaxTokens = null,
            onPartialText = onPartialText
        )
    }

    suspend fun solveProblemStepByStep(
        userPrompt: String,
        systemInstruction: String,
        attachmentUri: Uri? = null,
        modelPower: AiModelPower = AiModelPower.FLASH,
        onPartialText: (String) -> Unit = {}
    ): Result<AiRouterResult> = executeSolverEngine(
        userPrompt = userPrompt,
        contextInstruction = systemInstruction,
        attachmentUri = attachmentUri,
        modelPower = modelPower,
        onPartialText = onPartialText
    )

    suspend fun generateTwinQuestion(
        userPrompt: String,
        systemInstruction: String,
        attachmentUri: Uri? = null,
        modelPower: AiModelPower = AiModelPower.FLASH,
        onPartialText: (String) -> Unit = {}
    ): Result<AiRouterResult> = executeTwinQuestionEngine(
        userPrompt = userPrompt,
        contextInstruction = systemInstruction,
        attachmentUri = attachmentUri,
        modelPower = modelPower,
        onPartialText = onPartialText
    )

    suspend fun routeMessage(
        prompt: String,
        systemInstruction: String,
        attachmentUri: Uri? = null,
        enableTools: Boolean = false,
        modelPower: AiModelPower = AiModelPower.FLASH,
        activeEngine: SharkAiEngine? = null,
        onPartialText: (String) -> Unit = {}
    ): Result<AiRouterResult> {
        val resolvedEngine = activeEngine ?: if (enableTools && attachmentUri == null) {
            AutoIntentClassifier.classify(prompt, hasAttachment = false)
        } else {
            AutoIntentClassifier.classify(prompt, hasAttachment = attachmentUri != null)
        }
        return when (resolvedEngine) {
            SharkAiEngine.SOLVER_ENGINE -> executeSolverEngine(
                userPrompt = prompt,
                contextInstruction = systemInstruction,
                attachmentUri = attachmentUri,
                modelPower = modelPower,
                onPartialText = onPartialText
            )
            SharkAiEngine.TWIN_QUESTION_ENGINE -> executeTwinQuestionEngine(
                userPrompt = prompt,
                contextInstruction = systemInstruction,
                attachmentUri = attachmentUri,
                modelPower = modelPower,
                onPartialText = onPartialText
            )
            SharkAiEngine.PLANNER_OR_ORGANIZER -> executeAutonomousPlannerEngine(
                userPrompt = prompt,
                contextInstruction = systemInstruction,
                attachmentUri = attachmentUri,
                modelPower = modelPower,
                onPartialText = onPartialText
            )
            SharkAiEngine.CONVERSATIONAL_PEER -> executeConversationalPeerEngine(
                userPrompt = prompt,
                contextInstruction = systemInstruction,
                attachmentUri = attachmentUri,
                modelPower = modelPower,
                onPartialText = onPartialText
            )
        }
    }

    /**
     * Dedicated Vision & OCR pipeline powered by Google Gemini Vision.
     * Enforces `gemini-3.6-flash`, `temperature = 0.1f`, `tools = emptyList()`,
     * and the 1280px high-contrast pre-processing pipeline.
     */
    suspend fun executeGeminiVision(
        prompt: String,
        systemInstruction: String,
        attachmentUri: Uri,
        activeEngine: SharkAiEngine = SharkAiEngine.SOLVER_ENGINE,
        onPartialText: (String) -> Unit = {}
    ): Result<AiRouterResult> = withContext(Dispatchers.IO) {
        val attachments = resolveMultimodalAttachment(attachmentUri)
        val keyCount = ApiKeyRotator.getKeyCount().coerceAtLeast(1)
        var lastException: Throwable? = null

        val visionConfig = generationConfig {
            temperature = 0.1f // Strictly 0.1f for accurate OCR & Math problem reading
            maxOutputTokens = 8192
            topP = 0.8f
        }

        for (modelName in VISION_MODEL_TIERS) {
            val maxAttempts = (keyCount * 2).coerceIn(2, 6)
            for (attempt in 0 until maxAttempts) {
                val activeKey = ApiKeyRotator.getActiveKey()
                    .ifBlank { BuildConfig.GEMINI_API_KEY.trim() }
                    .trim()
                    .replace("\"", "")
                    .replace("'", "")

                if (activeKey.isBlank() || activeKey == "null") {
                    ApiKeyRotator.rotateToNextKey()
                    continue
                }

                try {
                    val cleanModelName = modelName.trim().removePrefix("models/").removePrefix("/")
                    val model = GenerativeModel(
                        modelName = cleanModelName,
                        apiKey = activeKey,
                        generationConfig = visionConfig,
                        tools = emptyList(), // Strictly tools = emptyList() for vision requests
                        systemInstruction = if (systemInstruction.isNotBlank()) {
                            content { text(systemInstruction) }
                        } else null,
                        requestOptions = RequestOptions(timeout = 60_000L)
                    )

                    val accumulatedText = StringBuilder()
                    val inputContent = buildMultimodalInputContent(prompt, attachments)
                    val responseFlow = model.generateContentStream(inputContent)

                    try {
                        responseFlow.collect { chunk ->
                            val chunkText = runCatching { chunk.text }.getOrNull()
                            if (!chunkText.isNullOrEmpty()) {
                                accumulatedText.append(chunkText)
                                onPartialText(accumulatedText.toString())
                            }
                        }
                    } catch (rse: ResponseStoppedException) {
                        Log.w("SharkAiVision", "Vision stream stopped (${rse.message}), accumulated ${accumulatedText.length} chars")
                        if (accumulatedText.isBlank()) {
                            throw rse
                        }
                    } catch (e: Exception) {
                        if (accumulatedText.length > 50) {
                            Log.w("SharkAiVision", "Vision stream interrupted (${e.message}), but kept ${accumulatedText.length} chars")
                        } else {
                            throw e
                        }
                    }

                    val finalText = accumulatedText.toString().trim().ifBlank {
                        "Görsel incelendi ve çözüldü."
                    }

                    onPartialText(finalText)

                    return@withContext Result.success(
                        AiRouterResult(
                            text = finalText,
                            executedToolNotes = emptyList(),
                            createdPlanFolderId = null,
                            engineUsed = activeEngine
                        )
                    )
                } catch (quotaEx: QuotaExceededException) {
                    Log.w("SharkAiVision", "QuotaExceededException on Vision model $modelName (attempt ${attempt + 1}/$maxAttempts). Rotating key...", quotaEx)
                    lastException = quotaEx
                    ApiKeyRotator.rotateToNextKey()
                    delay(300L * (attempt + 1))
                } catch (e: Exception) {
                    Log.w("SharkAiVision", "Vision error on $modelName (attempt ${attempt + 1}/$maxAttempts): ${e.javaClass.simpleName} - ${e.message}")
                    lastException = e
                    val msg = "${e.javaClass.simpleName} ${e.message} ${e.cause?.message}".lowercase()
                    val is503OrOverload = msg.contains("503") ||
                        msg.contains("overload") ||
                        msg.contains("unavailable") ||
                        e.javaClass.simpleName.contains("ServerException", ignoreCase = true)
                    val isKeyOrQuota = msg.contains("429") ||
                        msg.contains("quota") ||
                        msg.contains("resource_exhausted") ||
                        ApiKeyRotator.shouldRotateKey(e)

                    if (is503OrOverload) {
                        // Exponential backoff for 503 Overloaded: 500ms, 1000ms, 1500ms...
                        val backoffMs = (500L * (attempt + 1)).coerceAtMost(2500L)
                        Log.w("SharkAiVision", "Model $modelName returned 503/Overloaded. Rotating key and backing off for ${backoffMs}ms...")
                        ApiKeyRotator.rotateToNextKey()
                        delay(backoffMs)
                        if (attempt >= 2) {
                            // Advance to next fallback model tier
                            break
                        }
                    } else if (isKeyOrQuota) {
                        ApiKeyRotator.rotateToNextKey()
                        delay(250L)
                    } else if (msg.contains("404") || msg.contains("not_found") || msg.contains("no longer available") || msg.contains("not found for api version")) {
                        // Model not supported, advance to next model tier
                        break
                    } else {
                        ApiKeyRotator.rotateToNextKey()
                        delay(300L)
                    }
                }
            }
        }

        // Seamless fallback to Kimi Vision if Gemini Vision is unavailable or rate-limited
        val kimiKey = DeepSeekClient.getKimiApiKey()
        if (kimiKey.isNotBlank()) {
            val base64Img = extractBase64FromAttachment(attachments)
            if (base64Img != null) {
                Log.i("SharkAiVision", "Gemini vision exhausted, falling back to Kimi Vision...")
                try {
                    val kimiResult = DeepSeekClient.streamKimiVisionCompletion(
                        systemPrompt = systemInstruction,
                        userPrompt = prompt,
                        base64Image = base64Img,
                        mimeType = attachments.mimeType ?: "image/jpeg",
                        onAccumulatedChunk = onPartialText
                    )
                    if (kimiResult.isNotBlank()) {
                        return@withContext Result.success(
                            AiRouterResult(
                                text = kimiResult,
                                executedToolNotes = emptyList(),
                                createdPlanFolderId = null,
                                engineUsed = activeEngine
                            )
                        )
                    }
                } catch (ke: Throwable) {
                    Log.w("SharkAiVision", "Kimi Vision fallback failed: ${ke.message}", ke)
                }
            }
        }

        Result.failure(
            lastException ?: IllegalStateException("Vision modelleri yanıt vermedi.")
        )
    }

    /**
     * Dedicated Text/Chat streaming pipeline powered by DeepSeek API with automatic Kimi fallback.
     */
    suspend fun executeTextStream(
        activeEngine: SharkAiEngine = SharkAiEngine.CONVERSATIONAL_PEER,
        prompt: String,
        systemInstruction: String,
        chatHistory: List<com.example.sharkmentor.core.ai.OpenAiChatMessage> = emptyList(),
        modelPower: AiModelPower = AiModelPower.FLASH,
        onPartialText: (String) -> Unit = {}
    ): Result<AiRouterResult> = withContext(Dispatchers.IO) {
        try {
            val temp = if (modelPower == AiModelPower.PRO) 0.4f else 0.25f
            val maxTokens = if (modelPower == AiModelPower.PRO) 8192 else 4096

            val resultText = DeepSeekClient.streamChatCompletion(
                systemPrompt = systemInstruction,
                userPrompt = prompt,
                chatHistory = chatHistory,
                temperature = temp,
                maxTokens = maxTokens,
                onAccumulatedChunk = onPartialText
            )

            val parsedActionNotes = mutableListOf<String>()
            val actionRegex = Regex("#ACTION:(\\{.*?\\})#")
            for (match in actionRegex.findAll(resultText)) {
                val jsonPayload = match.groupValues[1]
                runCatching {
                    val jsonObj = Gson().fromJson(jsonPayload, JsonObject::class.java)
                    val actionType = jsonObj.get("type")?.asString
                    if (actionType == "INSERT_TASK") {
                        val day = jsonObj.get("day")?.asInt ?: 1
                        val subj = jsonObj.get("subject")?.asString ?: "Ders"
                        val top = jsonObj.get("topic")?.asString ?: "Çalışma"
                        val qCount = jsonObj.get("questions")?.asInt ?: 30
                        val planEntity = PlanEntity(
                            id = UUID.randomUUID().toString(),
                            folderId = "",
                            title = "$subj - $top",
                            subject = subj,
                            topic = top,
                            dayOfWeek = day,
                            scheduledTime = "16:00 - 17:30",
                            targetQuestions = qCount,
                            orderIndex = 0
                        )
                        planDao.insertTasks(listOf(planEntity))
                        parsedActionNotes.add("Plan eklendi: $subj • $top ($qCount Soru)")
                    }
                }
            }

            Result.success(
                AiRouterResult(
                    text = resultText,
                    executedToolNotes = parsedActionNotes,
                    createdPlanFolderId = null,
                    engineUsed = activeEngine
                )
            )
        } catch (e: Exception) {
            Log.w("AiRouterRepository", "DeepSeek/Kimi text stream failed (${e.message}), attempting Gemini fallback...", e)
            streamGeminiTextFallback(
                activeEngine = activeEngine,
                prompt = prompt,
                systemInstruction = systemInstruction,
                modelPower = modelPower,
                onPartialText = onPartialText
            )
        }
    }

    /**
     * Core hybrid router: Routes Multimodal/Vision to Gemini Vision, Text-only to DeepSeek (with Kimi fallback).
     */
    private suspend fun streamEnginePipeline(
        activeEngine: SharkAiEngine,
        prompt: String,
        systemInstruction: String,
        attachmentUri: Uri? = null,
        chatHistory: List<com.example.sharkmentor.core.ai.OpenAiChatMessage> = emptyList(),
        modelPower: AiModelPower = AiModelPower.FLASH,
        overrideMaxTokens: Int? = null,
        onPartialText: (String) -> Unit = {}
    ): Result<AiRouterResult> {
        return if (attachmentUri != null) {
            executeGeminiVision(
                prompt = prompt,
                systemInstruction = systemInstruction,
                attachmentUri = attachmentUri,
                activeEngine = activeEngine,
                onPartialText = onPartialText
            )
        } else {
            executeTextStream(
                activeEngine = activeEngine,
                prompt = prompt,
                systemInstruction = systemInstruction,
                chatHistory = chatHistory,
                modelPower = modelPower,
                onPartialText = onPartialText
            )
        }
    }

    /**
     * Fallback Gemini streaming execution pipeline if external providers are unavailable.
     */
    private suspend fun streamGeminiTextFallback(
        activeEngine: SharkAiEngine,
        prompt: String,
        systemInstruction: String,
        modelPower: AiModelPower = AiModelPower.FLASH,
        overrideMaxTokens: Int? = null,
        onPartialText: (String) -> Unit = {}
    ): Result<AiRouterResult> = withContext(Dispatchers.IO) {
        val keyCount = ApiKeyRotator.getKeyCount().coerceAtLeast(1)
        var lastException: Throwable? = null
        val modelTiers = if (modelPower == AiModelPower.PRO) DEEP_MODEL_TIERS else FAST_MODEL_TIERS
        val shouldAttachTools = activeEngine == SharkAiEngine.PLANNER_OR_ORGANIZER

        for (modelName in modelTiers) {
            for (attempt in 0 until keyCount) {
                val activeKey = ApiKeyRotator.getActiveKey()
                    .ifBlank { BuildConfig.GEMINI_API_KEY.trim() }
                    .trim()
                    .replace("\"", "")
                    .replace("'", "")

                if (activeKey.isBlank() || activeKey == "null") {
                    ApiKeyRotator.rotateToNextKey()
                    continue
                }

                try {
                    val model = createModel(
                        modelName = modelName,
                        apiKey = activeKey,
                        systemInstruction = systemInstruction,
                        modelPower = modelPower,
                        overrideMaxTokens = overrideMaxTokens,
                        enableTools = shouldAttachTools
                    )

                    val accumulatedText = StringBuilder()
                    val collectedFunctionCalls = mutableListOf<FunctionCallPart>()

                    val responseFlow = model.generateContentStream(prompt)

                    try {
                        responseFlow.collect { chunk ->
                            val chunkText = runCatching { chunk.text }.getOrNull()
                            if (!chunkText.isNullOrEmpty()) {
                                accumulatedText.append(chunkText)
                                onPartialText(accumulatedText.toString())
                            }
                            val calls = runCatching { chunk.functionCalls }.getOrDefault(emptyList())
                            if (calls.isNotEmpty()) {
                                collectedFunctionCalls.addAll(calls)
                            }
                        }
                    } catch (rse: ResponseStoppedException) {
                        Log.w("AiRouterRepository", "Gemini stream stopped (${rse.message}), keeping accumulated ${accumulatedText.length} chars")
                        if (accumulatedText.isBlank()) {
                            throw rse
                        }
                    } catch (e: Exception) {
                        if (accumulatedText.length > 50) {
                            Log.w("AiRouterRepository", "Gemini stream interrupted (${e.message}), but keeping ${accumulatedText.length} chars")
                        } else {
                            throw e
                        }
                    }

                    val toolNotes = mutableListOf<String>()
                    var createdFolderId: String? = null

                    if (collectedFunctionCalls.isNotEmpty()) {
                        for (call in collectedFunctionCalls) {
                            val executionResult = executeToolCall(call, null)
                            if (executionResult.note.isNotBlank()) {
                                toolNotes.add(executionResult.note)
                            }
                            if (executionResult.planFolderId != null) {
                                createdFolderId = executionResult.planFolderId
                            }
                        }
                    }

                    val finalText = buildString {
                        val modelText = accumulatedText.toString().trim()
                        if (modelText.isNotBlank()) {
                            append(modelText)
                        }
                        if (toolNotes.isNotEmpty()) {
                            if (isNotEmpty()) append("\n\n")
                            append(toolNotes.joinToString("\n"))
                        }
                    }.ifBlank {
                        "İşleminiz tamamlandı."
                    }

                    onPartialText(finalText)

                    return@withContext Result.success(
                        AiRouterResult(
                            text = finalText,
                            executedToolNotes = toolNotes,
                            createdPlanFolderId = createdFolderId,
                            engineUsed = activeEngine
                        )
                    )
                } catch (quotaEx: QuotaExceededException) {
                    Log.w(
                        "AiRouterRepository",
                        "QuotaExceededException (HTTP 429) on $modelName (key attempt ${attempt + 1}/$keyCount). Rotating key...",
                        quotaEx
                    )
                    lastException = quotaEx
                    ApiKeyRotator.rotateToNextKey()
                    delay(150L)
                } catch (e: Exception) {
                    val fullMsg = "${e.javaClass.simpleName} ${e.message} ${e.cause?.message}".lowercase()
                    val isQuotaOr429 = fullMsg.contains("429") ||
                        fullMsg.contains("quota") ||
                        fullMsg.contains("resource_exhausted") ||
                        fullMsg.contains("rate_limit") ||
                        ApiKeyRotator.shouldRotateKey(e)

                    Log.w(
                        "AiRouterRepository",
                        "Stream call failed on $modelName (attempt ${attempt + 1}/$keyCount, isQuota=$isQuotaOr429): ${e.message}"
                    )
                    lastException = e

                    if (isQuotaOr429) {
                        ApiKeyRotator.rotateToNextKey()
                        delay(150L)
                    } else if (shouldAttachTools) {
                        // Immediate tool-free streaming fallback on the same fast model
                        val fallbackResult = runCatching {
                            val fallbackModel = createModel(
                                modelName = modelName,
                                apiKey = activeKey,
                                systemInstruction = systemInstruction,
                                modelPower = modelPower,
                                overrideMaxTokens = overrideMaxTokens,
                                enableTools = false
                            )
                            val sb = StringBuilder()
                            fallbackModel.generateContentStream(prompt).collect { chunk ->
                                val t = runCatching { chunk.text }.getOrNull()
                                if (!t.isNullOrEmpty()) {
                                    sb.append(t)
                                    onPartialText(sb.toString())
                                }
                            }
                            sb.toString().trim()
                        }.getOrNull()

                        if (!fallbackResult.isNullOrBlank()) {
                            return@withContext Result.success(
                                AiRouterResult(
                                    text = fallbackResult,
                                    engineUsed = activeEngine
                                )
                            )
                        }
                        break
                    } else {
                        break
                    }
                }
            }
        }

        Result.failure(
            lastException ?: IllegalStateException("Tüm AI modelleri ve yedek anahtarlar yanıt vermedi.")
        )
    }

    private fun createModel(
        modelName: String,
        apiKey: String,
        systemInstruction: String,
        modelPower: AiModelPower,
        overrideMaxTokens: Int? = null,
        enableTools: Boolean = false
    ): GenerativeModel {
        val cleanModelName = modelName.trim().removePrefix("models/").removePrefix("/")
        return GenerativeModel(
            modelName = cleanModelName,
            apiKey = apiKey,
            generationConfig = modelPower.toGenerationConfig(overrideMaxTokens),
            tools = if (enableTools) listOf(autonomousSharkTool) else emptyList(),
            systemInstruction = if (systemInstruction.isNotBlank()) {
                content { text(systemInstruction) }
            } else null,
            requestOptions = RequestOptions(timeout = 30_000L)
        )
    }

    private data class MultimodalPayload(
        val bitmaps: List<Bitmap>,
        val rawBytes: ByteArray?,
        val mimeType: String
    )

    private suspend fun resolveMultimodalAttachment(uri: Uri): MultimodalPayload = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val mimeType = resolver.getType(uri) ?: inferMimeType(uri)

        val isPdf = mimeType.equals("application/pdf", ignoreCase = true) ||
            uri.toString().endsWith(".pdf", ignoreCase = true)

        if (isPdf) {
            val bitmaps = runCatching {
                PdfBitmapConverter.convertPdfUriToBitmaps(context, uri, maxPages = 3)
            }.getOrDefault(emptyList())
            val rawBytes = if (bitmaps.isEmpty()) {
                runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            } else null
            MultimodalPayload(
                bitmaps = bitmaps,
                rawBytes = rawBytes,
                mimeType = mimeType
            )
        } else {
            val optimized = ImageStorageManager.prepareOptimizedBitmapForAi(
                context = context,
                uri = uri,
                enhanceContrast = true
            )
            MultimodalPayload(
                bitmaps = if (optimized != null) listOf(optimized) else emptyList(),
                rawBytes = null,
                mimeType = "image/jpeg"
            )
        }
    }

    private fun extractBase64FromAttachment(payload: MultimodalPayload): String? {
        val bmp = payload.bitmaps.firstOrNull()
        if (bmp != null) {
            val stream = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, stream)
            return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        }
        val bytes = payload.rawBytes
        if (bytes != null) {
            return Base64.encodeToString(bytes, Base64.NO_WRAP)
        }
        return null
    }

    private fun buildMultimodalInputContent(
        prompt: String,
        attachment: MultimodalPayload
    ) = content {
        if (attachment.bitmaps.isNotEmpty()) {
            attachment.bitmaps.forEach { bmp ->
                image(bmp)
            }
        } else if (attachment.rawBytes != null) {
            val decoded = runCatching {
                BitmapFactory.decodeByteArray(attachment.rawBytes, 0, attachment.rawBytes.size)
            }.getOrNull()
            if (decoded != null) {
                image(decoded)
            } else {
                blob(attachment.mimeType, attachment.rawBytes)
            }
        }
        text(prompt)
    }

    private data class ToolExecutionOutcome(
        val note: String,
        val planFolderId: String? = null
    )

    private suspend fun executeToolCall(
        call: FunctionCallPart,
        attachmentUri: Uri?
    ): ToolExecutionOutcome = withContext(Dispatchers.IO) {
        when (call.name) {
            "organizeErrorBank" -> {
                val subject = call.args["subject"]?.ifBlank { "Matematik" } ?: "Matematik"
                val topic = call.args["topic"]?.ifBlank { "Genel" } ?: "Genel"
                val folderTitle = call.args["folderTitle"]?.takeIf { it.isNotBlank() }
                    ?: "$subject - $topic Hata Klasörü"

                val folderId = UUID.randomUUID().toString()
                trainingFolderDao.insertFolder(
                    TrainingFolderEntity(
                        id = folderId,
                        title = folderTitle
                    )
                )

                val untagged = errorQuestionDao.getUntaggedQuestions(limit = 10)
                untagged.filter { it.subject.equals(subject, ignoreCase = true) || it.subject.isBlank() }
                    .forEach { q ->
                        errorQuestionDao.updateAiTopic(q.id, topic)
                    }

                if (attachmentUri != null) {
                    val savedPath = runCatching {
                        ImageStorageManager.processAndSaveImage(context, attachmentUri, isQuestion = true)
                    }.getOrDefault(attachmentUri.toString())

                    errorQuestionDao.insertQuestion(
                        ErrorQuestionEntity(
                            subject = subject,
                            questionImageUri = savedPath,
                            topic = topic,
                            aiTopic = topic
                        )
                    )
                    trainingQuestionDao.insertQuestion(
                        TrainingQuestionEntity(
                            folderId = folderId,
                            questionText = "$subject - $topic",
                            imageUri = savedPath
                        )
                    )
                }

                ToolExecutionOutcome(
                    note = "✅ Hata Kumbarası organize edildi: **$folderTitle** ($subject • $topic)"
                )
            }

            "addStudyPlanItem" -> {
                val subject = call.args["subject"]?.ifBlank { "Matematik" } ?: "Matematik"
                val topic = call.args["topic"]?.ifBlank { "Soru Çözümü" } ?: "Soru Çözümü"
                val dayOfWeek = call.args["dayOfWeek"]?.toIntOrNull()?.coerceIn(1, 7) ?: 1
                val scheduledTime = call.args["scheduledTime"]?.takeIf { it.isNotBlank() } ?: "16:00 - 17:30"
                val targetQuestions = (call.args["targetQuestions"]?.toIntOrNull() ?: 30).coerceAtLeast(20)

                val dayName = dayOfWeekToTurkishName(dayOfWeek)

                val planEntity = PlanEntity(
                    id = UUID.randomUUID().toString(),
                    folderId = "",
                    title = "$subject - $topic",
                    subject = subject,
                    topic = topic,
                    dayOfWeek = dayOfWeek,
                    scheduledTime = scheduledTime,
                    targetQuestions = targetQuestions,
                    orderIndex = 0
                )

                val folderId = planRepositoryImpl.saveGeneratedPlan(
                    folderTitle = "Shark AI Çalışma Planı",
                    tasks = listOf(planEntity)
                )

                targetRepository.addTarget(
                    target = TargetEntity(
                        title = "$dayName • $subject",
                        description = "$topic ($targetQuestions Soru)",
                        type = TargetType.SHORT_TERM,
                        period = TargetPeriod.DAILY,
                        deadlineMillis = System.currentTimeMillis() + (dayOfWeek - 1) * 86_400_000L,
                        targetQuestions = targetQuestions,
                        priority = "HIGH",
                        category = subject
                    ),
                    tasks = listOf("$topic ($targetQuestions Soru)")
                )

                ToolExecutionOutcome(
                    note = "📅 Çalışma Planına Eklendi: **$dayName ($scheduledTime)** — $subject: $topic ($targetQuestions Soru) #ACTION:NAV_PLANS",
                    planFolderId = folderId
                )
            }

            else -> ToolExecutionOutcome(note = "")
        }
    }

    private fun dayOfWeekToTurkishName(day: Int): String = when (day) {
        1 -> "Pazartesi"
        2 -> "Salı"
        3 -> "Çarşamba"
        4 -> "Perşembe"
        5 -> "Cuma"
        6 -> "Cumartesi"
        7 -> "Pazar"
        else -> "Pazartesi"
    }

    private fun inferMimeType(uri: Uri): String {
        val path = uri.toString().lowercase()
        return when {
            path.endsWith(".pdf") -> "application/pdf"
            path.endsWith(".png") -> "image/png"
            path.endsWith(".webp") -> "image/webp"
            else -> "image/jpeg"
        }
    }
}
