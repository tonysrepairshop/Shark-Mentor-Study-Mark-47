package com.example.sharkmentor.core.coaching

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.compose.runtime.Immutable
import com.example.sharkmentor.core.ai.ApiKeyRotator
import com.example.sharkmentor.core.ai.GeminiRestClient
import com.example.sharkmentor.core.ai.UserAiPreferences
import com.example.sharkmentor.data.local.SharkMentorDatabase
import com.example.sharkmentor.data.local.entity.PlanEntity
import com.example.sharkmentor.data.local.entity.PlanFolderEntity
import com.example.sharkmentor.data.local.entity.ResourceEntity
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

@Immutable
data class CampCatalogTemplate(
    val id: String,
    val title: String,
    val instructor: String,
    val subject: String,
    val totalDays: Int,
    val matchKeywords: List<String>,
    val defaultPlaylistId: String = "",
    val defaultVideoId: String = "",
    val dayTopics: List<String> = emptyList()
)

@Immutable
data class CampDayEpisode(
    val dayNumber: Int,
    val topicTitle: String,
    val videoId: String = "",
    val playlistId: String = "",
    val customUrl: String = "",
    val searchQuery: String = "",
    val isCompleted: Boolean = false
) {
    fun buildExternalYouTubeUrl(): String {
        if (customUrl.isNotBlank() && (customUrl.startsWith("http://") || customUrl.startsWith("https://"))) {
            if (videoId.isNotBlank() || playlistId.isNotBlank()) return customUrl
        }
        if (videoId.isNotBlank() && playlistId.isNotBlank()) {
            return "https://www.youtube.com/watch?v=$videoId&list=$playlistId"
        }
        if (videoId.isNotBlank()) {
            return "https://www.youtube.com/watch?v=$videoId"
        }
        if (playlistId.isNotBlank()) {
            return "https://www.youtube.com/playlist?list=$playlistId&index=$dayNumber"
        }
        val q = Uri.encode(searchQuery.ifBlank { "$dayNumber. Gün $topicTitle" })
        return "https://www.youtube.com/results?search_query=$q"
    }

    fun buildInAppEmbedUrl(): String {
        if (videoId.isNotBlank() && playlistId.isNotBlank()) {
            return "https://www.youtube-nocookie.com/embed/$videoId?list=$playlistId&rel=0&modestbranding=1&playsinline=1"
        }
        if (videoId.isNotBlank()) {
            return "https://www.youtube-nocookie.com/embed/$videoId?rel=0&modestbranding=1&playsinline=1"
        }
        if (playlistId.isNotBlank()) {
            val zeroIdx = (dayNumber - 1).coerceAtLeast(0)
            return "https://www.youtube-nocookie.com/embed/videoseries?list=$playlistId&index=$zeroIdx&rel=0&modestbranding=1&playsinline=1"
        }
        val q = Uri.encode(searchQuery.ifBlank { "$dayNumber. Gün $topicTitle" })
        return "https://m.youtube.com/results?search_query=$q"
    }
}

@Immutable
data class ResourceCampBinding(
    val resourceId: Long,
    val resourceTitle: String,
    val subject: String,
    val campTitle: String,
    val instructor: String,
    val totalDays: Int,
    val currentDay: Int = 1,
    val youtubeUrl: String = "",
    val videoId: String = "",
    val playlistId: String = "",
    val episodes: List<CampDayEpisode> = emptyList(),
    val isAutoMatched: Boolean = false
) {
    fun currentEpisode(): CampDayEpisode {
        val safeDay = currentDay.coerceIn(1, totalDays.coerceAtLeast(1))
        return episodes.firstOrNull { it.dayNumber == safeDay }
            ?: episodes.getOrNull(safeDay - 1)
            ?: CampDayEpisode(
                dayNumber = safeDay,
                topicTitle = "$subject • $safeDay. Gün",
                videoId = videoId,
                playlistId = playlistId,
                customUrl = youtubeUrl,
                searchQuery = "$campTitle $instructor $safeDay. gün"
            )
    }
}

/**
 * Triple-Hybrid Book Camp Discovery, Daily Slicing & Schedule Integration Engine:
 * 1. Verified Camp Catalog for popular Turkish exam books & YouTube teacher camps.
 * 2. User YouTube Playlist/Video URL parser + AI day-by-day episode slicer.
 * 3. Instant task/resource camp matching + 1-tap weekly plan distribution.
 */
object BookCampManager {

    private const val PREFS_NAME = "shark_mark57_book_camps_prefs"
    private const val KEY_BINDINGS_JSON = "resource_camp_bindings_json"

    @Volatile
    private var isLoaded = false

    private val _bindingsByResourceId = MutableStateFlow<Map<Long, ResourceCampBinding>>(emptyMap())
    val bindingsByResourceId: StateFlow<Map<Long, ResourceCampBinding>> = _bindingsByResourceId.asStateFlow()

    private val tytMathTopics = listOf(
        "Temel Kavramlar", "Sayı Basamakları", "Bölme ve Bölünebilme", "EBOB - EKOK",
        "Rasyonel Sayılar", "Birinci Dereceden Denklemler", "Basit Eşitsizlikler", "Mutlak Değer",
        "Üslü Sayılar", "Köklü Sayılar", "Çarpanlara Ayırma", "Oran - Orantı",
        "Sayı ve Kesir Problemleri", "Yaş Problemleri", "Yüzde ve Kar-Zarar Problemleri",
        "Karışım ve Hareket Problemleri", "İşçi ve Rutin Olmayan Problemler", "Kümeler",
        "Fonksiyonlar", "Polinomlar", "İkinci Dereceden Denklemler", "Permütasyon - Kombinasyon",
        "Binom ve Olasılık", "Veri ve İstatistik"
    )

    private val aytMathTopics = listOf(
        "Fonksiyon Uygulamaları", "Polinomlar ve Çarpanlara Ayırma", "İkinci Dereceden Denklemler & Karmaşık Sayılar",
        "Parabol", "Eşitsizlikler ve Eşitsizlik Sistemleri", "Trigonometri - 1 (Birim Çember & Özdeşlikler)",
        "Trigonometri - 2 (Toplam-Fark & Yarım Açı)", "Trigonometrik Denklemler", "Logaritma Fonksiyonu",
        "Logaritmalı Denklemler ve Eşitsizlikler", "Diziler (Aritmetik ve Geometrik)", "Limit ve Süreklilik",
        "Türev Tanımı ve Alma Kuralları", "Türevin Geometrik Yorumu", "Maksimum - Minimum Problemleri",
        "Belirsiz İntegral", "Belirli İntegral ve Alan Hesabı", "Çemberin Analitik İncelenmesi"
    )

    private val geometryTopics = listOf(
        "Doğruda ve Üçgende Açılar", "Dik ve Özel Üçgenler", "İkizkenar ve Eşkenar Üçgen",
        "Üçgende Açıortay ve Kenarortay", "Üçgende Eşlik ve Benzerlik", "Üçgende Alan",
        "Çokgenler ve Genel Dörtgenler", "Yamuk ve Paralelkenar", "Eşkenar Dörtgen, Dikdörtgen ve Kare",
        "Çemberde Açı ve Uzunluk", "Dairede Alan", "Noktanın ve Doğrunun Analitiği",
        "Katı Cisimler (Prizma, Piramit, Küre)", "Dönüşüm Geometrisi"
    )

    private val physicsTopics = listOf(
        "Fizik Bilimine Giriş & Madde ve Özellikleri", "Hareket ve Kuvvet", "İş, Güç ve Enerji",
        "Isı, Sıcaklık ve Genleşme", "Elektrostatik ve Elektrik Akımı", "Manyetizma ve Basınç",
        "Kaldırma Kuvveti ve Dalgalar", "Optik (Aydınlanma, Ayna, Mercek)", "Vektörler ve Bağıl Hareket",
        "Newton'un Hareket Yasaları", "Bir ve İki Boyutta Sabit İvmeli Hareket", "İtme ve Çizgisel Momentum",
        "Tork, Denge ve Kütle Merkezi", "Çembersel Hareket ve Kepler", "Basit Harmonik Hareket",
        "Elektrik Alan, Potansiyel ve Sığa", "Manyetik Alan ve İndüksiyon", "Alternatif Akım ve Modern Fizik"
    )

    private val chemistryTopics = listOf(
        "Kimya Bilimi ve Atomun Yapısı", "Periyodik Sistem", "Kimyasal Türler Arası Etkileşimler",
        "Maddenin Halleri ve Doğa", "Kimyanın Temel Kanunları ve Mol", "Kimyasal Hesaplamalar",
        "Karışımlar ve Ayırma Teknikleri", "Asitler, Bazlar ve Tuzlar", "Modern Atom Teorisi",
        "Gazlar", "Sıvı Çözeltiler ve Çözünürlük", "Kimyasal Tepkimelerde Enerji",
        "Kimyasal Tepkimelerde Hız", "Kimyasal Tepkimelerde Denge", "Asit-Baz ve Çözünürlük Dengesi",
        "Kimya ve Elektrik (Elektrokimya)", "Karbon Kimyasına Giriş ve Organik Kimya"
    )

    private val biologyTopics = listOf(
        "Canlıların Ortak Özellikleri ve Temel Bileşenler", "Hücre ve Organeller",
        "Hücre Zarından Madde Geçişleri", "Canlıların Sınıflandırılması", "Hücre Bölünmeleri (Mitoz & Mayoz)",
        "Eşeyli ve Eşeysiz Üreme", "Kalıtım ve Genetik", "Ekosistem Ekolojisi",
        "Sinir Sistemi ve Endokrin Sistem", "Duyu Organları ve Destek-Hareket",
        "Sindirim ve Dolaşım Sistemi", "Solunum ve Üriner Sistem", "Komünite ve Popülasyon Ekolojisi",
        "Genden Proteine (Nükleik Asitler & Protein Sentezi)", "Canlılarda Enerji Dönüşümleri (Fotosentez & Solunum)",
        "Bitki Biyolojisi"
    )

    private val turkishTopics = listOf(
        "Sözcükte Anlam", "Cümlede Anlam", "Paragrafta Yapı ve Konu-Ana Düşünce",
        "Paragrafta Yardımcı Düşünce", "Ses Bilgisi", "Yazım Kuralları", "Noktalama İşaretleri",
        "Sözcükte Yapı ve Ekler", "İsim, Sıfat, Zamir ve Zarf", "Fiiller, Fiilimsiler ve Ek Fiil",
        "Cümlenin Ögeleri", "Fiilde Çatı ve Cümle Türleri", "Anlatım Bozuklukları"
    )

    val BuiltInCampCatalog: List<CampCatalogTemplate> = listOf(
        CampCatalogTemplate(
            id = "3d_tyt_mat_vdd",
            title = "3D TYT Matematik VDD Kampı",
            instructor = "Eyüp B.",
            subject = "TYT Matematik",
            totalDays = 45,
            matchKeywords = listOf("3d", "vdd", "eyüp", "eyup", "tyt matematik"),
            dayTopics = expandTopicsToDays(tytMathTopics, 45)
        ),
        CampCatalogTemplate(
            id = "3d_ayt_mat_vdd",
            title = "3D AYT Matematik VDD Kampı",
            instructor = "Eyüp B.",
            subject = "AYT Matematik",
            totalDays = 50,
            matchKeywords = listOf("3d ayt", "ayt vdd", "eyüp b ayt", "ayt matematik"),
            dayTopics = expandTopicsToDays(aytMathTopics, 50)
        ),
        CampCatalogTemplate(
            id = "mert_hoca_70_tyt",
            title = "70 Günde TYT Matematik Kampı",
            instructor = "Mert Hoca",
            subject = "TYT Matematik",
            totalDays = 70,
            matchKeywords = listOf("mert hoca", "70 günde", "70 gunde", "tyt mat"),
            dayTopics = expandTopicsToDays(tytMathTopics, 70)
        ),
        CampCatalogTemplate(
            id = "mert_hoca_60_ayt",
            title = "60 Günde AYT Matematik Kampı",
            instructor = "Mert Hoca",
            subject = "AYT Matematik",
            totalDays = 60,
            matchKeywords = listOf("mert hoca ayt", "60 günde", "60 gunde", "ayt mat"),
            dayTopics = expandTopicsToDays(aytMathTopics, 60)
        ),
        CampCatalogTemplate(
            id = "rehber_mat_49_tyt",
            title = "49 Günde TYT Matematik Kampı",
            instructor = "Rehber Matematik",
            subject = "TYT Matematik",
            totalDays = 49,
            matchKeywords = listOf("rehber", "49 günde", "49 gunde"),
            dayTopics = expandTopicsToDays(tytMathTopics, 49)
        ),
        CampCatalogTemplate(
            id = "orijinal_mat_kamp",
            title = "Orijinal Matematik Soru & Konu Kampı",
            instructor = "Orijinal Yayınları",
            subject = "Matematik",
            totalDays = 40,
            matchKeywords = listOf("orijinal", "orjinal", "mikro orijinal"),
            dayTopics = expandTopicsToDays(tytMathTopics + aytMathTopics.take(10), 40)
        ),
        CampCatalogTemplate(
            id = "apotemi_fasikul_kamp",
            title = "Apotemi Fasikül Maraton Kampı",
            instructor = "Apotemi Yayınları",
            subject = "AYT Matematik",
            totalDays = 30,
            matchKeywords = listOf("apotemi", "fasikül", "fasikul", "türev", "integral", "trigonometri", "fonksiyon"),
            dayTopics = expandTopicsToDays(aytMathTopics, 30)
        ),
        CampCatalogTemplate(
            id = "baris_yayinlari_mat",
            title = "Barış Yayınları Matematik Kampı",
            instructor = "Barış Çelenk",
            subject = "Matematik",
            totalDays = 36,
            matchKeywords = listOf("barış", "baris", "çelenk"),
            dayTopics = expandTopicsToDays(aytMathTopics, 36)
        ),
        CampCatalogTemplate(
            id = "kenan_kara_50_geo",
            title = "50 Günde TYT-AYT Geometri Kampı",
            instructor = "Kenan Kara",
            subject = "Geometri",
            totalDays = 50,
            matchKeywords = listOf("kenan kara", "geometri", "acill geometri", "345 geometri", "birey"),
            dayTopics = expandTopicsToDays(geometryTopics, 50)
        ),
        CampCatalogTemplate(
            id = "ozcan_aykin_fizik",
            title = "TYT-AYT Fizik Video Ders Kampı",
            instructor = "Özcan Aykın",
            subject = "Fizik",
            totalDays = 40,
            matchKeywords = listOf("özcan", "ozcan", "aykın", "fizik", "345 fizik", "ertan sinan"),
            dayTopics = expandTopicsToDays(physicsTopics, 40)
        ),
        CampCatalogTemplate(
            id = "vip_fizik_50",
            title = "50 Günde Fizik Kampı",
            instructor = "VIP Fizik",
            subject = "Fizik",
            totalDays = 50,
            matchKeywords = listOf("vip fizik", "50 günde fizik"),
            dayTopics = expandTopicsToDays(physicsTopics, 50)
        ),
        CampCatalogTemplate(
            id = "gorkem_sahin_kimya",
            title = "TYT-AYT Kimya Video Ders Kampı",
            instructor = "Görkem Şahin",
            subject = "Kimya",
            totalDays = 36,
            matchKeywords = listOf("görkem", "gorkem", "şahin", "kimya", "orbital", "aydın kimya", "palme kimya"),
            dayTopics = expandTopicsToDays(chemistryTopics, 36)
        ),
        CampCatalogTemplate(
            id = "dr_biyoloji_kamp",
            title = "TYT-AYT Biyoloji Kampı",
            instructor = "Dr. Biyoloji",
            subject = "Biyoloji",
            totalDays = 30,
            matchKeywords = listOf("dr biyoloji", "dr. biyoloji", "biyoloji", "biyotik", "palme biyoloji", "selin hoca"),
            dayTopics = expandTopicsToDays(biologyTopics, 30)
        ),
        CampCatalogTemplate(
            id = "rustu_hoca_49_turkce",
            title = "49 Günde TYT Türkçe & Paragraf Kampı",
            instructor = "Rüştü Hoca",
            subject = "Türkçe",
            totalDays = 49,
            matchKeywords = listOf("rüştü", "rustu", "türkçe", "turkce", "paragraf", "hız ve renk", "limit türkçe", "sıfır risk"),
            dayTopics = expandTopicsToDays(turkishTopics, 49)
        ),
        CampCatalogTemplate(
            id = "benim_hocam_tarih_kpss",
            title = "Tarih & Genel Kültür Video Ders Kampı",
            instructor = "Ramazan Yetgin",
            subject = "Tarih",
            totalDays = 35,
            matchKeywords = listOf("tarih", "kpss", "ramazan yetgin", "benim hocam"),
            dayTopics = (1..35).map { "Tarih Kampı • $it. Gün Konu & Soru" }
        ),
        CampCatalogTemplate(
            id = "tus_hizli_kamp",
            title = "TUS Temel & Klinik Kampı",
            instructor = "TUS Akademi",
            subject = "TUS",
            totalDays = 30,
            matchKeywords = listOf("tus", "klinik", "temel bilimler", "anatomi", "patoloji", "dahiliye"),
            dayTopics = (1..30).map { "TUS Kampı • $it. Gün Vaka & Spot Tekrar" }
        )
    )

    private fun expandTopicsToDays(topics: List<String>, totalDays: Int): List<String> {
        if (topics.isEmpty()) return (1..totalDays).map { "$it. Gün Konu Anlatımı" }
        return (0 until totalDays).map { idx ->
            val topicIdx = (idx * topics.size) / totalDays.coerceAtLeast(1)
            val topic = topics[topicIdx.coerceIn(0, topics.lastIndex)]
            val part = (idx % 2) + 1
            if (totalDays > topics.size * 1.3) "$topic ($part. Bölüm)" else topic
        }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun ensureLoaded(context: Context) {
        if (isLoaded) return
        synchronized(this) {
            if (isLoaded) return
            _bindingsByResourceId.value = loadBindingsFromPrefs(context.applicationContext)
            isLoaded = true
        }
    }

    /**
     * Extracts YouTube `(videoId, playlistId)` from any standard YouTube watch, short, embed, or playlist URL.
     */
    fun parseYouTubeUrlIds(rawUrl: String): Pair<String, String> {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) return "" to ""
        var videoId = ""
        var playlistId = ""

        runCatching {
            val listMatch = Regex("""[?&]list=([a-zA-Z0-9_-]+)""").find(trimmed)
            if (listMatch != null) {
                playlistId = listMatch.groupValues[1]
            }
            val vMatch = Regex("""(?:v=|youtu\.be/|embed/|shorts/|live/)([a-zA-Z0-9_-]{11})""").find(trimmed)
            if (vMatch != null) {
                videoId = vMatch.groupValues[1]
            }
        }
        return videoId to playlistId
    }

    /**
     * Resolves either the user-saved [ResourceCampBinding] or an automatic catalog match for [resource].
     */
    fun resolveCampForResource(context: Context, resource: ResourceEntity): ResourceCampBinding {
        ensureLoaded(context)
        _bindingsByResourceId.value[resource.id]?.let { saved ->
            val syncedCurrentDay = if (resource.totalUnits > 0 && resource.completedUnits > 0) {
                ((resource.completedUnits.toFloat() / resource.totalUnits.toFloat()) * saved.totalDays)
                    .toInt()
                    .plus(1)
                    .coerceIn(1, saved.totalDays)
            } else {
                saved.currentDay.coerceIn(1, saved.totalDays.coerceAtLeast(1))
            }
            return saved.copy(currentDay = syncedCurrentDay)
        }

        // Auto-match against BuiltInCampCatalog by title + subject
        val hay = "${resource.title} ${resource.subject}".lowercase(Locale("tr"))
        val matchedTemplate = BuiltInCampCatalog.firstOrNull { tpl ->
            tpl.matchKeywords.any { kw -> hay.contains(kw.lowercase(Locale("tr"))) }
        } ?: BuiltInCampCatalog.firstOrNull { tpl ->
            hay.contains(tpl.subject.lowercase(Locale("tr")).substringAfter(" "))
        }

        val totalDays = matchedTemplate?.totalDays ?: (resource.totalUnits.takeIf { it in 5..90 } ?: 30)
        val campTitle = matchedTemplate?.title ?: "${resource.title} Video Kampı"
        val instructor = matchedTemplate?.instructor ?: resource.subject
        val topics = matchedTemplate?.dayTopics ?: defaultTopicsForSubject(resource.subject, totalDays)

        val currentDay = if (resource.totalUnits > 0 && resource.completedUnits > 0) {
            ((resource.completedUnits.toFloat() / resource.totalUnits.toFloat()) * totalDays)
                .toInt()
                .plus(1)
                .coerceIn(1, totalDays)
        } else 1

        val episodes = (1..totalDays).map { day ->
            val topic = topics.getOrElse(day - 1) { "${resource.subject} • $day. Gün" }
            CampDayEpisode(
                dayNumber = day,
                topicTitle = topic,
                videoId = matchedTemplate?.defaultVideoId.orEmpty(),
                playlistId = matchedTemplate?.defaultPlaylistId.orEmpty(),
                searchQuery = "${resource.title} $instructor ${resource.subject} $day. gün $topic",
                isCompleted = day < currentDay
            )
        }

        return ResourceCampBinding(
            resourceId = resource.id,
            resourceTitle = resource.title,
            subject = resource.subject,
            campTitle = campTitle,
            instructor = instructor,
            totalDays = totalDays,
            currentDay = currentDay,
            episodes = episodes,
            isAutoMatched = true
        )
    }

    /**
     * Resolves a [CampDayEpisode] for any weekly plan box ([PlanEntity]) or Duolingo path node,
     * linking it to the student's matched library book camp or subject camp.
     */
    fun resolveCampEpisodeForTask(
        context: Context,
        subject: String,
        topic: String,
        taskTitle: String = "",
        dayHint: Int = 1,
        libraryResources: List<ResourceEntity> = emptyList()
    ): Pair<String, CampDayEpisode> {
        ensureLoaded(context)
        val cleanSubj = subject.trim().ifBlank { "Matematik" }
        val cleanTopic = topic.trim().ifBlank { taskTitle.trim() }.ifBlank { cleanSubj }

        // Check if task explicitly mentions "X. Gün"
        val explicitDay = Regex("""(\d{1,2})\.\s*[Gg]ün""").find("$cleanTopic $taskTitle")
            ?.groupValues?.getOrNull(1)?.toIntOrNull()

        // 1. Check user's bound library resources for this subject
        val matchingBinding = _bindingsByResourceId.value.values.firstOrNull {
            it.subject.contains(cleanSubj, ignoreCase = true) || cleanSubj.contains(it.subject, ignoreCase = true)
        } ?: libraryResources.firstOrNull {
            it.subject.contains(cleanSubj, ignoreCase = true) || cleanSubj.contains(it.subject, ignoreCase = true)
        }?.let { resolveCampForResource(context, it) }

        if (matchingBinding != null) {
            val matchedEpisode = if (explicitDay != null) {
                matchingBinding.episodes.firstOrNull { it.dayNumber == explicitDay }
            } else {
                matchingBinding.episodes.firstOrNull {
                    it.topicTitle.contains(cleanTopic, ignoreCase = true) ||
                        cleanTopic.contains(it.topicTitle.substringBefore(" ("), ignoreCase = true)
                }
            } ?: matchingBinding.currentEpisode()

            val refinedQuery = "${matchingBinding.campTitle} ${matchingBinding.instructor} ${matchedEpisode.dayNumber}. gün $cleanTopic"
            return matchingBinding.campTitle to matchedEpisode.copy(
                topicTitle = cleanTopic.ifBlank { matchedEpisode.topicTitle },
                searchQuery = refinedQuery
            )
        }

        // 2. Fallback to built-in catalog by subject/topic
        val hay = "$cleanSubj $cleanTopic $taskTitle".lowercase(Locale("tr"))
        val template = BuiltInCampCatalog.firstOrNull { tpl ->
            tpl.matchKeywords.any { kw -> hay.contains(kw.lowercase(Locale("tr"))) } ||
                hay.contains(tpl.subject.lowercase(Locale("tr")))
        }
        val dayNum = explicitDay ?: dayHint.coerceAtLeast(1)
        val campName = template?.let { "${it.title} (${it.instructor})" } ?: "$cleanSubj Video Kampı"
        val query = if (template != null) {
            "${template.title} ${template.instructor} $cleanTopic"
        } else {
            "$cleanSubj $cleanTopic konu anlatımı kamp"
        }

        return campName to CampDayEpisode(
            dayNumber = dayNum,
            topicTitle = cleanTopic,
            videoId = template?.defaultVideoId.orEmpty(),
            playlistId = template?.defaultPlaylistId.orEmpty(),
            searchQuery = query
        )
    }

    /**
     * Saves or updates a custom or catalog camp binding for [resource], optionally using AI
     * to slice the camp into daily episodes.
     */
    fun saveResourceCampBinding(
        context: Context,
        resource: ResourceEntity,
        campTitle: String,
        instructor: String,
        totalDays: Int,
        currentDay: Int,
        youtubeUrl: String,
        customDayTopics: List<String> = emptyList()
    ): ResourceCampBinding {
        ensureLoaded(context)
        val safeDays = totalDays.coerceIn(1, 120)
        val safeCurrent = currentDay.coerceIn(1, safeDays)
        val (videoId, playlistId) = parseYouTubeUrlIds(youtubeUrl)

        val baseTopics = if (customDayTopics.isNotEmpty()) {
            expandTopicsToDays(customDayTopics, safeDays)
        } else {
            val tpl = BuiltInCampCatalog.firstOrNull {
                it.title.equals(campTitle.trim(), ignoreCase = true)
            }
            tpl?.dayTopics?.let { expandTopicsToDays(it, safeDays) }
                ?: defaultTopicsForSubject(resource.subject, safeDays)
        }

        val episodes = (1..safeDays).map { d ->
            val topic = baseTopics.getOrElse(d - 1) { "${resource.subject} • $d. Gün" }
            CampDayEpisode(
                dayNumber = d,
                topicTitle = topic,
                videoId = if (safeDays == 1 || playlistId.isBlank()) videoId else "",
                playlistId = playlistId,
                customUrl = youtubeUrl.trim(),
                searchQuery = "$campTitle $instructor $d. gün $topic",
                isCompleted = d < safeCurrent
            )
        }

        val binding = ResourceCampBinding(
            resourceId = resource.id,
            resourceTitle = resource.title,
            subject = resource.subject,
            campTitle = campTitle.trim().ifBlank { "${resource.title} Kampı" },
            instructor = instructor.trim().ifBlank { resource.subject },
            totalDays = safeDays,
            currentDay = safeCurrent,
            youtubeUrl = youtubeUrl.trim(),
            videoId = videoId,
            playlistId = playlistId,
            episodes = episodes,
            isAutoMatched = false
        )

        val updated = _bindingsByResourceId.value.toMutableMap()
        updated[resource.id] = binding
        _bindingsByResourceId.value = updated
        persistBindingsToPrefs(context.applicationContext, updated)
        return binding
    }

    fun advanceCampDay(context: Context, resource: ResourceEntity): ResourceCampBinding {
        val current = resolveCampForResource(context, resource)
        val nextDay = (current.currentDay + 1).coerceIn(1, current.totalDays.coerceAtLeast(1))
        return saveResourceCampBinding(
            context = context,
            resource = resource,
            campTitle = current.campTitle,
            instructor = current.instructor,
            totalDays = current.totalDays,
            currentDay = nextDay,
            youtubeUrl = current.youtubeUrl,
            customDayTopics = current.episodes.map { it.topicTitle }
        )
    }

    /**
     * Uses Gemini AI (with instant offline curriculum fallback) to slice a book/camp into
     * [totalDays] structured daily topics.
     */
    suspend fun generateDailyCampTopicsWithAi(
        context: Context,
        bookTitle: String,
        subject: String,
        instructor: String,
        totalDays: Int
    ): List<String> = withContext(Dispatchers.IO) {
        val safeDays = totalDays.coerceIn(3, 90)
        val compass = CoachingEngine.buildAiSystemCompassPrompt(context)
        val prompt = """
            Kaynak Kitap / Kamp: "$bookTitle" ($subject - Eğitmen: $instructor)
            Bu kampı tam $safeDays güne böl. Her satırda sadece o günün konu başlığını yaz (1. Gün numaralandırması koymadan, sadece konu adı).
            Toplam $safeDays satır döndür. Açıklama veya emoji yazma.
        """.trimIndent()

        val aiLines = runCatching {
            UserAiPreferences.initialize(context)
            val res = com.example.sharkmentor.core.ai.GeminiAiFactory.generateContent(
                contents = listOf(com.example.sharkmentor.core.ai.GeminiContent.userText(prompt)),
                systemPrompt = compass,
                taskType = com.example.sharkmentor.core.ai.AiTaskType.LIGHT_INTERACTION
            )
            res.text
                .lines()
                .map { line ->
                    line.trim()
                        .replace(Regex("""^\d+[\.\)\-\s]+([Gg]ün[\s:\-]*)?"""), "")
                        .trim()
                }
                .filter { it.length >= 3 }
        }.getOrDefault(emptyList())

        if (aiLines.size >= 3) {
            return@withContext expandTopicsToDays(aiLines, safeDays)
        }

        defaultTopicsForSubject(subject, safeDays)
    }

    /**
     * Distributes 7 consecutive camp days starting from [binding.currentDay] into the student's
     * active weekly plan folder in Room (`planDao()`), so every day of the week has a scheduled
     * camp box with direct video & question targets.
     */
    suspend fun distributeCampToWeeklyPlan(
        context: Context,
        binding: ResourceCampBinding,
        targetQuestionsPerDay: Int = 35
    ): Int = withContext(Dispatchers.IO) {
        val db = SharkMentorDatabase.getDatabase(context.applicationContext)
        val planDao = db.planDao()

        val existingFolders = planDao.getAllFoldersSync()
        val activeFolder = existingFolders.firstOrNull() ?: PlanFolderEntity(
            id = UUID.randomUUID().toString(),
            title = "Bu Haftanın Kamp Planı"
        ).also { planDao.insertFolder(it) }

        val startDay = binding.currentDay.coerceIn(1, binding.totalDays.coerceAtLeast(1))
        val newTasks = mutableListOf<PlanEntity>()

        for (dayOfWeek in 1..7) {
            val campDayNum = startDay + (dayOfWeek - 1)
            if (campDayNum > binding.totalDays) break
            val ep = binding.episodes.firstOrNull { it.dayNumber == campDayNum }
                ?: CampDayEpisode(
                    dayNumber = campDayNum,
                    topicTitle = "${binding.subject} • $campDayNum. Gün"
                )
            val taskTopic = "${binding.campTitle} • ${ep.dayNumber}. Gün: ${ep.topicTitle}"
            val entity = PlanEntity(
                id = UUID.randomUUID().toString(),
                folderId = activeFolder.id,
                title = taskTopic,
                subject = binding.subject,
                topic = taskTopic,
                dayOfWeek = dayOfWeek,
                scheduledTime = "10:00 - 11:30",
                targetQuestions = targetQuestionsPerDay.coerceAtLeast(30),
                isCompleted = false,
                orderIndex = dayOfWeek
            )
            newTasks.add(entity)
        }
        if (newTasks.isNotEmpty()) {
            planDao.insertTasks(newTasks)
        }
        newTasks.size
    }

    private fun defaultTopicsForSubject(subject: String, totalDays: Int): List<String> {
        val lower = subject.lowercase(Locale("tr"))
        val base = when {
            lower.contains("ayt") && lower.contains("mat") -> aytMathTopics
            lower.contains("mat") -> tytMathTopics
            lower.contains("geo") -> geometryTopics
            lower.contains("fiz") -> physicsTopics
            lower.contains("kim") -> chemistryTopics
            lower.contains("biyo") -> biologyTopics
            lower.contains("türk") || lower.contains("turk") || lower.contains("paragraf") -> turkishTopics
            else -> (1..totalDays).map { "$subject • $it. Gün Konu & Soru" }
        }
        return expandTopicsToDays(base, totalDays)
    }

    private fun loadBindingsFromPrefs(context: Context): Map<Long, ResourceCampBinding> {
        val raw = prefs(context).getString(KEY_BINDINGS_JSON, null) ?: return emptyMap()
        return runCatching {
            val arr = JSONArray(raw)
            val map = LinkedHashMap<Long, ResourceCampBinding>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val resId = obj.optLong("resourceId", -1L)
                if (resId <= 0L) continue
                val epsArr = obj.optJSONArray("episodes")
                val episodes = mutableListOf<CampDayEpisode>()
                if (epsArr != null) {
                    for (j in 0 until epsArr.length()) {
                        val eObj = epsArr.optJSONObject(j) ?: continue
                        episodes.add(
                            CampDayEpisode(
                                dayNumber = eObj.optInt("dayNumber", j + 1),
                                topicTitle = eObj.optString("topicTitle", ""),
                                videoId = eObj.optString("videoId", ""),
                                playlistId = eObj.optString("playlistId", ""),
                                customUrl = eObj.optString("customUrl", ""),
                                searchQuery = eObj.optString("searchQuery", ""),
                                isCompleted = eObj.optBoolean("isCompleted", false)
                            )
                        )
                    }
                }
                map[resId] = ResourceCampBinding(
                    resourceId = resId,
                    resourceTitle = obj.optString("resourceTitle", ""),
                    subject = obj.optString("subject", ""),
                    campTitle = obj.optString("campTitle", ""),
                    instructor = obj.optString("instructor", ""),
                    totalDays = obj.optInt("totalDays", 30),
                    currentDay = obj.optInt("currentDay", 1),
                    youtubeUrl = obj.optString("youtubeUrl", ""),
                    videoId = obj.optString("videoId", ""),
                    playlistId = obj.optString("playlistId", ""),
                    episodes = episodes,
                    isAutoMatched = obj.optBoolean("isAutoMatched", false)
                )
            }
            map
        }.getOrDefault(emptyMap())
    }

    private fun persistBindingsToPrefs(context: Context, bindings: Map<Long, ResourceCampBinding>) {
        val arr = JSONArray()
        bindings.values.forEach { b ->
            val obj = JSONObject()
            obj.put("resourceId", b.resourceId)
            obj.put("resourceTitle", b.resourceTitle)
            obj.put("subject", b.subject)
            obj.put("campTitle", b.campTitle)
            obj.put("instructor", b.instructor)
            obj.put("totalDays", b.totalDays)
            obj.put("currentDay", b.currentDay)
            obj.put("youtubeUrl", b.youtubeUrl)
            obj.put("videoId", b.videoId)
            obj.put("playlistId", b.playlistId)
            obj.put("isAutoMatched", b.isAutoMatched)
            val epsArr = JSONArray()
            b.episodes.forEach { ep ->
                val eObj = JSONObject()
                eObj.put("dayNumber", ep.dayNumber)
                eObj.put("topicTitle", ep.topicTitle)
                eObj.put("videoId", ep.videoId)
                eObj.put("playlistId", ep.playlistId)
                eObj.put("customUrl", ep.customUrl)
                eObj.put("searchQuery", ep.searchQuery)
                eObj.put("isCompleted", ep.isCompleted)
                epsArr.put(eObj)
            }
            obj.put("episodes", epsArr)
            arr.put(obj)
        }
        prefs(context).edit().putString(KEY_BINDINGS_JSON, arr.toString()).apply()
    }
}
