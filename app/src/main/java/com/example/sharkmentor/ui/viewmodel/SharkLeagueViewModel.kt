package com.example.sharkmentor.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import androidx.compose.runtime.Immutable
import com.example.sharkmentor.core.util.GoogleServicesHelper
import kotlinx.coroutines.tasks.await

// ─── Tier thresholds (seconds/week) ───────────────────────────────────

private const val MAKO_THRESHOLD        = 10 * 3600L  // 10 h
private const val CEKICBAS_THRESHOLD    = 20 * 3600L  // 20 h
private const val KAPLAN_THRESHOLD      = 30 * 3600L  // 30 h
private const val BUYUK_BEYAZ_THRESHOLD = 45 * 3600L  // 45 h

// ─── Data models ────────────────────────────────────────────────────────

enum class SharkTier(val label: String, val emoji: String, val color: Long) {
    CAMGOZ     ("Camgöz",       "", 0xFF94A3B8), // Slate (Cyan tint possible in UI)
    MAKO       ("Mako",         "", 0xFF38BDF8), // Sky 400
    CEKICBAS   ("Çekiçbaş",     "", 0xFF6366F1), // Indigo/Blue
    KAPLAN     ("Kaplan",       "", 0xFFF59E0B), // Amber/Gold
    BUYUK_BEYAZ("Büyük Beyaz",  "", 0xFFE2E8F0)  // Platinum/Pure Sky Glow
}

fun Long.toTier(): SharkTier = when {
    this >= BUYUK_BEYAZ_THRESHOLD -> SharkTier.BUYUK_BEYAZ
    this >= KAPLAN_THRESHOLD      -> SharkTier.KAPLAN
    this >= CEKICBAS_THRESHOLD    -> SharkTier.CEKICBAS
    this >= MAKO_THRESHOLD        -> SharkTier.MAKO
    else                          -> SharkTier.CAMGOZ
}

/** Next tier threshold in seconds; null if already Büyük Beyaz */
fun SharkTier.nextThreshold(): Long? = when (this) {
    SharkTier.CAMGOZ      -> MAKO_THRESHOLD
    SharkTier.MAKO        -> CEKICBAS_THRESHOLD
    SharkTier.CEKICBAS    -> KAPLAN_THRESHOLD
    SharkTier.KAPLAN      -> BUYUK_BEYAZ_THRESHOLD
    SharkTier.BUYUK_BEYAZ -> null
}

@Immutable
data class LeagueEntry(
    val studentId: String    = "",
    val displayName: String  = "",  // anonymized call-sign or initials
    val weeklyFocusSeconds: Long = 0L,
    val streakDays: Int      = 0,
    val tier: SharkTier      = SharkTier.CAMGOZ,
    val isCurrentUser: Boolean = false
)

@Immutable
data class SharkLeagueUiState(
    val isLoading: Boolean         = true,
    val myEntry: LeagueEntry?      = null,
    val leaderboard: List<LeagueEntry> = emptyList(),
    val institutionCode: String    = "",
    val groupName: String?         = null,
    val error: String?             = null
)

// â”€â”€â”€ ViewModel â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

class SharkLeagueViewModel : ViewModel() {

    private val db   get() = GoogleServicesHelper.getFirebaseFirestore()
    private val auth get() = GoogleServicesHelper.getFirebaseAuth()

    private val _uiState = MutableStateFlow(SharkLeagueUiState())
    val uiState: StateFlow<SharkLeagueUiState> = _uiState.asStateFlow()

    // â”€â”€â”€ Load â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    fun loadLeague() {
        val uid = GoogleServicesHelper.getCurrentUser(auth)?.uid ?: return
        val database = db ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                // 1. Fetch student doc to get branchCode & displayName
                val studentDoc = database.collection("students").document(uid).get().await()
                val branchCode = studentDoc.getString("branchCode")
                    ?: studentDoc.getString("institutionCode")
                    ?: return@launch run {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            error = "Kuruma baÄŸlÄ± deÄŸilsiniz. Kurum bÃ¼lteni iÃ§in koÃ§unuzdan branchCode alÄ±n."
                        )
                    }
                val myName = studentDoc.getString("name") ?: "Ã–ÄŸrenci"
                val groupId = studentDoc.getString("groupId")
                val groupName = studentDoc.getString("groupName")

                // 2. Compute current student's weekly focus seconds from sessions subcollection
                val weeklySeconds = computeWeeklyFocusSeconds(uid)

                // 3. Upsert own entry in the league collection
                upsertMyEntry(branchCode, uid, myName, weeklySeconds, studentDoc.getLong("streakDays")?.toInt() ?: 0, groupId)

                // 4. Fetch leaderboard (top 50, sorted by weeklyFocusSeconds desc)
                var query = database.collection("institutions")
                    .document(branchCode)
                    .collection("weeklyLeague")
                    .orderBy("weeklyFocusSeconds", com.google.firebase.firestore.Query.Direction.DESCENDING)
                    .limit(50)

                if (!groupId.isNullOrEmpty()) {
                    query = query.whereEqualTo("groupId", groupId)
                }

                val leagueSnap = query.get().await()

                val entries = leagueSnap.documents.mapIndexed { rank, doc ->
                    val id    = doc.id
                    val secs  = doc.getLong("weeklyFocusSeconds") ?: 0L
                    val name  = doc.getString("displayName") ?: "Anonim"
                    val streak = doc.getLong("streakDays")?.toInt() ?: 0
                    LeagueEntry(
                        studentId          = id,
                        displayName        = anonymizeCallSign(name, rank + 1, id == uid),
                        weeklyFocusSeconds = secs,
                        streakDays         = streak,
                        tier               = secs.toTier(),
                        isCurrentUser      = id == uid
                    )
                }

                val myEntry = entries.find { it.isCurrentUser } ?: LeagueEntry(
                    studentId          = uid,
                    displayName        = "Sen",
                    weeklyFocusSeconds = weeklySeconds,
                    tier               = weeklySeconds.toTier(),
                    isCurrentUser      = true
                )

                _uiState.value = _uiState.value.copy(
                    isLoading       = false,
                    myEntry         = myEntry,
                    leaderboard     = entries,
                    institutionCode = branchCode,
                    groupName       = groupName
                )
            } catch (e: Exception) {
                Log.e("SharkLeagueVM", "League load error", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error     = "Lig yÃ¼klenemedi: ${e.message}"
                )
            }
        }
    }

    // â”€â”€â”€ Helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private suspend fun computeWeeklyFocusSeconds(uid: String): Long {
        return try {
            val database = db ?: return 0L
            val now = System.currentTimeMillis()
            val weekStart = now - 7L * 24 * 3600 * 1000
            val snap = database.collection("students").document(uid)
                .collection("sessions")
                .whereGreaterThanOrEqualTo("createdAt", weekStart)
                .get()
                .await()
            snap.documents.sumOf { doc ->
                doc.getLong("durationSeconds") ?: 0L
            }
        } catch (e: Exception) {
            0L
        }
    }

    private suspend fun upsertMyEntry(
        branchCode: String,
        uid: String,
        name: String,
        weeklyFocusSeconds: Long,
        streakDays: Int,
        groupId: String?
    ) {
        try {
            val database = db ?: return
            val payload = hashMapOf<String, Any>(
                "studentId"          to uid,
                "displayName"        to name,
                "weeklyFocusSeconds" to weeklyFocusSeconds,
                "streakDays"         to streakDays,
                "tier"               to weeklyFocusSeconds.toTier().name,
                "lastSessionAt"      to FieldValue.serverTimestamp()
            )
            if (!groupId.isNullOrEmpty()) {
                payload["groupId"] = groupId
            }
            database.collection("institutions").document(branchCode)
                .collection("weeklyLeague").document(uid)
                .set(payload, SetOptions.merge())
                .await()
        } catch (e: Exception) {
            Log.w("SharkLeagueVM", "upsert failed", e)
        }
    }

    /**
     * Show current user's real name, others get anonymized call-signs
     * e.g. "Megalodon #3", "Mako #7", initials only
     */
    private fun anonymizeCallSign(name: String, rank: Int, isCurrentUser: Boolean): String {
        if (isCurrentUser) return "Sen ($name)"
        val initials = name.split(" ")
            .take(2)
            .mapNotNull { it.firstOrNull()?.uppercase() }
            .joinToString(".")
        return "$initials. #$rank"
    }
}

