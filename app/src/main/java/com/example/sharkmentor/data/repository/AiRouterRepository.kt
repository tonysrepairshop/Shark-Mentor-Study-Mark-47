package com.example.sharkmentor.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.example.sharkmentor.core.ai.AiProviderChoice
import com.example.sharkmentor.core.ai.DeepSeekClient
import com.example.sharkmentor.core.ai.GeminiApiException
import com.example.sharkmentor.core.ai.GeminiChatTurn
import com.example.sharkmentor.core.ai.GeminiFunctionDeclaration
import com.example.sharkmentor.core.ai.GeminiRestClient
import com.example.sharkmentor.core.ai.GeminiThinkingLevel
import com.example.sharkmentor.core.ai.UserAiPreferences
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
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
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
    )
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
        "ekle",
        "tüm konular",
        "tum konular",
        "müfredat",
        "mufredat",
        "konu hakimiyeti",
        "kütüphane",
        "kutuphane",
        "kitabını",
        "kitabini",
        "kitapta",
        "sayfaya kadar",
        "teste kadar",
        "işaretle",
        "isaretle",
        "hedef ekle",
        "hedefi güncelle",
        "hedefimi"
    )

    private val AUTONOMOUS_TOOL_KEYWORDS = listOf(
        "haftalık plan",
        "haftalik plan",
        "çalışma programı",
        "calisma programi",
        "plana ekle",
        "tüm konular",
        "tum konular",
        "müfredat",
        "mufredat",
        "konu hakimiyeti",
        "sayfaya kadar",
        "teste kadar",
        "işaretle",
        "isaretle",
        "kitap ekle",
        "kaynak ekle",
        "hedef ekle",
        "hedefleri ekle",
        "hedefi güncelle"
    )

    private val MATH_SYMBOL_REGEX = Regex("([=\\+\\^√∫∑π÷×]|\\d+\\s*[\\+\\-\\*/=]\\s*\\d+)")

    fun isComplexAutonomousActionIntent(text: String): Boolean {
        val normalized = text.lowercase(Locale("tr")).trim()
        return AUTONOMOUS_TOOL_KEYWORDS.any { normalized.contains(it) }
    }

    fun classify(text: String, hasAttachment: Boolean = false): SharkAiEngine {
        val normalized = text.lowercase(Locale("tr")).trim()

        // Rule A (Twin Question)
        if (TWIN_KEYWORDS.any { normalized.contains(it) }) {
            return SharkAiEngine.TWIN_QUESTION_ENGINE
        }

        // Rule B (Planner / Autonomous Tool / Topic Mastery / Resource / Target Organizer)
        if (PLANNER_KEYWORDS.any { normalized.contains(it) }) {
            return SharkAiEngine.PLANNER_OR_ORGANIZER
        }

        // Rule C (Problem Solver / Scanner)
        // Image attachment alone does NOT force SolverEngine; user decides via prompt or action chips
        if (SOLVER_KEYWORDS.any { normalized.contains(it) } ||
            MATH_SYMBOL_REGEX.containsMatchIn(normalized)
        ) {
            return SharkAiEngine.SOLVER_ENGINE
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

    init {
        UserAiPreferences.initialize(context)
    }

    companion object {
        const val SOLE_GEMINI_MODEL = "gemini-3.6-flash"

        const val MANDATORY_PERSONA_RULES = """
KESİN DAVRANIŞ VE ÜSLUP KURALLARI (ASLA İHLAL ETME):
1. SIFIR YALAKALIK & GİRİŞ YASAĞI: "Harika bir soru!", "Çok güzel düşünmüşsün!", "Merhaba! Sana bugün nasıl yardımcı olabilirim?" gibi yapay övgüleri ve kurumsal giriş cümlelerini KESİNLİKLE KULLANMA. Doğrudan konuya veya çözüme gir.
2. ORANTILI UZUNLUK:
   - Kullanıcı yalnızca "Merhaba", "Selam", "Nasılsın" gibi kısa bir selam verirse MAKSİMUM 1 KISA CÜMLE ile yanıt ver (Örn: "Selam! Bugün hangi derse veya soruya odaklanıyoruz?"). Selamlamaya asla paragraflarca yanıt yazma.
   - Seçilen yanıt gücüne (Hızlı vs Detaylı) sadık kal.
3. SOHBET KANCASI (KAPIYI KAPATMA):
   - Yanıtlarını "Başarılar dilerim, başka sorun varsa sorabilirsin" gibi sohbeti bitiren klişelerle BİTİRME.
   - Bunun yerine öğrenciyi akışta tutan tek cümlelik doğal bir takip sorusu (kanca) sor (Örn: "Bu adımda kafana yatmayan bir yer var mı, yoksa benzer bir ikiz soruyla pekiştirelim mi?").
"""

        fun getDepthDirective(modelPower: AiModelPower): String = when (modelPower) {
            AiModelPower.FLASH ->
                "\n[YANIT DERİNLİĞİ: HIZLI MOD (DÜŞÜK KOTA)] -> Lafı uzatma; 3-5 net, vurucu cümlede doğrudan özeti veya pratik çözümü ver."
            AiModelPower.PRO ->
                "\n[YANIT DERİNLİĞİ: DETAYLI MOD (DERİN SENTEZ)] -> Konuyu veya soruyu yüzeysel geçme; mantığını, adım adım çıkarımını, kavramsal arka planını, ÖSYM/MEB sınav tuzaklarını ve somut örneklerini derinlemesine açıkla."
        }

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
Sen SharkMentor Otonom Programlayıcı, Kütüphane ve Konu Hakimiyeti Motorusun.
Öğrencinin haftalık çalışma planını hazırlar, takvime görev ekler, mevcut kaynak/kitap ilerlemesini günceller, yeni kaynak ekler ve TYT/AYT konularını eksiksiz olarak konu hakimiyetine işlersin.

KURALLAR VE EYLEM YÖNETİMİ:
1. Sohbet metninde öğrenciye hitaben 2-4 cümlelik samimi, motive edici ve net bir Türkçe açıklama/özet yaz.
2. Yapılan veya önerilen işlemi sisteme işlemek için mesajının EN SONUNA ilgili JSON eylem bloğunu (#ACTION:{...}#) ekle:

HAFTALIK 7 GÜNLÜK PLAN OLUŞTURMA:
#ACTION:{"type":"CREATE_WEEKLY_PLAN","title":"Haftalık Çalışma Programı","summary":"Strateji özeti","schedule":[{"dayOfWeek":1,"timeSlot":"09:00 - 10:30","subject":"Matematik","topic":"Türev","targetQuestions":40}]}#

TEKİL GÖREV EKLEME:
#ACTION:{"type":"INSERT_PLAN_TASK","day":1,"time":"16:00 - 17:30","subject":"Fizik","topic":"Kuvvet ve Hareket","questions":30}#

TOPLU KONU HAKİMİYETİ EKLEME (Örn: "TYT tüm konular", "AYT tüm konular" veya müfredat görseli/listesi istendiğinde ASLA tek boş klasör açma; preset veya topics dizisiyle tüm alt konuları gönder):
#ACTION:{"type":"INSERT_TOPICS_BATCH","exam":"TYT","preset":"TYT_ALL","topics":[{"subject":"TYT Türkçe","topic":"Sözcükte Anlam","targetLevel":5},{"subject":"TYT Matematik","topic":"Temel Kavramlar","targetLevel":5}]}#

TEKİL KONU HEDEFİ EKLEME / GÜNCELLEME:
#ACTION:{"type":"INSERT_TOPIC","subject":"Biyoloji","topic":"Kalıtım ve Genetik","currentLevel":1,"targetLevel":5}#
#ACTION:{"type":"UPDATE_TOPIC_PROGRESS","subject":"Biyoloji","topic":"Kalıtım ve Genetik","currentLevel":4}#

MEVCUT KAYNAK / KİTAP İLERLEMESİ GÜNCELLEME (Öğrenci "X kitabını 45. sayfaya/teste kadar çözdüm işaretle" dediğinde YENİ KİTAP AÇMA, mevcut kitabı güncelle!):
#ACTION:{"type":"UPDATE_RESOURCE_PROGRESS","resourceId":0,"title":"Kitap Adı","completedUnits":45,"unitType":"Sayfa","isIncremental":false}#

YENİ KAYNAK / KİTAP EKLEME (Yalnızca kütüphanede henüz yoksa):
#ACTION:{"type":"INSERT_RESOURCE","title":"3D AYT Matematik Soru Bankası","subject":"Matematik","totalUnits":48,"completedUnits":0,"unitType":"Test"}#

TEKİL VEYA TOPLU HEDEF EKLEME:
#ACTION:{"type":"INSERT_TARGET","title":"TYT Türkçe 35+ Net","description":"Haftalık deneme ve paragraf rutini","type":"SHORT_TERM","period":"WEEKLY","targetQuestions":150,"priority":"HIGH"}#
#ACTION:{"type":"INSERT_TARGETS_BATCH","targets":[{"title":"TYT Türkçe 35+ Net","description":"Haftalık paragraf rutini","type":"SHORT_TERM","period":"WEEKLY","targetQuestions":150,"priority":"HIGH"}]}#

MEVCUT HEDEF İLERLEMESİNİ GÜNCELLEME:
#ACTION:{"type":"UPDATE_TARGET_PROGRESS","targetId":0,"title":"TYT Türkçe 35+ Net","solvedQuestions":60,"isCompleted":false,"isIncremental":false}#
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

    private val autonomousToolDeclarations: List<GeminiFunctionDeclaration> by lazy {
        listOf(
            GeminiFunctionDeclaration(
                name = "organizeErrorBank",
                description = "Organizes the student's Error Bank (Hata Kumbarası) by classifying untagged questions by subject/topic, creating a training folder, or saving an attached question into the error bank.",
                parametersJsonSchema = JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put(
                            "subject",
                            JSONObject()
                                .put("type", "STRING")
                                .put("description", "Subject name such as Matematik, Fizik, Kimya, Biyoloji, Türkçe, Tarih, Coğrafya")
                        )
                        put(
                            "topic",
                            JSONObject()
                                .put("type", "STRING")
                                .put("description", "Sub-topic name such as Türev, İntegral, Optik, Paragraf")
                        )
                        put(
                            "folderTitle",
                            JSONObject()
                                .put("type", "STRING")
                                .put("description", "Title for the organized training folder")
                        )
                    })
                    put("required", JSONArray().put("subject").put("topic"))
                }
            ),
            GeminiFunctionDeclaration(
                name = "addStudyPlanItem",
                description = "Adds a new study task or session item directly into the student's active Study Plan (Çalışma Planı).",
                parametersJsonSchema = JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put(
                            "subject",
                            JSONObject()
                                .put("type", "STRING")
                                .put("description", "Subject name (e.g. Matematik, Fizik, Türkçe)")
                        )
                        put(
                            "topic",
                            JSONObject()
                                .put("type", "STRING")
                                .put("description", "Topic or study focus (e.g. Türev Uygulamaları, Paragraf Rutini)")
                        )
                        put(
                            "dayOfWeek",
                            JSONObject()
                                .put("type", "INTEGER")
                                .put("description", "Day of week from 1 (Monday / Pazartesi) to 7 (Sunday / Pazar)")
                        )
                        put(
                            "scheduledTime",
                            JSONObject()
                                .put("type", "STRING")
                                .put("description", "Time slot formatted as HH:mm - HH:mm (e.g. 16:00 - 17:30)")
                        )
                        put(
                            "targetQuestions",
                            JSONObject()
                                .put("type", "INTEGER")
                                .put("description", "Target number of questions to solve (minimum 20, default 30)")
                        )
                    })
                    put("required", JSONArray().put("subject").put("topic").put("dayOfWeek"))
                }
            )
        )
    }

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

        val maxTokens = 4096

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

        val maxTokens = 4096

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

        val maxTokens = 6144

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
     * Dedicated Vision & OCR pipeline powered by Google Gemini Vision (`gemini-3.6-flash` REST).
     */
    suspend fun executeGeminiVision(
        prompt: String,
        systemInstruction: String,
        attachmentUri: Uri,
        activeEngine: SharkAiEngine = SharkAiEngine.SOLVER_ENGINE,
        modelPower: AiModelPower = AiModelPower.FLASH,
        onPartialText: (String) -> Unit = {}
    ): Result<AiRouterResult> = withContext(Dispatchers.IO) {
        val attachments = resolveMultimodalAttachment(attachmentUri)
        val extraDecodedBitmaps = mutableListOf<Bitmap>()

        try {
            val enrichedSystemInstruction = buildString {
                append(systemInstruction.trim())
                append(getDepthDirective(modelPower))
            }.trim()

            val visionBitmaps = mutableListOf<Bitmap>()
            visionBitmaps.addAll(attachments.bitmaps)

            val inlineFiles = mutableListOf<Pair<String, ByteArray>>()
            if (visionBitmaps.isEmpty() && attachments.rawBytes != null) {
                val decoded = runCatching {
                    BitmapFactory.decodeByteArray(attachments.rawBytes, 0, attachments.rawBytes.size)
                }.getOrNull()
                if (decoded != null) {
                    extraDecodedBitmaps.add(decoded)
                    visionBitmaps.add(decoded)
                } else {
                    inlineFiles.add(attachments.mimeType to attachments.rawBytes)
                }
            }

            if (visionBitmaps.isEmpty() && inlineFiles.isEmpty()) {
                return@withContext Result.failure(IllegalStateException("Eklenen dosya veya görsel okunamadı."))
            }

            val thinkingLevel = if (modelPower == AiModelPower.PRO) {
                GeminiThinkingLevel.MEDIUM
            } else {
                GeminiThinkingLevel.LOW
            }
            val maxTokens = if (modelPower == AiModelPower.PRO) 12288 else 6144

            val geminiResult = GeminiRestClient.streamWithFailover(
                context = context,
                systemInstruction = enrichedSystemInstruction,
                userPrompt = prompt.trim().ifBlank { "Eklenen görseli veya belgeyi incele." },
                bitmaps = visionBitmaps,
                inlineFiles = inlineFiles,
                tools = emptyList(),
                responseJson = false,
                maxOutputTokens = maxTokens,
                thinkingLevel = thinkingLevel,
                timeoutMs = 75_000L,
                onPartialText = onPartialText
            )

            if (geminiResult.isSuccess) {
                val finalText = geminiResult.getOrThrow().text.trim().ifBlank {
                    "Görsel incelendi ve çözüldü."
                }
                val parsedActionNotes = parseInlineActionNotes(finalText)
                onPartialText(finalText)
                return@withContext Result.success(
                    AiRouterResult(
                        text = finalText,
                        executedToolNotes = parsedActionNotes,
                        createdPlanFolderId = null,
                        engineUsed = activeEngine
                    )
                )
            }

            val lastException = geminiResult.exceptionOrNull()
            if (lastException is CancellationException) throw lastException

            // Seamless fallback to Kimi Vision ONLY if Kimi key is configured
            val kimiKey = DeepSeekClient.getKimiApiKey(context)
            if (kimiKey.isNotBlank()) {
                val base64Img = extractBase64FromAttachment(attachments)
                if (base64Img != null) {
                    Log.i("SharkAiVision", "Gemini vision failed, falling back to Kimi Vision...")
                    try {
                        val kimiResult = DeepSeekClient.streamKimiVisionCompletion(
                            systemPrompt = enrichedSystemInstruction,
                            userPrompt = prompt,
                            base64Image = base64Img,
                            mimeType = attachments.mimeType,
                            maxTokens = if (modelPower == AiModelPower.PRO) 8192 else 4096,
                            context = context,
                            onAccumulatedChunk = onPartialText
                        )
                        if (kimiResult.isNotBlank()) {
                            val parsedActionNotes = parseInlineActionNotes(kimiResult)
                            return@withContext Result.success(
                                AiRouterResult(
                                    text = kimiResult,
                                    executedToolNotes = parsedActionNotes,
                                    createdPlanFolderId = null,
                                    engineUsed = activeEngine
                                )
                            )
                        }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (ke: Throwable) {
                        Log.w("SharkAiVision", "Kimi Vision fallback failed: ${ke.message}", ke)
                    }
                }
            }

            Result.failure(
                lastException ?: IllegalStateException("Vision modelleri yanıt vermedi.")
            )
        } finally {
            attachments.bitmaps.forEach { runCatching { if (!it.isRecycled) it.recycle() } }
            extraDecodedBitmaps.forEach { runCatching { if (!it.isRecycled) it.recycle() } }
        }
    }

    /**
     * Parses inline `#ACTION:{...}#` tags for both Text and Vision pipelines.
     */
    private suspend fun parseInlineActionNotes(resultText: String): List<String> {
        val parsedActionNotes = mutableListOf<String>()
        val actionRegex = Regex("#ACTION:(\\{.*?\\})#", setOf(RegexOption.DOT_MATCHES_ALL))
        for (match in actionRegex.findAll(resultText)) {
            val jsonPayload = match.groupValues[1]
            runCatching {
                val jsonObj = Gson().fromJson(jsonPayload, JsonObject::class.java)
                val actionType = jsonObj.get("type")?.asString?.uppercase(Locale.ROOT)
                when (actionType) {
                    "INSERT_TASK" -> {
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
                    "INSERT_TOPICS_BATCH" -> {
                        parsedActionNotes.add("Konu hakimiyeti listesi işlendi.")
                    }
                    "UPDATE_RESOURCE_PROGRESS" -> {
                        parsedActionNotes.add("Kütüphane kaynak ilerlemesi güncellendi.")
                    }
                    "INSERT_TARGETS_BATCH", "UPDATE_TARGET_PROGRESS" -> {
                        parsedActionNotes.add("Hedef ilerlemesi güncellendi.")
                    }
                }
            }
        }
        return parsedActionNotes
    }

    /**
     * Dedicated Text/Chat streaming pipeline:
     * - Respects `UserAiPreferences.selectedAiProvider` (`AUTO`, `GEMINI`, `DEEPSEEK`, `KIMI`).
     * - Forwards conversation `chatHistory` to Gemini as well as OpenAI-compatible providers.
     * - Skips DeepSeek/Kimi completely when user chose `GEMINI` or when no DeepSeek/Kimi keys exist.
     */
    suspend fun executeTextStream(
        activeEngine: SharkAiEngine = SharkAiEngine.CONVERSATIONAL_PEER,
        prompt: String,
        systemInstruction: String,
        chatHistory: List<com.example.sharkmentor.core.ai.OpenAiChatMessage> = emptyList(),
        modelPower: AiModelPower = AiModelPower.FLASH,
        onPartialText: (String) -> Unit = {}
    ): Result<AiRouterResult> = withContext(Dispatchers.IO) {
        val enrichedSystemInstruction = buildString {
            append(systemInstruction.trim())
            append(getDepthDirective(modelPower))
        }.trim()

        val selectedProvider = UserAiPreferences.getOrNull(context)?.getSelectedProvider() ?: AiProviderChoice.AUTO
        val hasOpenAiProviderKeys = DeepSeekClient.hasAnyOpenAiProviderKey(context)
        val geminiHistory = chatHistory.takeLast(6).map { msg ->
            GeminiChatTurn(role = msg.role, text = msg.content)
        }

        val isAutonomousActionRequest = activeEngine == SharkAiEngine.PLANNER_OR_ORGANIZER ||
            AutoIntentClassifier.isComplexAutonomousActionIntent(prompt)

        suspend fun runOpenAiProvider(): Result<AiRouterResult> {
            return try {
                val temp = if (modelPower == AiModelPower.PRO) 0.4f else 0.25f
                val maxTokens = when {
                    isAutonomousActionRequest -> 4096
                    modelPower == AiModelPower.PRO -> 4096
                    else -> 2048
                }
                val preferKimi = DeepSeekClient.isLongContextPrompt(prompt, enrichedSystemInstruction)
                val resultText = DeepSeekClient.streamChatCompletion(
                    systemPrompt = enrichedSystemInstruction,
                    userPrompt = prompt,
                    chatHistory = chatHistory,
                    temperature = temp,
                    maxTokens = maxTokens,
                    preferKimiLongContext = preferKimi,
                    context = context,
                    onAccumulatedChunk = onPartialText
                )
                val parsedActionNotes = parseInlineActionNotes(resultText)
                Result.success(
                    AiRouterResult(
                        text = resultText,
                        executedToolNotes = parsedActionNotes,
                        createdPlanFolderId = null,
                        engineUsed = activeEngine
                    )
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

        when (selectedProvider) {
            AiProviderChoice.GEMINI -> {
                return@withContext streamGeminiTextFallback(
                    activeEngine = activeEngine,
                    prompt = prompt,
                    systemInstruction = enrichedSystemInstruction,
                    chatHistory = geminiHistory,
                    modelPower = modelPower,
                    overrideMaxTokens = if (modelPower == AiModelPower.PRO) 16384 else 6144,
                    forceInlineActionMode = isAutonomousActionRequest,
                    onPartialText = onPartialText
                )
            }

            AiProviderChoice.DEEPSEEK, AiProviderChoice.KIMI -> {
                if (hasOpenAiProviderKeys) {
                    val openAiAttempt = runOpenAiProvider()
                    if (openAiAttempt.isSuccess) return@withContext openAiAttempt
                    Log.w(
                        "AiRouterRepository",
                        "Selected provider $selectedProvider failed (${openAiAttempt.exceptionOrNull()?.message}), falling back to gemini-3.6-flash..."
                    )
                }
                return@withContext streamGeminiTextFallback(
                    activeEngine = activeEngine,
                    prompt = prompt,
                    systemInstruction = enrichedSystemInstruction,
                    chatHistory = geminiHistory,
                    modelPower = modelPower,
                    overrideMaxTokens = if (modelPower == AiModelPower.PRO) 16384 else 6144,
                    forceInlineActionMode = isAutonomousActionRequest,
                    onPartialText = onPartialText
                )
            }

            AiProviderChoice.AUTO -> {
                if (isAutonomousActionRequest) {
                    val geminiActionAttempt = streamGeminiTextFallback(
                        activeEngine = activeEngine,
                        prompt = prompt,
                        systemInstruction = enrichedSystemInstruction,
                        chatHistory = geminiHistory,
                        modelPower = modelPower,
                        overrideMaxTokens = if (modelPower == AiModelPower.PRO) 12288 else 6144,
                        forceInlineActionMode = true,
                        onPartialText = onPartialText
                    )
                    if (geminiActionAttempt.isSuccess) {
                        return@withContext geminiActionAttempt
                    }
                    if (!hasOpenAiProviderKeys) {
                        return@withContext geminiActionAttempt
                    }
                    Log.w("AiRouterRepository", "Gemini autonomous action call failed, falling back to DeepSeek/Kimi...")
                    val openAiFallback = runOpenAiProvider()
                    return@withContext if (openAiFallback.isSuccess) openAiFallback else geminiActionAttempt
                }

                if (hasOpenAiProviderKeys) {
                    val openAiAttempt = runOpenAiProvider()
                    if (openAiAttempt.isSuccess) {
                        return@withContext openAiAttempt
                    }
                    Log.w(
                        "AiRouterRepository",
                        "DeepSeek/Kimi text stream failed (${openAiAttempt.exceptionOrNull()?.message}), falling back to gemini-3.6-flash..."
                    )
                }

                return@withContext streamGeminiTextFallback(
                    activeEngine = activeEngine,
                    prompt = prompt,
                    systemInstruction = enrichedSystemInstruction,
                    chatHistory = geminiHistory,
                    modelPower = modelPower,
                    onPartialText = onPartialText
                )
            }
        }
    }

    /**
     * Core hybrid router: Routes Multimodal/Vision to Gemini Vision, Text-only to DeepSeek/Kimi/Gemini.
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
                modelPower = modelPower,
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
     * Gemini (`gemini-3.6-flash`) streaming execution pipeline for complex autonomous action generation and critical fallback.
     */
    private suspend fun streamGeminiTextFallback(
        activeEngine: SharkAiEngine,
        prompt: String,
        systemInstruction: String,
        chatHistory: List<GeminiChatTurn> = emptyList(),
        modelPower: AiModelPower = AiModelPower.FLASH,
        overrideMaxTokens: Int? = null,
        forceInlineActionMode: Boolean = false,
        onPartialText: (String) -> Unit = {}
    ): Result<AiRouterResult> = withContext(Dispatchers.IO) {
        val shouldAttachTools = !forceInlineActionMode && activeEngine == SharkAiEngine.PLANNER_OR_ORGANIZER
        val thinkingLevel = if (modelPower == AiModelPower.PRO) {
            GeminiThinkingLevel.MEDIUM
        } else {
            GeminiThinkingLevel.LOW
        }
        val effectiveMaxTokens = overrideMaxTokens ?: if (modelPower == AiModelPower.PRO) 16384 else 6144

        var geminiResult = GeminiRestClient.streamWithFailover(
            context = context,
            systemInstruction = systemInstruction,
            userPrompt = prompt,
            chatHistory = chatHistory,
            tools = if (shouldAttachTools) autonomousToolDeclarations else emptyList(),
            responseJson = false,
            maxOutputTokens = effectiveMaxTokens,
            thinkingLevel = thinkingLevel,
            timeoutMs = 75_000L,
            onPartialText = onPartialText
        )

        val err = geminiResult.exceptionOrNull()
        if (err is CancellationException) throw err

        if (geminiResult.isFailure && shouldAttachTools && (err as? GeminiApiException)?.httpCode == 400) {
            Log.w("AiRouterRepository", "Tool schema returned HTTP 400; retrying without tools...")
            geminiResult = GeminiRestClient.streamWithFailover(
                context = context,
                systemInstruction = systemInstruction,
                userPrompt = prompt,
                chatHistory = chatHistory,
                tools = emptyList(),
                responseJson = false,
                maxOutputTokens = effectiveMaxTokens,
                thinkingLevel = thinkingLevel,
                timeoutMs = 75_000L,
                onPartialText = onPartialText
            )
        }

        if (geminiResult.isFailure) {
            val finalErr = geminiResult.exceptionOrNull()
            if (finalErr is CancellationException) throw finalErr
            return@withContext Result.failure(
                finalErr ?: IllegalStateException("Tüm AI modelleri ve yedek anahtarlar yanıt vermedi.")
            )
        }

        val response = geminiResult.getOrThrow()
        val toolNotes = mutableListOf<String>()
        var createdFolderId: String? = null

        if (response.functionCalls.isNotEmpty()) {
            for (call in response.functionCalls) {
                val executionResult = executeToolCall(call.name, call.args, null)
                if (executionResult.note.isNotBlank()) {
                    toolNotes.add(executionResult.note)
                }
                if (executionResult.planFolderId != null) {
                    createdFolderId = executionResult.planFolderId
                }
            }
        }

        val finalText = buildString {
            val modelText = response.text.trim()
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

        val inlineNotes = parseInlineActionNotes(finalText)
        toolNotes.addAll(inlineNotes)

        onPartialText(finalText)

        Result.success(
            AiRouterResult(
                text = finalText,
                executedToolNotes = toolNotes,
                createdPlanFolderId = createdFolderId,
                engineUsed = activeEngine
            )
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
        if (bmp != null && !bmp.isRecycled) {
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

    private data class ToolExecutionOutcome(
        val note: String,
        val planFolderId: String? = null
    )

    private suspend fun executeToolCall(
        callName: String,
        args: Map<String, String>,
        attachmentUri: Uri?
    ): ToolExecutionOutcome = withContext(Dispatchers.IO) {
        when (callName) {
            "organizeErrorBank" -> {
                val subject = args["subject"]?.ifBlank { "Matematik" } ?: "Matematik"
                val topic = args["topic"]?.ifBlank { "Genel" } ?: "Genel"
                val folderTitle = args["folderTitle"]?.takeIf { it.isNotBlank() }
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
