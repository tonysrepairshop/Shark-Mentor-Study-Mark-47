package com.example.sharkmentor.core.ai

import java.util.Locale

/**
 * Official MEB / ÖSYM TYT & AYT Subject + Sub-topic Curriculum Catalog.
 * Ensures that when a student or AI requests "TYT tüm konular", "AYT tüm konular",
 * or a specific subject's curriculum, all individual sub-topics are batch-inserted
 * into `TopicGoalEntity` instead of creating a single empty subject folder.
 */
object YksCurriculumCatalog {

    val TYT_SUBJECT_TOPICS: Map<String, List<String>> = linkedMapOf(
        "TYT Türkçe" to listOf(
            "Sözcükte Anlam",
            "Cümlede Anlam",
            "Paragrafta Anlam ve Yapı",
            "Ses Bilgisi",
            "Yazım Kuralları",
            "Noktalama İşaretleri",
            "Sözcükte Yapı ve Ekler",
            "Sözcük Türleri (İsim, Sıfat, Zamir, Zarf)",
            "Fiiller, Ek Fiil ve Fiilimsiler",
            "Cümlenin Ögeleri",
            "Cümle Türleri",
            "Anlatım Bozuklukları"
        ),
        "TYT Matematik" to listOf(
            "Temel Kavramlar ve Sayılar",
            "Sayı Basamakları ve Bölünebilme",
            "EBOB - EKOK",
            "Rasyonel Sayılar ve Ondalık Sayılar",
            "Basit Eşitsizlikler ve Mutlak Değer",
            "Üslü ve Köklü Sayılar",
            "Çarpanlara Ayırma ve Özdeşlikler",
            "Oran - Orantı",
            "Sayı, Kesir ve Yaş Problemleri",
            "Hız, İşçi, Yüzde ve Karışım Problemleri",
            "Kümeler ve Kartezyen Çarpım",
            "Fonksiyonlar",
            "Polinomlar",
            "Permütasyon, Kombinasyon ve Olasılık",
            "İstatistik ve Veri Analizi"
        ),
        "TYT Geometri" to listOf(
            "Doğruda ve Üçgende Açılar",
            "Özel Üçgenler (Dik, İkizkenar, Eşkenar)",
            "Üçgende Açıortay, Kenarortay ve Benzerlik",
            "Üçgende Alan",
            "Çokgenler ve Dörtgenler",
            "Paralelkenar, Eşkenar Dörtgen ve Yamuk",
            "Kare ve Dikdörtgen",
            "Çember ve Daire",
            "Katı Cisimler (Prizma, Piramit, Silindir, Koni, Küre)"
        ),
        "TYT Fizik" to listOf(
            "Fizik Bilimine Giriş",
            "Madde ve Özellikleri",
            "Hareket ve Kuvvet (Newton Yasaları)",
            "İş, Güç ve Enerji",
            "Isı, Sıcaklık ve Genleşme",
            "Basınç ve Kaldırma Kuvveti",
            "Elektrostatik ve Elektrik Devreleri",
            "Mıknatıslar ve Manyetizma",
            "Dalgalar (Yay, Su, Ses, Deprem)",
            "Optik (Aydınlanma, Aynalar, Kırılma, Mercekler)"
        ),
        "TYT Kimya" to listOf(
            "Kimya Bilimi ve Semboller",
            "Atom ve Periyodik Sistem",
            "Kimyasal Türler Arası Etkileşimler",
            "Maddenin Halleri",
            "Doğa ve Kimya",
            "Kimyanın Temel Kanunları ve Mol Kavramı",
            "Kimyasal Hesaplamalar",
            "Karışımlar ve Ayırma Teknikleri",
            "Asitler, Bazlar ve Tuzlar",
            "Kimya Her Yerde"
        ),
        "TYT Biyoloji" to listOf(
            "Yaşam Bilimi Biyoloji (Canlıların Temel Bileşenleri)",
            "Hücre ve Organeller (Hücre Zarından Madde Geçişleri)",
            "Canlıların Sınıflandırılması ve Çeşitliliği",
            "Hücre Bölünmeleri (Mitoz ve Mayoz)",
            "Kalıtımın Genel İlkeleri ve Soy Ağaçları",
            "Ekosistem Ekolojisi ve Güncel Çevre Sorunları"
        ),
        "TYT Tarih" to listOf(
            "Tarih ve Zaman",
            "İlk ve Orta Çağlarda Türk Dünyası",
            "İslam Medeniyetinin Doğuşu ve İlk Türk-İslam Devletleri",
            "Yerleşme ve Devletleşme Sürecinde Selçuklu Türkiyesi",
            "Beylikten Devlete Osmanlı Siyaseti ve Medeniyeti",
            "Dünya Gücü Osmanlı ve Değişim Çağında Avrupa-Osmanlı",
            "XX. Yüzyıl Başlarında Osmanlı Devleti ve I. Dünya Savaşı",
            "Milli Mücadele (Hazırlık ve Cepheler)",
            "Atatürkçülük ve Türk İnkılabı"
        ),
        "TYT Coğrafya" to listOf(
            "Doğa ve İnsan",
            "Dünya'nın Şekli, Hareketleri ve Coğrafi Konum",
            "Harita Bilgisi ve İzohipsler",
            "Atmosfer, Sıcaklık, Basınç, Rüzgarlar ve Nem",
            "İklim Tipleri ve Türkiye'nin İklimi",
            "İç ve Dış Kuvvetler (Yer'in Şekillenmesi)",
            "Su, Toprak ve Bitki Varlığı",
            "Nüfus, Göç ve Yerleşme",
            "Bölgeler, Ulaşım Yolları ve Doğal Afetler"
        ),
        "TYT Felsefe" to listOf(
            "Felsefeyi Tanıma ve Felsefe ile Düşünme",
            "Varlık Felsefesi (Ontoloji)",
            "Bilgi Felsefesi (Epistemoloji)",
            "Bilim Felsefesi",
            "Ahlak Felsefesi (Etik)",
            "Din, Siyaset ve Sanat Felsefesi"
        ),
        "TYT Din Kültürü" to listOf(
            "Bilgi ve İnanç",
            "Din ve İslam",
            "İslam ve İbadet",
            "Ahlak ve Değerler",
            "Allah-İnsan İlişkisi ve Hz. Muhammed",
            "Vahiy, Akıl ve İslam Düşüncesinde Yorumlar"
        )
    )

    val AYT_SUBJECT_TOPICS: Map<String, List<String>> = linkedMapOf(
        "AYT Matematik" to listOf(
            "İkinci Dereceden Denklemler ve Karmaşık Sayılar",
            "Parabol ve İkinci Dereceden Eşitsizlikler",
            "Fonksiyonlarda Uygulamalar ve Dönüşümler",
            "Trigonometri",
            "Logaritma",
            "Diziler",
            "Limit ve Süreklilik",
            "Türev ve Uygulamaları",
            "İntegral ve Alan Hesabı"
        ),
        "AYT Geometri" to listOf(
            "Doğrunun Analitik İncelenmesi",
            "Dönüşüm Geometrisi",
            "Çemberin Analitik İncelenmesi",
            "Uzay Geometrisi ve Katı Cisimler"
        ),
        "AYT Fizik" to listOf(
            "Vektörler ve Bağıl Hareket",
            "Newton'un Hareket Yasaları ve Sabit İvmeli Hareket",
            "Atışlar (Bir ve İki Boyutta Hareket)",
            "İş, Güç, Enerji ve İtme-Momentum",
            "Tork, Denge ve Kütle Merkezi",
            "Basit Makineler",
            "Elektriksel Kuvvet, Alan, Potansiyel ve Sığa",
            "Manyetizma ve Elektromanyetik İndüksiyon",
            "Alternatif Akım ve Transformatörler",
            "Çembersel Hareket ve Kütle Çekim",
            "Basit Harmonik Hareket",
            "Dalga Mekaniği ve Elektromanyetik Dalgalar",
            "Atom Fiziğine Giriş, Radyoaktivite ve Modern Fizik"
        ),
        "AYT Kimya" to listOf(
            "Modern Atom Teorisi",
            "Gazlar",
            "Sıvı Çözeltiler ve Çözünürlük",
            "Kimyasal Tepkimelerde Enerji (Entalpi)",
            "Kimyasal Tepkimelerde Hız",
            "Kimyasal Tepkimelerde Denge",
            "Asit-Baz Dengesi ve Çözünürlük Dengesi (Kçç)",
            "Kimya ve Elektrik (Redoks ve Piller)",
            "Karbon Kimyasına Giriş ve Organik Bileşikler",
            "Enerji Kaynakları ve Bilimsel Gelişmeler"
        ),
        "AYT Biyoloji" to listOf(
            "Sinir Sistemi, Endokrin Sistem ve Duyu Organları",
            "Destek-Hareket ve Sindirim Sistemi",
            "Dolaşım, Bağışıklık ve Solunum Sistemi",
            "Üriner Sistem ve Üreme Sistemi",
            "Komünite ve Popülasyon Ekolojisi",
            "Nükleik Asitler, Genetik Şifre ve Protein Sentezi",
            "Canlılık ve Enerji (Fotosentez, Kemosentez, Hücresel Solunum)",
            "Bitki Biyolojisi",
            "Canlılar ve Çevre"
        ),
        "AYT Edebiyat" to listOf(
            "Anlam Bilgisi ve Şiir Bilgisi",
            "İslamiyet Öncesi ve Geçiş Dönemi Türk Edebiyatı",
            "Halk Edebiyatı",
            "Divan Edebiyatı",
            "Tanzimat, Servetifünun ve Fecriati Edebiyatı",
            "Milli Edebiyat Dönemi",
            "Cumhuriyet Dönemi Türk Edebiyatı (Şiir, Roman, Hikaye, Tiyatro)",
            "Edebi Akımlar ve Dünya Edebiyatı"
        ),
        "AYT Tarih" to listOf(
            "Tarih ve Zaman / İlk ve Orta Çağlarda Türk Dünyası",
            "İslam Medeniyeti ve İlk Türk-İslam Devletleri",
            "Osmanlı Devleti Kuruluş, Yükseliş ve Kültür-Medeniyet",
            "Yeni ve Yakın Çağda Avrupa ve Osmanlı",
            "XX. Yüzyıl Başlarında Osmanlı ve Milli Mücadele",
            "Atatürk İlke ve İnkılapları",
            "XX. Yüzyıl Dünya ve Türkiye Tarihi (Soğuk Savaş ve Küreselleşme)"
        ),
        "AYT Coğrafya" to listOf(
            "Ekosistem, Biyoçeşitlilik ve Madde Döngüleri",
            "Nüfus Politikaları ve Yerleşme Özellikleri",
            "Türkiye'de ve Dünyada Ekonomik Faaliyetler (Tarım, Hayvancılık, Madencilik, Sanayi)",
            "Ulaşım, Ticaret ve Turizm Coğrafyası",
            "Bölgesel Kalkınma Projeleri ve Jeopolitik Konum",
            "Küresel Ortam: Bölgeler, Ülkeler ve Çevre-Toplum"
        )
    )

    private val PLACEHOLDER_TOPIC_NAMES = setOf(
        "",
        "genel",
        "konu",
        "tüm konular",
        "tum konular",
        "müfredat",
        "mufredat",
        "tyt tüm konular",
        "ayt tüm konular",
        "tyt konuları",
        "ayt konuları",
        "klasör",
        "hepsi"
    )

    fun isPlaceholderOrBroadTopic(topic: String): Boolean {
        val norm = topic.lowercase(Locale("tr")).trim()
        return norm in PLACEHOLDER_TOPIC_NAMES ||
            norm.endsWith("tüm konular") ||
            norm.endsWith("tum konular") ||
            norm.endsWith("konuları") ||
            norm.endsWith("konulari") ||
            norm.endsWith("müfredatı")
    }

    /**
     * Expands a preset (`TYT_ALL`, `AYT_ALL`, `AYT_SAY`, `AYT_EA`, `AYT_SOZ`) or user prompt / subject name
     * into `(subject, topic)` pairs.
     */
    fun expandFromPresetOrPrompt(
        preset: String?,
        exam: String?,
        userPrompt: String = ""
    ): List<Pair<String, String>> {
        val combined = "${preset.orEmpty()} ${exam.orEmpty()} $userPrompt".lowercase(Locale("tr"))
        val results = mutableListOf<Pair<String, String>>()

        val wantsAllTyt = combined.contains("tyt_all") ||
            combined.contains("tyt tüm") ||
            combined.contains("tyt tum") ||
            combined.contains("tüm tyt") ||
            combined.contains("tum tyt") ||
            (combined.contains("tyt") && (combined.contains("müfredat") || combined.contains("konuların hepsi") || combined.contains("bütün konular")))

        val wantsAllAyt = combined.contains("ayt_all") ||
            combined.contains("ayt tüm") ||
            combined.contains("ayt tum") ||
            combined.contains("tüm ayt") ||
            combined.contains("tum ayt") ||
            (combined.contains("ayt") && (combined.contains("müfredat") || combined.contains("konuların hepsi") || combined.contains("bütün konular")))

        if (wantsAllTyt) {
            TYT_SUBJECT_TOPICS.forEach { (subj, topics) ->
                topics.forEach { t -> results.add(subj to t) }
            }
        }

        if (wantsAllAyt) {
            val isSayOnly = combined.contains("sayısal") || combined.contains("sayisal") || combined.contains("ayt_say")
            val isEaOnly = combined.contains("eşit ağırlık") || combined.contains("esit agirlik") || combined.contains("ayt_ea")
            val isSozOnly = combined.contains("sözel") || combined.contains("sozel") || combined.contains("ayt_soz")

            val filteredSubjects = when {
                isSayOnly -> AYT_SUBJECT_TOPICS.filterKeys {
                    it.contains("Matematik") || it.contains("Geometri") || it.contains("Fizik") || it.contains("Kimya") || it.contains("Biyoloji")
                }
                isEaOnly -> AYT_SUBJECT_TOPICS.filterKeys {
                    it.contains("Matematik") || it.contains("Geometri") || it.contains("Edebiyat") || it.contains("Tarih") || it.contains("Coğrafya")
                }
                isSozOnly -> AYT_SUBJECT_TOPICS.filterKeys {
                    it.contains("Edebiyat") || it.contains("Tarih") || it.contains("Coğrafya")
                }
                else -> AYT_SUBJECT_TOPICS
            }
            filteredSubjects.forEach { (subj, topics) ->
                topics.forEach { t -> results.add(subj to t) }
            }
        }

        if (results.isNotEmpty()) {
            return results.distinct()
        }

        // Check if a specific subject's full topic list was requested
        for ((subj, topics) in (TYT_SUBJECT_TOPICS + AYT_SUBJECT_TOPICS)) {
            val subjNorm = subj.lowercase(Locale("tr"))
            val shortSubjNorm = subjNorm.removePrefix("tyt ").removePrefix("ayt ").trim()
            if (combined.contains(subjNorm) || (combined.contains(shortSubjNorm) && (combined.contains("tüm") || combined.contains("tum") || combined.contains("konular")))) {
                val isAyt = subjNorm.startsWith("ayt")
                val promptHasAyt = combined.contains("ayt")
                if (isAyt == promptHasAyt || combined.contains(subjNorm)) {
                    topics.forEach { t -> results.add(subj to t) }
                }
            }
        }

        return results.distinct()
    }

    /**
     * Expands a single subject name into all its sub-topics when the AI sends a broad/empty topic for that subject.
     */
    fun expandSubjectTopics(subject: String, examHint: String = "TYT"): List<Pair<String, String>> {
        val norm = subject.lowercase(Locale("tr")).trim()
        val isAyt = norm.contains("ayt") || examHint.equals("AYT", ignoreCase = true)
        val sourceMap = if (isAyt) AYT_SUBJECT_TOPICS else TYT_SUBJECT_TOPICS

        for ((catalogSubj, topics) in sourceMap) {
            val catalogNorm = catalogSubj.lowercase(Locale("tr"))
            val shortCatalog = catalogNorm.removePrefix("tyt ").removePrefix("ayt ").trim()
            if (norm == catalogNorm || norm == shortCatalog || norm.contains(shortCatalog)) {
                return topics.map { catalogSubj to it }
            }
        }

        // Fallback across both maps
        for ((catalogSubj, topics) in (TYT_SUBJECT_TOPICS + AYT_SUBJECT_TOPICS)) {
            val shortCatalog = catalogSubj.lowercase(Locale("tr")).removePrefix("tyt ").removePrefix("ayt ").trim()
            if (norm.contains(shortCatalog)) {
                return topics.map { catalogSubj to it }
            }
        }
        return emptyList()
    }

    private val NON_ALNUM_REGEX = Regex("[^a-z0-9çğıöşü\\s]")
    private val MULTI_SPACE_REGEX = Regex("\\s+")
    private val TOPIC_NOISE_TOKENS = setOf(
        "ve", "ile", "veya", "konusu", "konuları", "konu", "uygulamaları",
        "uygulamalari", "bilgisi", "hesabı", "hesabi", "genel", "temel"
    )

    fun normalizeTokens(input: String): List<String> {
        return input.lowercase(Locale("tr"))
            .replace(NON_ALNUM_REGEX, " ")
            .replace(MULTI_SPACE_REGEX, " ")
            .trim()
            .split(" ")
            .filter { it.isNotBlank() }
    }

    fun coreSubjectName(rawSubject: String): String {
        val norm = rawSubject.lowercase(Locale("tr"))
            .replace("tyt", " ")
            .replace("ayt", " ")
            .replace("yks", " ")
            .replace("ders:", " ")
            .replace(NON_ALNUM_REGEX, " ")
            .replace(MULTI_SPACE_REGEX, " ")
            .trim()
        return when {
            norm.contains("geometri") || norm == "geo" -> "geometri"
            norm.contains("matematik") || norm == "mat" -> "matematik"
            norm.contains("fizik") || norm == "fiz" -> "fizik"
            norm.contains("kimya") || norm == "kim" -> "kimya"
            norm.contains("biyoloji") || norm == "biyo" -> "biyoloji"
            norm.contains("edebiyat") -> "edebiyat"
            norm.contains("türkçe") || norm.contains("turkce") || norm == "tr" -> "türkçe"
            norm.contains("tarih") -> "tarih"
            norm.contains("coğrafya") || norm.contains("cografya") -> "coğrafya"
            norm.contains("felsefe") -> "felsefe"
            norm.contains("din") -> "din kültürü"
            norm.contains("ingilizce") -> "ingilizce"
            else -> norm
        }
    }

    fun areSubjectsCompatible(subjA: String, subjB: String): Boolean {
        val a = subjA.lowercase(Locale("tr")).trim()
        val b = subjB.lowercase(Locale("tr")).trim()
        if (a.isBlank() || b.isBlank() || a == "genel" || b == "genel" || a == "ders" || b == "ders") {
            return true
        }
        val aHasTyt = Regex("""\btyt\b""").containsMatchIn(a)
        val aHasAyt = Regex("""\bayt\b""").containsMatchIn(a)
        val bHasTyt = Regex("""\btyt\b""").containsMatchIn(b)
        val bHasAyt = Regex("""\bayt\b""").containsMatchIn(b)
        if ((aHasTyt && bHasAyt && !bHasTyt) || (aHasAyt && bHasTyt && !bHasAyt)) {
            return false
        }
        val coreA = coreSubjectName(a)
        val coreB = coreSubjectName(b)
        if (coreA == coreB) return true
        // Handle "Türkçe / Edebiyat" combined folder compatibility
        if ((coreA == "türkçe" && b.contains("türkçe")) || (coreB == "türkçe" && a.contains("türkçe"))) return true
        if ((coreA == "edebiyat" && b.contains("edebiyat")) || (coreB == "edebiyat" && a.contains("edebiyat"))) return true
        return false
    }

    /**
     * Finds unambiguous `(canonicalSubject, canonicalTopic)` in the curriculum catalog for a raw `(subject, topic)`.
     * Prevents mis-classifying AYT topics (e.g. Türev/İntegral) under TYT Matematik or across unrelated subjects.
     */
    fun findCurriculumMatch(
        rawSubject: String,
        rawTopic: String,
        promptHint: String = ""
    ): Pair<String, String>? {
        val cleanTopic = rawTopic.trim()
        if (cleanTopic.isBlank() || isPlaceholderOrBroadTopic(cleanTopic)) return null

        val topicTokens = normalizeTokens(cleanTopic).filter { it !in TOPIC_NOISE_TOKENS && it.length >= 3 }
        if (topicTokens.isEmpty()) return null
        val topicNorm = normalizeTokens(cleanTopic).joinToString(" ")

        val allEntries = (TYT_SUBJECT_TOPICS.entries + AYT_SUBJECT_TOPICS.entries).flatMap { (subj, topics) ->
            topics.map { subj to it }
        }

        val scored = allEntries.mapNotNull { (catSubj, catTopic) ->
            val catNorm = normalizeTokens(catTopic).joinToString(" ")
            val catTokens = normalizeTokens(catTopic).filter { it !in TOPIC_NOISE_TOKENS && it.length >= 3 }
            val exact = catNorm == topicNorm
            val containment = catNorm.contains(topicNorm) || topicNorm.contains(catNorm)
            val overlap = topicTokens.count { t -> catTokens.any { ct -> ct == t || ct.startsWith(t) || t.startsWith(ct) } }
            val strongMatch = exact || (containment && topicTokens.isNotEmpty()) || (overlap >= 2) || (topicTokens.size == 1 && overlap == 1 && topicTokens[0].length >= 5)
            if (!strongMatch) return@mapNotNull null

            val coreMatches = rawSubject.isNotBlank() &&
                !rawSubject.equals("Genel", ignoreCase = true) &&
                !rawSubject.equals("Ders", ignoreCase = true) &&
                coreSubjectName(catSubj) == coreSubjectName(rawSubject)

            val rawLower = "$rawSubject $promptHint".lowercase(Locale("tr"))
            val examBonus = when {
                rawLower.contains("ayt") && catSubj.startsWith("AYT") -> 2
                rawLower.contains("tyt") && catSubj.startsWith("TYT") -> 2
                else -> 0
            }
            val score = (if (exact) 100 else 0) +
                (if (containment) 40 else 0) +
                (overlap * 10) +
                (if (coreMatches) 30 else 0) +
                examBonus
            Triple(catSubj, catTopic, score)
        }

        if (scored.isEmpty()) return null

        val rawCore = coreSubjectName(rawSubject)
        val hasExplicitRawCore = rawSubject.isNotBlank() &&
            !rawSubject.equals("Genel", ignoreCase = true) &&
            !rawSubject.equals("Ders", ignoreCase = true)

        // If a unique non-ambiguous catalog entry wins clearly, return it
        val distinctCores = scored.map { coreSubjectName(it.first) }.distinct()
        val best = scored.maxByOrNull { it.third } ?: return null

        // Cross-subject fix only when topic uniquely identifies a different subject OR raw subject is generic
        if (!hasExplicitRawCore || distinctCores.size == 1 || coreSubjectName(best.first) == rawCore) {
            return best.first to best.second
        }
        return null
    }

    /**
     * Resolves canonical subject display name when `rawSubject` is generic or abbreviated.
     */
    fun canonicalizeSubject(
        rawSubject: String,
        rawTopic: String = "",
        promptHint: String = ""
    ): String {
        val trimmed = rawSubject.trim()
        val match = findCurriculumMatch(trimmed, rawTopic, promptHint)
        if (match != null) {
            return match.first
        }
        if (trimmed.isBlank() || trimmed.equals("Genel", ignoreCase = true) || trimmed.equals("Ders", ignoreCase = true)) {
            return trimmed.ifBlank { "Genel" }
        }
        val lower = trimmed.lowercase(Locale("tr"))
        val combinedHint = "$lower ${promptHint.lowercase(Locale("tr"))}"
        val hasTyt = Regex("""\btyt\b""").containsMatchIn(lower)
        val hasAyt = Regex("""\bayt\b""").containsMatchIn(lower)
        val prefix = when {
            hasAyt -> "AYT "
            hasTyt -> "TYT "
            combinedHint.contains("ayt") -> "AYT "
            combinedHint.contains("tyt") -> "TYT "
            else -> ""
        }
        val coreTitle = when (coreSubjectName(trimmed)) {
            "matematik" -> "Matematik"
            "geometri" -> "Geometri"
            "fizik" -> "Fizik"
            "kimya" -> "Kimya"
            "biyoloji" -> "Biyoloji"
            "türkçe" -> "Türkçe"
            "edebiyat" -> "Edebiyat"
            "tarih" -> "Tarih"
            "coğrafya" -> "Coğrafya"
            "felsefe" -> "Felsefe"
            "din kültürü" -> "Din Kültürü"
            "ingilizce" -> "İngilizce"
            else -> return trimmed
        }
        return if (prefix.isNotEmpty() && !coreTitle.equals("İngilizce", ignoreCase = true)) {
            if (prefix == "AYT " && coreTitle == "Türkçe") "AYT Edebiyat" else "$prefix$coreTitle"
        } else {
            trimmed
        }
    }
}
