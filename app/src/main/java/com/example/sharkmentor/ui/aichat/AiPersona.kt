package com.example.sharkmentor.ui.aichat

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.ui.graphics.vector.ImageVector

enum class AiPersona(
    val id: String,
    val title: String,
    val subtitle: String,
    val iconRes: ImageVector,
    val isTemporaryMemory: Boolean = false, // 10-Day TTL flag
    val isRestricted: Boolean = false
) {
    SHARK_COACH(
        id = "shark_coach",
        title = "Sınav Koçu",
        subtitle = "Taktiksel yönlendirme ve sınav stratejisi",
        iconRes = Icons.Default.Psychology,
        isTemporaryMemory = false
    ),
    GOAL_SETTER(
        id = "goal_setter",
        title = "Hedef Analizi",
        subtitle = "Stratejik net analizi ve sıralama projeksiyonu",
        iconRes = Icons.AutoMirrored.Filled.TrendingUp,
        isTemporaryMemory = false
    ),
    STUDY_PLANNER(
        id = "study_planner",
        title = "Planlayıcı",
        subtitle = "Günlük ve haftalık ders programı mimarı",
        iconRes = Icons.Default.DateRange,
        isTemporaryMemory = false,
        isRestricted = true
    ),
    ZEN_CALM(
        id = "zen_calm",
        title = "Odak & Stres",
        subtitle = "Sınav kaygısı, nefes ve zihinsel rahatlama",
        iconRes = Icons.Default.SelfImprovement,
        isTemporaryMemory = true // 10-Day TTL
    ),
    DOPAMINE_COACH(
        id = "dopamine_coach",
        title = "Alışkanlık Yönetimi",
        subtitle = "Odaklanma, erteleme ve alışkanlık yönetimi",
        iconRes = Icons.Default.Bolt,
        isTemporaryMemory = true, // 10-Day TTL
        isRestricted = true
    )
}

fun isPersonaAvailable(persona: AiPersona, isDopamineActive: Boolean, isInstitutional: Boolean): Pair<Boolean, String?> {
    return when (persona) {
        AiPersona.DOPAMINE_COACH -> {
            if (!isDopamineActive) false to "Dopamin Orucu aktif edildiğinde kullanılabilir."
            else true to null
        }
        AiPersona.STUDY_PLANNER -> {
            if (isInstitutional) false to "Planlarınız kurum koçunuz tarafından hazırlanmaktadır."
            else true to null
        }
        else -> true to null
    }
}
