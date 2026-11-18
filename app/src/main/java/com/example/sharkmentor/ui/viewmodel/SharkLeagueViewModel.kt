package com.example.sharkmentor.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.sharkmentor.core.badges.SharkBadgeCatalog
import com.example.sharkmentor.core.service.FocusTimerService
import com.example.sharkmentor.core.timer.TimerMode
import com.example.sharkmentor.core.timer.TimerPhase
import com.example.sharkmentor.core.util.AvatarHelper
import com.example.sharkmentor.core.util.GoogleServicesHelper
import com.example.sharkmentor.core.util.SessionManager
import com.example.sharkmentor.data.focus.FocusSessionManager
import com.example.sharkmentor.data.local.SharkMentorDatabase
import com.example.sharkmentor.data.local.preferences.UserPreferences
import com.example.sharkmentor.service.sync.SyncWorker
import com.example.sharkmentor.services.SharkMessagingService
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

// ─── Tier thresholds (seconds/week) ──────────────────────────────────────────

private const val MAKO_THRESHOLD        = 10 * 3600L  // 10 saat
private const val CEKICBAS_THRESHOLD    = 20 * 3600L  // 20 saat
private const val KAPLAN_THRESHOLD      = 30 * 3600L  // 30 saat
private const val BUYUK_BEYAZ_THRESHOLD = 45 * 3600L  // 45 saat

/** 15-minute anti-spam cooldown for sending a study invite to the same friend/group member. */
const val NUDGE_COOLDOWN_MS: Long = 15L * 60L * 1_000L

const val GLOBAL_LEAGUE_BUCKET = "GLOBAL_INDIVIDUAL"

private const val INVITE_STALENESS_MS = 24L * 60L * 60L * 1_000L

// ─── Data models ─────────────────────────────────────────────────────────────

enum class SharkTier(val label: String, val emoji: String, val color: Long) {
    CAMGOZ     ("Camgöz",      "🦈", 0xFF94A3B8),
    MAKO       ("Mako",        "⚡", 0xFF38BDF8),
    CEKICBAS   ("Çekiçbaş",    "🌊", 0xFF6366F1),
    KAPLAN     ("Kaplan",      "🔥", 0xFFF59E0B),
    BUYUK_BEYAZ("Büyük Beyaz", "👑", 0xFFE2E8F0)
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

fun SharkTier.previousThreshold(): Long = when (this) {
    SharkTier.CAMGOZ      -> 0L
    SharkTier.MAKO        -> MAKO_THRESHOLD
    SharkTier.CEKICBAS    -> CEKICBAS_THRESHOLD
    SharkTier.KAPLAN      -> KAPLAN_THRESHOLD
    SharkTier.BUYUK_BEYAZ -> BUYUK_BEYAZ_THRESHOLD
}

enum class LeagueTab(val title: String) {
    GLOBAL_INDIVIDUAL("Bireysel Genel Lig"),
    GROUPS_AND_FRIENDS("Gruplarım & Arkadaşlarım")
}

@Immutable
data class StudyGroupSummary(
    val inviteCode: String,
    val name: String,
    val memberCount: Int,
    val creatorId: String = ""
)

@Immutable
data class IncomingStudyInvite(
    val id: String,
    val fromUid: String,
    val fromName: String,
    val message: String,
    val groupId: String?,
    val createdAtMillis: Long
)

@Immutable
data class LeagueEntry(
    val studentId: String = "",
    val displayName: String = "",
    val rawName: String = "",
    val friendCode: String = "",
    val weeklyFocusSeconds: Long = 0L,
    val dailyAverageMinutes: Int = 0,
    val streakDays: Int = 0,
    val tier: SharkTier = SharkTier.CAMGOZ,
    val isCurrentUser: Boolean = false,
    val isStudyingNow: Boolean = false,
    val activeSubject: String? = null,
    val lastNudgeSentMillis: Long = 0L,
    val avatarBase64: String = "",
    val avatarPresetId: String = "",
    val mentorPoints: Int = 0,
    val showcaseBadges: List<String> = emptyList()
) {
    fun canBeNudged(nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (isCurrentUser || studentId.isBlank()) return false
        return (nowMillis - lastNudgeSentMillis) >= NUDGE_COOLDOWN_MS
    }

    fun remainingNudgeCooldownLabel(nowMillis: Long = System.currentTimeMillis()): String {
        val remMs = (NUDGE_COOLDOWN_MS - (nowMillis - lastNudgeSentMillis)).coerceAtLeast(0L)
        if (remMs <= 0L) return "Davet Et"
        val remHours = remMs / (3600L * 1000L)
        val remMins = (remMs % (3600L * 1000L)) / (60L * 1000L)
        return if (remHours > 0L) "${remHours}s ${remMins}dk" else "${remMins.coerceAtLeast(1L)}dk"
    }
}

@Immutable
data class SharkLeagueUiState(
    val isLoading: Boolean = true,
    val activeTab: LeagueTab = LeagueTab.GLOBAL_INDIVIDUAL,
    val myEntry: LeagueEntry? = null,
    val myFriendCode: String = "",
    val leaderboard: List<LeagueEntry> = emptyList(),
    val friendsAndGroupMembers: List<LeagueEntry> = emptyList(),
    val joinedGroups: List<StudyGroupSummary> = emptyList(),
    val selectedGroupCode: String? = null, // null = "Tüm Arkadaşlarım & Gruplarım"
    val institutionCode: String = "",
    val useInstitutionFilter: Boolean = false,
    val groupName: String? = null,
    val isActionInProgress: Boolean = false,
    val statusMessage: String? = null,
    val error: String? = null,
    val needsNameSetup: Boolean = false,
    val incomingStudyInvite: IncomingStudyInvite? = null,
    val pendingDmRequestCount: Int = 0,
    val dmStatusByPeerId: Map<String, String> = emptyMap(),
    val myShowcaseBadges: List<String> = emptyList(),
    val myUnlockedBadges: Set<String> = emptySet(),
    val myMentorPoints: Int = 0
)

// ─── ViewModel ───────────────────────────────────────────────────────────────

class SharkLeagueViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val userPrefs = UserPreferences.getInstance(appContext)
    private val roomDb = SharkMentorDatabase.getDatabase(appContext)

    private val db   get() = GoogleServicesHelper.getFirebaseFirestore(appContext)
    private val auth get() = GoogleServicesHelper.getFirebaseAuth(appContext)

    private val _uiState = MutableStateFlow(
        SharkLeagueUiState(
            myFriendCode = userPrefs.getOrCreateFriendCode(
                GoogleServicesHelper.getCurrentUser(auth, appContext)?.uid
            ),
            myShowcaseBadges = SharkBadgeCatalog.getShowcaseBadgeIds(appContext),
            myUnlockedBadges = SharkBadgeCatalog.getUnlockedBadgeIds(appContext),
            myMentorPoints = SharkBadgeCatalog.getMentorPoints(appContext)
        )
    )
    val uiState: StateFlow<SharkLeagueUiState> = _uiState.asStateFlow()

    private var incomingInvitesListener: ListenerRegistration? = null
    private var incomingDmListener: ListenerRegistration? = null
    private var observedUid: String? = null
    private val notifiedInviteIds = mutableSetOf<String>()

    /** Union of all friend IDs + joined study group member IDs eligible for Study Invites. */
    private var eligibleSocialMemberIds: Set<String> = emptySet()

    fun selectTab(tab: LeagueTab) {
        _uiState.value = _uiState.value.copy(activeTab = tab, statusMessage = null, error = null)
    }

    fun selectGroupFilter(groupCode: String?) {
        _uiState.value = _uiState.value.copy(selectedGroupCode = groupCode)
        loadLeague(preserveTab = true)
    }

    fun toggleInstitutionLeague(useInstitution: Boolean) {
        _uiState.value = _uiState.value.copy(useInstitutionFilter = useInstitution)
        loadLeague(preserveTab = true)
    }

    fun clearStatusMessage() {
        _uiState.value = _uiState.value.copy(statusMessage = null)
    }

    // ─── Name Resolution & Quick Name Update (Task 1) ────────────────────────

    private fun isMeaningfulName(candidate: String?): Boolean {
        val clean = SyncWorker.sanitizeTurkishText(candidate).trim()
        if (clean.length < 2) return false
        val lower = clean.lowercase(Locale("tr"))
        return lower != "öğrenci" &&
            lower != "ogrenci" &&
            lower != "kayıtlı öğrenci" &&
            lower != "kayitli ogrenci" &&
            lower != "arkadaş" &&
            lower != "arkadas" &&
            lower != "student" &&
            lower != "null"
    }

    private fun persistStudentNameLocally(cleanName: String) {
        appContext.getSharedPreferences("user_preferences", Context.MODE_PRIVATE)
            .edit()
            .putString("student_name", cleanName)
            .apply()
    }

    private suspend fun resolveStudentDisplayName(
        uid: String,
        database: FirebaseFirestore?,
        studentDoc: DocumentSnapshot?
    ): String {
        // 1. UserPreferences studentName
        val prefName = SyncWorker.sanitizeTurkishText(userPrefs.studentName).trim()
        if (isMeaningfulName(prefName)) return prefName

        // 2. FirebaseAuth currentUser.displayName
        val firebaseUser = GoogleServicesHelper.getCurrentUser(auth, appContext)
        val authDisplayName = SyncWorker.sanitizeTurkishText(firebaseUser?.displayName).trim()
        if (isMeaningfulName(authDisplayName)) {
            persistStudentNameLocally(authDisplayName)
            return authDisplayName
        }

        // 3. Firestore students/{uid} -> users/{uid} (name, displayName, fullName, username)
        val studentDocName = extractPreferredName(studentDoc, fallback = "")
        if (isMeaningfulName(studentDocName)) {
            persistStudentNameLocally(studentDocName)
            return studentDocName
        }

        if (database != null && uid.isNotBlank() && uid != "local_student" && uid != "student_default") {
            val usersDoc = runCatching { database.collection("users").document(uid).get().await() }.getOrNull()
            val usersDocName = extractPreferredName(usersDoc, fallback = "")
            if (isMeaningfulName(usersDocName)) {
                persistStudentNameLocally(usersDocName)
                return usersDocName
            }
        }

        // 4. Fallback: email prefix with capitalized initial
        val emailPrefix = firebaseUser?.email?.substringBefore("@")?.trim().orEmpty()
        if (emailPrefix.isNotBlank()) {
            val formattedEmailName = emailPrefix
                .replace(".", " ")
                .replace("_", " ")
                .replace("-", " ")
                .split(" ")
                .filter { it.isNotBlank() }
                .joinToString(" ") { part ->
                    part.replaceFirstChar { ch -> ch.titlecase(Locale("tr")) }
                }
                .trim()
            if (isMeaningfulName(formattedEmailName)) {
                persistStudentNameLocally(formattedEmailName)
                return formattedEmailName
            }
        }

        return "Öğrenci"
    }

    private fun extractPreferredName(
        vararg snapshots: DocumentSnapshot?,
        fallback: String
    ): String {
        val fields = listOf("name", "displayName", "fullName", "username")
        for (snap in snapshots) {
            if (snap == null || !snap.exists()) continue
            for (field in fields) {
                val candidate = SyncWorker.sanitizeTurkishText(snap.getString(field)).trim()
                if (isMeaningfulName(candidate)) {
                    return candidate
                }
            }
        }
        return fallback
    }

    fun updateStudentDisplayName(rawName: String) {
        val cleanName = SyncWorker.sanitizeTurkishText(rawName.trim())
        if (!isMeaningfulName(cleanName)) {
            _uiState.value = _uiState.value.copy(
                statusMessage = "Lütfen en az 2 karakterli gerçek bir ad girin."
            )
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isActionInProgress = true)
            persistStudentNameLocally(cleanName)

            val uid = GoogleServicesHelper.getCurrentUser(auth, appContext)?.uid ?: "local_student"
            val database = db
            val myFriendCode = _uiState.value.myFriendCode.ifBlank { userPrefs.getOrCreateFriendCode(uid) }

            if (database != null && uid.isNotBlank() && uid != "local_student" && uid != "student_default") {
                runCatching {
                    database.collection("students").document(uid)
                        .set(
                            mapOf("name" to cleanName, "displayName" to cleanName),
                            SetOptions.merge()
                        )
                        .await()
                }
                runCatching {
                    database.collection("institutions").document(GLOBAL_LEAGUE_BUCKET)
                        .collection("weeklyLeague").document(uid)
                        .set(mapOf("displayName" to cleanName), SetOptions.merge())
                        .await()
                }
                val instCode = _uiState.value.institutionCode
                if (instCode.isNotBlank() && instCode != GLOBAL_LEAGUE_BUCKET) {
                    runCatching {
                        database.collection("institutions").document(instCode)
                            .collection("weeklyLeague").document(uid)
                            .set(mapOf("displayName" to cleanName), SetOptions.merge())
                            .await()
                    }
                }
                runCatching {
                    database.collection("friend_codes").document(myFriendCode)
                        .set(mapOf("displayName" to cleanName), SetOptions.merge())
                        .await()
                }
                for (groupCode in userPrefs.joinedGroupCodes) {
                    runCatching {
                        database.collection("study_groups").document(groupCode)
                            .set(
                                mapOf("memberNames" to mapOf(uid to cleanName)),
                                SetOptions.merge()
                            )
                            .await()
                    }
                }
            }

            _uiState.value = _uiState.value.copy(
                isActionInProgress = false,
                needsNameSetup = false,
                statusMessage = "Adın \"$cleanName\" olarak güncellendi!"
            )
            loadLeague(preserveTab = true)
        }
    }

    // ─── Live Incoming Study Invites & DM Count Listeners ────────────────────

    private fun ensureSocialListeners(uid: String) {
        if (uid.isBlank() || uid == "local_student" || uid == "student_default") return
        val database = db ?: return
        if (observedUid == uid && incomingInvitesListener != null && incomingDmListener != null) return

        incomingInvitesListener?.remove()
        incomingDmListener?.remove()
        observedUid = uid

        incomingInvitesListener = database.collection("nudges")
            .whereEqualTo("toUid", uid)
            .whereEqualTo("seen", false)
            .addSnapshotListener { snap, error ->
                if (error != null) {
                    Log.w("SharkLeagueVM", "Incoming invites listener warning: ${error.message}")
                    return@addSnapshotListener
                }
                val now = System.currentTimeMillis()
                val latest = snap?.documents
                    ?.mapNotNull { doc ->
                        val createdMs = doc.getLong("createdAtMillis")
                            ?: doc.getTimestamp("createdAt")?.toDate()?.time
                            ?: 0L
                        if (createdMs > 0L && now - createdMs > INVITE_STALENESS_MS) {
                            return@mapNotNull null
                        }
                        val fromUid = doc.getString("fromUid") ?: doc.getString("senderId").orEmpty()
                        if (fromUid == uid) return@mapNotNull null
                        val fromName = SyncWorker.sanitizeTurkishText(
                            doc.getString("fromName") ?: doc.getString("senderName") ?: "Arkadaşın"
                        ).ifBlank { "Arkadaşın" }
                        val msg = SyncWorker.sanitizeTurkishText(
                            doc.getString("message").orEmpty()
                        ).ifBlank {
                            "$fromName seni birlikte çalışmaya davet ediyor! Hadi masaya geç ve odak seansını başlat 🦈"
                        }
                        IncomingStudyInvite(
                            id = doc.id,
                            fromUid = fromUid,
                            fromName = fromName,
                            message = msg,
                            groupId = doc.getString("groupId")?.takeIf { it.isNotBlank() },
                            createdAtMillis = createdMs
                        )
                    }
                    ?.maxByOrNull { it.createdAtMillis }

                _uiState.value = _uiState.value.copy(incomingStudyInvite = latest)

                if (latest != null && notifiedInviteIds.add(latest.id)) {
                    SharkMessagingService.showStudyInviteNotification(
                        context = appContext,
                        fromName = latest.fromName,
                        message = latest.message,
                        groupId = latest.groupId,
                        nudgeId = latest.id
                    )
                }
            }

        incomingDmListener = database.collection("dm_threads")
            .whereArrayContains("participantIds", uid)
            .addSnapshotListener { snap, error ->
                if (error != null) return@addSnapshotListener
                var pendingCount = 0
                val statusMap = mutableMapOf<String, String>()
                snap?.documents?.forEach { doc ->
                    val participants = (doc.get("participantIds") as? List<*>)
                        ?.mapNotNull { it?.toString() }
                        .orEmpty()
                    val peerUid = participants.firstOrNull { it != uid }
                    val status = doc.getString("status").orEmpty().ifBlank { "PENDING" }
                    val requesterId = doc.getString("requesterId").orEmpty()
                    if (!peerUid.isNullOrBlank()) {
                        statusMap[peerUid] = status
                    }
                    if (status == "PENDING" && requesterId.isNotBlank() && requesterId != uid) {
                        pendingCount++
                    }
                }
                _uiState.value = _uiState.value.copy(
                    pendingDmRequestCount = pendingCount,
                    dmStatusByPeerId = statusMap
                )
            }
    }

    fun dismissIncomingInvite(inviteId: String) {
        if (_uiState.value.incomingStudyInvite?.id == inviteId) {
            _uiState.value = _uiState.value.copy(incomingStudyInvite = null)
        }
        val database = db ?: return
        if (inviteId.isBlank()) return
        runCatching {
            database.collection("nudges").document(inviteId)
                .set(mapOf("seen" to true), SetOptions.merge())
        }
    }

    fun acceptIncomingInviteAndStartFocus(inviteId: String) {
        dismissIncomingInvite(inviteId)
        val timerState = FocusTimerService.timerStateFlow.value
        if (timerState.timerPhase == TimerPhase.IDLE) {
            val modeToStart = timerState.currentMode.takeIf { it != TimerMode.BREAK } ?: TimerMode.POMODORO
            val startIntent = Intent(appContext, FocusTimerService::class.java).apply {
                action = FocusTimerService.ACTION_START
                putExtra(FocusTimerService.EXTRA_MODE, modeToStart.name)
                putExtra(FocusTimerService.EXTRA_POMODORO_MINUTES, timerState.customPomodoroDurationMinutes)
                putExtra(FocusTimerService.EXTRA_BREAK_MINUTES, timerState.breakDurationMinutes)
                putExtra(FocusTimerService.EXTRA_SUBJECT, "Birlikte Odak")
            }
            val started = runCatching { appContext.startForegroundService(startIntent) }.isSuccess
            if (started) {
                val durationMin = if (modeToStart == TimerMode.BLOCK_90) 90 else timerState.customPomodoroDurationMinutes
                FocusSessionManager(appContext).startSession(durationMinutes = durationMin, subject = "Birlikte Odak")
                SessionManager.setTimerRunning(true)
            }
            _uiState.value = _uiState.value.copy(
                statusMessage = if (started) "🦈 Odak seansın başlatıldı! İyi çalışmalar!" else "Odak sayacı ana ekranda başlatılabilir."
            )
        } else {
            _uiState.value = _uiState.value.copy(
                statusMessage = "🦈 Davet onaylandı — odak sayacın zaten aktif!"
            )
        }
    }

    // ─── Load League (Individual + Optional Institution + Groups & Friends) ──

    fun loadLeague(preserveTab: Boolean = true) {
        viewModelScope.launch {
            val currentTab = _uiState.value.activeTab
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                activeTab = if (preserveTab) currentTab else LeagueTab.GLOBAL_INDIVIDUAL,
                error = null
            )

            try {
                val uid = GoogleServicesHelper.getCurrentUser(auth, appContext)?.uid ?: "local_student"
                val myFriendCode = userPrefs.getOrCreateFriendCode(uid)

                // 1. Always compute local Room weekly focus & streak first (offline-first guarantee)
                val (localWeeklySeconds, localStreakDays) = withContext(Dispatchers.IO) {
                    computeLocalWeeklyStats()
                }

                val database = db
                if (database == null || uid == "local_student" || uid == "student_default") {
                    val offlineName = resolveStudentDisplayName(uid, null, null)
                    val localBadges = SharkBadgeCatalog.getShowcaseBadgeIds(appContext)
                    val localUnlocked = SharkBadgeCatalog.getUnlockedBadgeIds(appContext)
                    val localPoints = SharkBadgeCatalog.getMentorPoints(appContext)
                    val fallbackMe = buildMyLeagueEntry(
                        uid = uid,
                        name = offlineName,
                        friendCode = myFriendCode,
                        weeklySeconds = localWeeklySeconds,
                        streakDays = localStreakDays,
                        mentorPoints = localPoints,
                        showcaseBadges = localBadges
                    )
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        myEntry = fallbackMe,
                        myFriendCode = myFriendCode,
                        leaderboard = listOf(fallbackMe),
                        friendsAndGroupMembers = listOf(fallbackMe),
                        needsNameSetup = !isMeaningfulName(offlineName),
                        myShowcaseBadges = localBadges,
                        myUnlockedBadges = localUnlocked,
                        myMentorPoints = localPoints
                    )
                    return@launch
                }

                ensureSocialListeners(uid)

                // 2. Read student doc from Firestore (without requiring branchCode!)
                val studentDoc = try {
                    database.collection("students").document(uid).get().await()
                } catch (_: Exception) {
                    null
                }

                val branchCode = (studentDoc?.getString("branchCode")
                    ?: studentDoc?.getString("institutionCode")
                    ?: userPrefs.institutionId)
                    ?.takeIf { it.isNotBlank() && it != "INDIVIDUAL" }
                    ?: ""

                val myName = resolveStudentDisplayName(uid, database, studentDoc)
                val remoteFriendCode = studentDoc?.getString("friendCode")
                    ?.takeIf { it.startsWith("SHK-") && it.length == 8 }
                val effectiveFriendCode = remoteFriendCode ?: myFriendCode
                if (remoteFriendCode != null && remoteFriendCode != userPrefs.friendCode) {
                    userPrefs.friendCode = remoteFriendCode
                }

                // 3. Combine local & cloud weekly focus seconds (max of both to avoid undercounting)
                val cloudWeeklySeconds = computeCloudWeeklyFocusSeconds(uid)
                val weeklySeconds = maxOf(localWeeklySeconds, cloudWeeklySeconds)
                val remoteStreak = studentDoc?.getLong("streakDays")?.toInt() ?: 0
                val streakDays = maxOf(localStreakDays, remoteStreak)

                val hasCloudAvatarFields = studentDoc != null &&
                    (studentDoc.contains("avatarBase64") || studentDoc.contains("avatarPresetId"))
                val myAvatarBase64 = if (hasCloudAvatarFields) {
                    studentDoc?.getString("avatarBase64").orEmpty()
                } else {
                    AvatarHelper.getSavedAvatarBase64(appContext)
                }
                val myAvatarPresetId = if (myAvatarBase64.isNotBlank()) {
                    ""
                } else if (hasCloudAvatarFields) {
                    studentDoc?.getString("avatarPresetId").orEmpty()
                } else {
                    AvatarHelper.getSavedAvatarPresetId(appContext)
                }
                if (hasCloudAvatarFields) {
                    AvatarHelper.saveLocalAvatar(appContext, myAvatarBase64, myAvatarPresetId)
                }

                // Sync groups & friends first so badge auto-unlocks know group creator status
                val remoteGroups = (studentDoc?.get("joinedGroupCodes") as? List<*>)
                    ?.mapNotNull { it?.toString()?.trim()?.uppercase() }
                    ?.filter { it.isNotBlank() }
                    ?.toSet()
                    ?: emptySet()
                val allGroupCodes = (userPrefs.joinedGroupCodes + remoteGroups)
                    .map { it.trim().uppercase() }
                    .filter { it.isNotBlank() }
                    .toSet()
                if (allGroupCodes != userPrefs.joinedGroupCodes) {
                    userPrefs.joinedGroupCodes = allGroupCodes
                }

                val remoteFriends = (studentDoc?.get("friendIds") as? List<*>)
                    ?.mapNotNull { it?.toString()?.trim() }
                    ?.filter { it.isNotBlank() }
                    ?.toSet()
                    ?: emptySet()
                val allFriendIds = (userPrefs.addedFriendIds + remoteFriends)
                    .filter { it.isNotBlank() && it != uid }
                    .toSet()
                if (allFriendIds != userPrefs.addedFriendIds) {
                    userPrefs.addedFriendIds = allFriendIds
                }

                // Badges & Mentor Points sync
                val remotePoints = studentDoc?.getLong("mentorPoints")?.toInt() ?: 0
                val mergedPoints = maxOf(remotePoints, SharkBadgeCatalog.getMentorPoints(appContext))
                SharkBadgeCatalog.saveMentorPoints(appContext, mergedPoints)

                val remoteUnlocked = (studentDoc?.get("unlockedBadges") as? List<*>)
                    ?.mapNotNull { it?.toString()?.trim() }
                    ?.filter { it.isNotBlank() }
                    .orEmpty()
                SharkBadgeCatalog.mergeUnlockedBadges(appContext, remoteUnlocked)

                val remoteShowcase = (studentDoc?.get("showcaseBadges") as? List<*>)
                    ?.mapNotNull { it?.toString()?.trim() }
                    ?.filter { it.isNotBlank() }
                    ?.take(SharkBadgeCatalog.MAX_SHOWCASE_BADGES)
                    .orEmpty()
                if (remoteShowcase.isNotEmpty() && SharkBadgeCatalog.getShowcaseBadgeIds(appContext).isEmpty()) {
                    SharkBadgeCatalog.saveShowcaseBadgeIds(appContext, remoteShowcase)
                }

                val approvedSolutionsCloud = studentDoc?.getLong("approvedSolutionsCount")?.toInt() ?: 0
                val provisionalGroupCreator = allGroupCodes.isNotEmpty() &&
                    _uiState.value.joinedGroups.any { it.creatorId == uid }
                SharkBadgeCatalog.evaluateAutomaticUnlocks(
                    context = appContext,
                    mentorPoints = mergedPoints,
                    approvedSolutions = approvedSolutionsCloud,
                    isGroupCreator = provisionalGroupCreator
                )

                val myShowcase = SharkBadgeCatalog.getShowcaseBadgeIds(appContext)
                val myUnlocked = SharkBadgeCatalog.getUnlockedBadgeIds(appContext)

                // 4. Register friendCode & upsert into GLOBAL_INDIVIDUAL (and institution league if any)
                registerFriendCodeAndProfile(
                    uid = uid,
                    name = myName,
                    friendCode = effectiveFriendCode,
                    weeklyFocusSeconds = weeklySeconds,
                    streakDays = streakDays,
                    branchCode = branchCode,
                    avatarBase64 = myAvatarBase64,
                    avatarPresetId = myAvatarPresetId,
                    mentorPoints = mergedPoints,
                    showcaseBadges = myShowcase,
                    unlockedBadges = myUnlocked
                )

                // Sync real name into joined study_groups memberNames map if meaningful
                if (isMeaningfulName(myName)) {
                    for (code in allGroupCodes) {
                        runCatching {
                            database.collection("study_groups").document(code)
                                .set(mapOf("memberNames" to mapOf(uid to myName)), SetOptions.merge())
                        }
                    }
                }

                val myEntry = buildMyLeagueEntry(
                    uid = uid,
                    name = myName,
                    friendCode = effectiveFriendCode,
                    weeklySeconds = weeklySeconds,
                    streakDays = streakDays,
                    avatarBase64 = myAvatarBase64,
                    avatarPresetId = myAvatarPresetId,
                    mentorPoints = mergedPoints,
                    showcaseBadges = myShowcase
                )

                // 5. Fetch Global Individual League (or Institution League if user toggled it)
                val useInst = _uiState.value.useInstitutionFilter && branchCode.isNotBlank()
                val targetBucket = if (useInst) branchCode else GLOBAL_LEAGUE_BUCKET

                val globalEntries = fetchLeaderboardBucket(
                    bucketId = targetBucket,
                    currentUid = uid,
                    myEntry = myEntry
                )

                // 6. Fetch User's Joined Study Groups & Friends comparison list
                val (groupSummaries, socialEntries) = fetchGroupsAndFriendsEntries(
                    uid = uid,
                    myEntry = myEntry,
                    groupCodes = allGroupCodes,
                    friendIds = allFriendIds,
                    selectedGroupCode = _uiState.value.selectedGroupCode
                )

                // Re-evaluate Grup Kaptanı after group summaries load
                val finalUnlocked = if (groupSummaries.any { it.creatorId == uid }) {
                    SharkBadgeCatalog.unlockBadge(appContext, SharkBadgeCatalog.BADGE_GRUP_KAPTANI)
                } else {
                    myUnlocked
                }
                val finalShowcase = SharkBadgeCatalog.getShowcaseBadgeIds(appContext)

                eligibleSocialMemberIds = (allFriendIds + socialEntries.map { it.studentId })
                    .filter { it.isNotBlank() && it != uid }
                    .toSet()

                val activeGroupSummary = groupSummaries.firstOrNull {
                    it.inviteCode == _uiState.value.selectedGroupCode
                }

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    myEntry = myEntry.copy(showcaseBadges = finalShowcase),
                    myFriendCode = effectiveFriendCode,
                    leaderboard = globalEntries,
                    friendsAndGroupMembers = socialEntries,
                    joinedGroups = groupSummaries,
                    institutionCode = branchCode,
                    groupName = activeGroupSummary?.name,
                    needsNameSetup = !isMeaningfulName(myName),
                    myShowcaseBadges = finalShowcase,
                    myUnlockedBadges = finalUnlocked,
                    myMentorPoints = mergedPoints,
                    error = null
                )
            } catch (e: Exception) {
                Log.e("SharkLeagueVM", "League load error", e)
                val (localWeeklySeconds, localStreakDays) = withContext(Dispatchers.IO) {
                    computeLocalWeeklyStats()
                }
                val uid = GoogleServicesHelper.getCurrentUser(auth, appContext)?.uid ?: "local_student"
                val myFriendCode = userPrefs.getOrCreateFriendCode(uid)
                val fallbackName = resolveStudentDisplayName(uid, null, null)
                val fallbackMe = buildMyLeagueEntry(
                    uid = uid,
                    name = fallbackName,
                    friendCode = myFriendCode,
                    weeklySeconds = localWeeklySeconds,
                    streakDays = localStreakDays,
                    mentorPoints = SharkBadgeCatalog.getMentorPoints(appContext),
                    showcaseBadges = SharkBadgeCatalog.getShowcaseBadgeIds(appContext)
                )
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    myEntry = fallbackMe,
                    myFriendCode = myFriendCode,
                    leaderboard = _uiState.value.leaderboard.ifEmpty { listOf(fallbackMe) },
                    friendsAndGroupMembers = _uiState.value.friendsAndGroupMembers.ifEmpty { listOf(fallbackMe) },
                    needsNameSetup = !isMeaningfulName(fallbackName),
                    error = null
                )
            }
        }
    }

    // ─── Create Study Group (6-character invite code) ────────────────────────

    fun createStudyGroup(rawGroupName: String) {
        val cleanName = SyncWorker.sanitizeTurkishText(rawGroupName.trim())
        if (cleanName.length < 2) {
            _uiState.value = _uiState.value.copy(
                statusMessage = "Lütfen en az 2 karakterli bir çalışma grubu adı girin."
            )
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isActionInProgress = true, statusMessage = null)
            try {
                val uid = GoogleServicesHelper.getCurrentUser(auth, appContext)?.uid
                val database = db
                val inviteCode = generateSixCharGroupCode()

                val updatedCodes = userPrefs.joinedGroupCodes + inviteCode
                userPrefs.joinedGroupCodes = updatedCodes

                val unlockedNow = SharkBadgeCatalog.unlockBadge(appContext, SharkBadgeCatalog.BADGE_GRUP_KAPTANI)
                val showcaseNow = SharkBadgeCatalog.getShowcaseBadgeIds(appContext)

                if (database != null && !uid.isNullOrBlank() && uid != "student_default") {
                    val myName = _uiState.value.myEntry?.rawName
                        ?.takeIf { isMeaningfulName(it) }
                        ?: resolveStudentDisplayName(uid, database, null)

                    val groupData = hashMapOf<String, Any>(
                        "inviteCode" to inviteCode,
                        "name" to cleanName,
                        "creatorId" to uid,
                        "creatorName" to myName,
                        "memberIds" to listOf(uid),
                        "memberNames" to mapOf(uid to myName),
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                    database.collection("study_groups").document(inviteCode)
                        .set(groupData, SetOptions.merge())
                        .await()

                    database.collection("students").document(uid)
                        .set(
                            mapOf("joinedGroupCodes" to FieldValue.arrayUnion(inviteCode)),
                            SetOptions.merge()
                        )
                        .await()

                    SharkBadgeCatalog.syncShowcaseAndBadgesToCloud(
                        context = appContext,
                        uid = uid,
                        showcaseIds = showcaseNow,
                        unlockedIds = unlockedNow,
                        mentorPoints = SharkBadgeCatalog.getMentorPoints(appContext)
                    )
                }

                val newGroupSummary = StudyGroupSummary(
                    inviteCode = inviteCode,
                    name = cleanName,
                    memberCount = 1,
                    creatorId = uid ?: ""
                )
                _uiState.value = _uiState.value.copy(
                    isActionInProgress = false,
                    activeTab = LeagueTab.GROUPS_AND_FRIENDS,
                    selectedGroupCode = inviteCode,
                    joinedGroups = (_uiState.value.joinedGroups + newGroupSummary).distinctBy { it.inviteCode },
                    groupName = cleanName,
                    myShowcaseBadges = showcaseNow,
                    myUnlockedBadges = unlockedNow,
                    statusMessage = "\"$cleanName\" grubu oluşturuldu! Davet Kodu: $inviteCode"
                )
                loadLeague(preserveTab = true)
            } catch (e: Exception) {
                Log.e("SharkLeagueVM", "createStudyGroup error", e)
                _uiState.value = _uiState.value.copy(
                    isActionInProgress = false,
                    statusMessage = "Grup oluşturulurken bağlantı hatası oluştu: ${e.localizedMessage ?: "Tekrar deneyin"}"
                )
            }
        }
    }

    // ─── Join Group (6-char code) or Add Friend (SHK-XXXX) ───────────────────

    fun joinByCode(rawInputCode: String) {
        val cleaned = rawInputCode.trim().uppercase(Locale.ROOT)
        if (cleaned.isBlank()) {
            _uiState.value = _uiState.value.copy(
                statusMessage = "Lütfen geçerli bir Arkadaş Kodu (SHK-XXXX) veya 6 haneli Grup Kodu girin."
            )
            return
        }

        val isFriendCode = cleaned.startsWith("SHK-") || cleaned.length == 4
        val normalizedFriendCode = if (cleaned.length == 4) "SHK-$cleaned" else cleaned

        if (isFriendCode && normalizedFriendCode == _uiState.value.myFriendCode) {
            _uiState.value = _uiState.value.copy(
                statusMessage = "Bu senin kendi arkadaş kodun ($normalizedFriendCode)."
            )
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isActionInProgress = true, statusMessage = null)
            try {
                val uid = GoogleServicesHelper.getCurrentUser(auth, appContext)?.uid
                val database = db
                if (database == null || uid.isNullOrBlank() || uid == "student_default") {
                    _uiState.value = _uiState.value.copy(
                        isActionInProgress = false,
                        statusMessage = "Kodla katılmak için internet bağlantısı ve oturum gereklidir."
                    )
                    return@launch
                }

                if (isFriendCode) {
                    // 1. Lookup friend_codes/{normalizedFriendCode} or students where friendCode == normalizedFriendCode
                    var friendUid: String? = null
                    var friendName: String? = null

                    val codeDoc = try {
                        database.collection("friend_codes").document(normalizedFriendCode).get().await()
                    } catch (_: Exception) {
                        null
                    }
                    if (codeDoc != null && codeDoc.exists()) {
                        friendUid = codeDoc.getString("studentId")
                        friendName = extractPreferredName(codeDoc, fallback = "")
                    }

                    if (friendUid.isNullOrBlank()) {
                        val querySnap = database.collection("students")
                            .whereEqualTo("friendCode", normalizedFriendCode)
                            .limit(1)
                            .get()
                            .await()
                        val doc = querySnap.documents.firstOrNull()
                        if (doc != null) {
                            friendUid = doc.id
                            friendName = extractPreferredName(doc, fallback = "")
                        }
                    } else if (!isMeaningfulName(friendName)) {
                        val friendStudentDoc = runCatching {
                            database.collection("students").document(friendUid).get().await()
                        }.getOrNull()
                        friendName = extractPreferredName(friendStudentDoc, codeDoc, fallback = "")
                    }

                    if (friendUid.isNullOrBlank()) {
                        _uiState.value = _uiState.value.copy(
                            isActionInProgress = false,
                            statusMessage = "$normalizedFriendCode koduna ait bir öğrenci bulunamadı."
                        )
                        return@launch
                    }

                    if (friendUid == uid) {
                        _uiState.value = _uiState.value.copy(
                            isActionInProgress = false,
                            statusMessage = "Kendini arkadaş olarak ekleyemezsin."
                        )
                        return@launch
                    }

                    userPrefs.addedFriendIds = userPrefs.addedFriendIds + friendUid
                    eligibleSocialMemberIds = eligibleSocialMemberIds + friendUid
                    database.collection("students").document(uid)
                        .set(mapOf("friendIds" to FieldValue.arrayUnion(friendUid)), SetOptions.merge())
                        .await()
                    try {
                        database.collection("students").document(friendUid)
                            .set(mapOf("friendIds" to FieldValue.arrayUnion(uid)), SetOptions.merge())
                            .await()
                    } catch (_: Exception) {}

                    val cleanFriendName = SyncWorker.sanitizeTurkishText(
                        friendName?.takeIf { isMeaningfulName(it) } ?: "Arkadaş"
                    )
                    _uiState.value = _uiState.value.copy(
                        isActionInProgress = false,
                        activeTab = LeagueTab.GROUPS_AND_FRIENDS,
                        selectedGroupCode = null,
                        statusMessage = "$cleanFriendName ($normalizedFriendCode) arkadaş listene eklendi!"
                    )
                    loadLeague(preserveTab = true)
                } else {
                    // 6-digit Study Group Code
                    val groupCode = cleaned.replace("[^A-Z0-9]".toRegex(), "")
                    val groupDoc = database.collection("study_groups").document(groupCode).get().await()
                    if (!groupDoc.exists()) {
                        _uiState.value = _uiState.value.copy(
                            isActionInProgress = false,
                            statusMessage = "$groupCode kodlu bir çalışma grubu bulunamadı."
                        )
                        return@launch
                    }

                    val groupTitle = SyncWorker.sanitizeTurkishText(
                        groupDoc.getString("name") ?: "Çalışma Grubu"
                    )
                    val myName = _uiState.value.myEntry?.rawName
                        ?.takeIf { isMeaningfulName(it) }
                        ?: resolveStudentDisplayName(uid, database, null)

                    userPrefs.joinedGroupCodes = userPrefs.joinedGroupCodes + groupCode

                    val groupUpdate = hashMapOf<String, Any>(
                        "memberIds" to FieldValue.arrayUnion(uid),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                    if (isMeaningfulName(myName)) {
                        groupUpdate["memberNames"] = mapOf(uid to myName)
                    }
                    database.collection("study_groups").document(groupCode)
                        .set(groupUpdate, SetOptions.merge())
                        .await()

                    database.collection("students").document(uid)
                        .set(
                            mapOf("joinedGroupCodes" to FieldValue.arrayUnion(groupCode)),
                            SetOptions.merge()
                        )
                        .await()

                    _uiState.value = _uiState.value.copy(
                        isActionInProgress = false,
                        activeTab = LeagueTab.GROUPS_AND_FRIENDS,
                        selectedGroupCode = groupCode,
                        groupName = groupTitle,
                        statusMessage = "\"$groupTitle\" ($groupCode) çalışma grubuna katıldın!"
                    )
                    loadLeague(preserveTab = true)
                }
            } catch (e: Exception) {
                Log.e("SharkLeagueVM", "joinByCode error", e)
                _uiState.value = _uiState.value.copy(
                    isActionInProgress = false,
                    statusMessage = "Kod işlenirken hata oluştu: ${e.localizedMessage ?: "Tekrar deneyin"}"
                )
            }
        }
    }

    // ─── Study Invite ("Çalışmaya Davet Et") — Friends & Groups ONLY, 15m Cooldown ──

    fun sendNudge(target: LeagueEntry) {
        nudgeLeagueMember(target)
    }

    fun nudgeLeagueMember(target: LeagueEntry) {
        if (target.isCurrentUser || target.studentId.isBlank()) return

        val isSocialMember = eligibleSocialMemberIds.contains(target.studentId) ||
            userPrefs.addedFriendIds.contains(target.studentId) ||
            _uiState.value.friendsAndGroupMembers.any { !it.isCurrentUser && it.studentId == target.studentId }

        if (!isSocialMember) {
            _uiState.value = _uiState.value.copy(
                statusMessage = "Çalışma daveti yalnızca arkadaşlarına veya grup üyelerine gönderilebilir."
            )
            return
        }

        val now = System.currentTimeMillis()
        val lastLocalNudge = maxOf(target.lastNudgeSentMillis, userPrefs.getLastNudgeMillis(target.studentId))

        if (now - lastLocalNudge < NUDGE_COOLDOWN_MS) {
            val waitLabel = target.copy(lastNudgeSentMillis = lastLocalNudge).remainingNudgeCooldownLabel(now)
            _uiState.value = _uiState.value.copy(
                statusMessage = "${target.displayName} yakın zamanda davet edildi. $waitLabel sonra tekrar davet edebilirsin."
            )
            return
        }

        viewModelScope.launch {
            try {
                val uid = GoogleServicesHelper.getCurrentUser(auth, appContext)?.uid
                val database = db
                if (database == null || uid.isNullOrBlank() || uid == "local_student" || uid == "student_default") {
                    _uiState.value = _uiState.value.copy(
                        statusMessage = "Çalışma daveti göndermek için internet bağlantısı ve oturum gereklidir."
                    )
                    return@launch
                }

                // Check server-side 15m cooldown record if available
                val sentNudgeRef = database.collection("students").document(uid)
                    .collection("sent_nudges").document(target.studentId)
                val existingSnap = try { sentNudgeRef.get().await() } catch (_: Exception) { null }
                val remoteLastMs = existingSnap?.getLong("lastSentAtMillis") ?: 0L
                if (now - remoteLastMs < NUDGE_COOLDOWN_MS) {
                    userPrefs.recordNudgeSent(target.studentId, remoteLastMs)
                    updateEntryNudgeTimestamp(target.studentId, remoteLastMs)
                    val waitLabel = target.copy(lastNudgeSentMillis = remoteLastMs).remainingNudgeCooldownLabel(now)
                    _uiState.value = _uiState.value.copy(
                        statusMessage = "${target.displayName} yakın zamanda davet edildi ($waitLabel kaldı)."
                    )
                    return@launch
                }

                val myName = SyncWorker.sanitizeTurkishText(
                    _uiState.value.myEntry?.rawName?.takeIf { isMeaningfulName(it) }
                        ?: resolveStudentDisplayName(uid, database, null)
                )
                val targetCleanName = SyncWorker.sanitizeTurkishText(
                    target.rawName.ifBlank { target.displayName }
                )
                val nudgeDocId = "${uid}_${target.studentId}_${now / NUDGE_COOLDOWN_MS}"
                val activeGroupId = _uiState.value.selectedGroupCode
                    ?: _uiState.value.joinedGroups.firstOrNull()?.inviteCode

                val nudgePayload = hashMapOf<String, Any?>(
                    "id" to nudgeDocId,
                    "fromUid" to uid,
                    "fromName" to myName,
                    "senderId" to uid,
                    "senderName" to myName,
                    "toUid" to target.studentId,
                    "toName" to targetCleanName,
                    "receiverId" to target.studentId,
                    "receiverName" to targetCleanName,
                    "groupId" to activeGroupId,
                    "type" to "STUDY_INVITE",
                    "message" to "🦈 $myName seni birlikte çalışmaya davet ediyor! Masaya geçip odak seansını başlat.",
                    "createdAt" to FieldValue.serverTimestamp(),
                    "createdAtMillis" to now,
                    "seen" to false
                )

                database.collection("nudges").document(nudgeDocId)
                    .set(nudgePayload, SetOptions.merge())
                    .await()

                try {
                    sentNudgeRef.set(
                        mapOf(
                            "targetStudentId" to target.studentId,
                            "lastSentAtMillis" to now,
                            "updatedAt" to FieldValue.serverTimestamp()
                        ),
                        SetOptions.merge()
                    ).await()
                } catch (_: Exception) {}

                userPrefs.recordNudgeSent(target.studentId, now)
                updateEntryNudgeTimestamp(target.studentId, now)

                _uiState.value = _uiState.value.copy(
                    statusMessage = "🦈 $targetCleanName çalışma davetiyle çağrıldı!"
                )
            } catch (e: Exception) {
                Log.e("SharkLeagueVM", "nudgeLeagueMember error", e)
                _uiState.value = _uiState.value.copy(
                    statusMessage = "Çalışma daveti gönderilemedi: ${e.localizedMessage ?: "Bağlantıyı kontrol edin"}"
                )
            }
        }
    }

    // ─── Showcase Badges & Request-Approved DM ───────────────────────────────

    fun updateMyShowcaseBadges(selectedBadgeIds: List<String>) {
        val saved = SharkBadgeCatalog.saveShowcaseBadgeIds(appContext, selectedBadgeIds)
        val current = _uiState.value
        _uiState.value = current.copy(
            myShowcaseBadges = saved,
            myEntry = current.myEntry?.copy(showcaseBadges = saved),
            leaderboard = current.leaderboard.map {
                if (it.isCurrentUser) it.copy(showcaseBadges = saved) else it
            },
            friendsAndGroupMembers = current.friendsAndGroupMembers.map {
                if (it.isCurrentUser) it.copy(showcaseBadges = saved) else it
            },
            statusMessage = "3 rozet vitrinin güncellendi!"
        )

        val uid = GoogleServicesHelper.getCurrentUser(auth, appContext)?.uid ?: return
        viewModelScope.launch {
            SharkBadgeCatalog.syncShowcaseAndBadgesToCloud(
                context = appContext,
                uid = uid,
                showcaseIds = saved,
                unlockedIds = SharkBadgeCatalog.getUnlockedBadgeIds(appContext),
                mentorPoints = SharkBadgeCatalog.getMentorPoints(appContext)
            )
        }
    }

    fun sendDmRequest(target: LeagueEntry, note: String) {
        if (target.isCurrentUser || target.studentId.isBlank()) return
        viewModelScope.launch {
            val uid = GoogleServicesHelper.getCurrentUser(auth, appContext)?.uid
            val database = db
            if (database == null || uid.isNullOrBlank() || uid == "local_student" || uid == "student_default") {
                _uiState.value = _uiState.value.copy(
                    statusMessage = "Mesaj isteği göndermek için oturum ve internet bağlantısı gerekir."
                )
                return@launch
            }

            try {
                val threadId = listOf(uid.trim(), target.studentId.trim()).sorted().joinToString("_")
                val existingDoc = runCatching {
                    database.collection("dm_threads").document(threadId).get().await()
                }.getOrNull()
                val existingStatus = existingDoc?.getString("status").orEmpty()

                if (existingStatus == "ACCEPTED") {
                    _uiState.value = _uiState.value.copy(
                        dmStatusByPeerId = _uiState.value.dmStatusByPeerId + (target.studentId to "ACCEPTED"),
                        statusMessage = "Bu öğrenciyle direkt sohbetin zaten onaylı."
                    )
                    return@launch
                }
                if (existingStatus == "PENDING") {
                    _uiState.value = _uiState.value.copy(
                        dmStatusByPeerId = _uiState.value.dmStatusByPeerId + (target.studentId to "PENDING"),
                        statusMessage = "Mesaj isteği zaten beklemede."
                    )
                    return@launch
                }

                val myName = SyncWorker.sanitizeTurkishText(
                    _uiState.value.myEntry?.rawName?.takeIf { isMeaningfulName(it) }
                        ?: resolveStudentDisplayName(uid, database, null)
                )
                val peerName = SyncWorker.sanitizeTurkishText(
                    target.rawName.ifBlank { target.displayName }
                )
                val cleanNote = SyncWorker.sanitizeTurkishText(note.trim()).ifBlank {
                    "Soru & çalışma hakkında direkt mesaj isteği"
                }
                val now = System.currentTimeMillis()
                val payload = hashMapOf<String, Any>(
                    "threadId" to threadId,
                    "participantIds" to listOf(uid, target.studentId).sorted(),
                    "participantNames" to mapOf(
                        uid to myName,
                        target.studentId to peerName
                    ),
                    "participantAvatarsBase64" to mapOf(
                        uid to AvatarHelper.getSavedAvatarBase64(appContext),
                        target.studentId to target.avatarBase64
                    ),
                    "participantAvatarPresets" to mapOf(
                        uid to AvatarHelper.getSavedAvatarPresetId(appContext),
                        target.studentId to target.avatarPresetId
                    ),
                    "requesterId" to uid,
                    "requesterName" to myName,
                    "recipientId" to target.studentId,
                    "recipientName" to peerName,
                    "status" to "PENDING",
                    "requestNote" to cleanNote,
                    "lastMessageText" to cleanNote,
                    "createdAtMillis" to now,
                    "updatedAtMillis" to now,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )

                database.collection("dm_threads").document(threadId)
                    .set(payload, SetOptions.merge())
                    .await()

                _uiState.value = _uiState.value.copy(
                    dmStatusByPeerId = _uiState.value.dmStatusByPeerId + (target.studentId to "PENDING"),
                    statusMessage = "$peerName için direkt mesaj isteği gönderildi!"
                )
            } catch (e: Exception) {
                Log.w("SharkLeagueVM", "sendDmRequest warning: ${e.message}")
                _uiState.value = _uiState.value.copy(
                    statusMessage = "Mesaj isteği gönderilemedi: ${e.localizedMessage ?: "Bağlantıyı kontrol edin"}"
                )
            }
        }
    }

    // ─── Internal Helpers ────────────────────────────────────────────────────

    private fun updateEntryNudgeTimestamp(targetStudentId: String, timestampMillis: Long) {
        val current = _uiState.value
        _uiState.value = current.copy(
            leaderboard = current.leaderboard.map {
                if (it.studentId == targetStudentId) it.copy(lastNudgeSentMillis = timestampMillis) else it
            },
            friendsAndGroupMembers = current.friendsAndGroupMembers.map {
                if (it.studentId == targetStudentId) it.copy(lastNudgeSentMillis = timestampMillis) else it
            }
        )
    }

    private fun buildMyLeagueEntry(
        uid: String,
        name: String,
        friendCode: String,
        weeklySeconds: Long,
        streakDays: Int,
        avatarBase64: String = AvatarHelper.getSavedAvatarBase64(appContext),
        avatarPresetId: String = AvatarHelper.getSavedAvatarPresetId(appContext),
        mentorPoints: Int = SharkBadgeCatalog.getMentorPoints(appContext),
        showcaseBadges: List<String> = SharkBadgeCatalog.getShowcaseBadgeIds(appContext)
    ): LeagueEntry {
        val cleanName = SyncWorker.sanitizeTurkishText(name.ifBlank { "Öğrenci" })
        val dailyAvgMin = ((weeklySeconds / 60L) / 7L).toInt()
        val effectivePresetId = if (avatarBase64.isNotBlank()) "" else avatarPresetId
        return LeagueEntry(
            studentId = uid,
            displayName = "Sen ($cleanName)",
            rawName = cleanName,
            friendCode = friendCode,
            weeklyFocusSeconds = weeklySeconds,
            dailyAverageMinutes = dailyAvgMin,
            streakDays = streakDays,
            tier = weeklySeconds.toTier(),
            isCurrentUser = true,
            avatarBase64 = avatarBase64,
            avatarPresetId = effectivePresetId,
            mentorPoints = mentorPoints,
            showcaseBadges = showcaseBadges
        )
    }

    private fun computeLocalWeeklyStats(): Pair<Long, Int> {
        return try {
            val now = System.currentTimeMillis()
            val weekStart = now - 7L * 24 * 3600 * 1000
            val sessions = roomDb.sessionDao().getSessionsSince(weekStart)
            val weeklySeconds = sessions.sumOf { it.durationSeconds }

            // Compute active streak days from local sessions
            val allRecent = roomDb.sessionDao().getSessionsSince(now - 30L * 24 * 3600 * 1000)
            val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val activeDays = allRecent
                .filter { it.durationSeconds >= 600L } // at least 10 mins in a day
                .map { dateFmt.format(Date(it.completedAt)) }
                .toSet()

            var streak = 0
            val dayMs = 24L * 3600L * 1000L
            for (offset in 0..30) {
                val dayKey = dateFmt.format(Date(now - offset * dayMs))
                if (activeDays.contains(dayKey)) {
                    streak++
                } else if (offset == 0) {
                    // Today might not have a completed session yet; check yesterday
                    continue
                } else {
                    break
                }
            }
            weeklySeconds to streak
        } catch (e: Exception) {
            0L to 0
        }
    }

    private suspend fun computeCloudWeeklyFocusSeconds(uid: String): Long {
        return try {
            val database = db ?: return 0L
            val now = System.currentTimeMillis()
            val weekStart = now - 7L * 24 * 3600 * 1000

            val sessionsSnap = try {
                database.collection("students").document(uid)
                    .collection("sessions")
                    .whereGreaterThanOrEqualTo("timestamp", weekStart)
                    .get()
                    .await()
            } catch (_: Exception) {
                null
            }
            val fromSessions = sessionsSnap?.documents?.sumOf { doc ->
                doc.getLong("durationSeconds") ?: 0L
            } ?: 0L

            if (fromSessions > 0L) return fromSessions

            val focusSnap = try {
                database.collection("students").document(uid)
                    .collection("focus_sessions")
                    .whereGreaterThanOrEqualTo("timestamp", weekStart)
                    .get()
                    .await()
            } catch (_: Exception) {
                null
            }
            focusSnap?.documents?.sumOf { doc ->
                doc.getLong("durationSeconds") ?: 0L
            } ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    private suspend fun registerFriendCodeAndProfile(
        uid: String,
        name: String,
        friendCode: String,
        weeklyFocusSeconds: Long,
        streakDays: Int,
        branchCode: String,
        avatarBase64: String = "",
        avatarPresetId: String = "",
        mentorPoints: Int = 0,
        showcaseBadges: List<String> = emptyList(),
        unlockedBadges: Set<String> = emptySet()
    ) {
        try {
            val database = db ?: return
            val dailyAverageMinutes = ((weeklyFocusSeconds / 60L) / 7L).toInt()
            val effectivePresetId = if (avatarBase64.isNotBlank()) "" else avatarPresetId
            val leaguePayload = hashMapOf<String, Any>(
                "studentId" to uid,
                "displayName" to name,
                "friendCode" to friendCode,
                "weeklyFocusSeconds" to weeklyFocusSeconds,
                "dailyAverageMinutes" to dailyAverageMinutes,
                "streakDays" to streakDays,
                "tier" to weeklyFocusSeconds.toTier().name,
                "avatarBase64" to avatarBase64,
                "avatarPresetId" to effectivePresetId,
                "mentorPoints" to mentorPoints,
                "showcaseBadges" to showcaseBadges,
                "lastSessionAt" to FieldValue.serverTimestamp()
            )

            // 1. Always upsert into Bireysel Genel Lig (GLOBAL_INDIVIDUAL)
            database.collection("institutions").document(GLOBAL_LEAGUE_BUCKET)
                .collection("weeklyLeague").document(uid)
                .set(leaguePayload, SetOptions.merge())
                .await()

            // 2. If user also belongs to an institution, upsert into institution weeklyLeague
            if (branchCode.isNotBlank() && branchCode != GLOBAL_LEAGUE_BUCKET) {
                database.collection("institutions").document(branchCode)
                    .collection("weeklyLeague").document(uid)
                    .set(leaguePayload, SetOptions.merge())
                    .await()
            }

            // 3. Register friend_codes/{friendCode} for instant lookup
            try {
                database.collection("friend_codes").document(friendCode)
                    .set(
                        mapOf(
                            "friendCode" to friendCode,
                            "studentId" to uid,
                            "displayName" to name,
                            "updatedAt" to FieldValue.serverTimestamp()
                        ),
                        SetOptions.merge()
                    )
                    .await()
            } catch (_: Exception) {}

            // 4. Mirror summary stats on students/{uid} so friends & groups can read them
            val studentPayload = hashMapOf<String, Any>(
                "id" to uid,
                "friendCode" to friendCode,
                "weeklyFocusSeconds" to weeklyFocusSeconds,
                "streakDays" to streakDays,
                "mentorPoints" to mentorPoints,
                "showcaseBadges" to showcaseBadges,
                "unlockedBadges" to unlockedBadges.toList(),
                "lastActiveAt" to FieldValue.serverTimestamp()
            )
            if (isMeaningfulName(name)) {
                studentPayload["name"] = name
                studentPayload["displayName"] = name
            }
            database.collection("students").document(uid)
                .set(studentPayload, SetOptions.merge())
                .await()
        } catch (e: Exception) {
            Log.w("SharkLeagueVM", "registerFriendCodeAndProfile warning: ${e.message}")
        }
    }

    private suspend fun fetchLeaderboardBucket(
        bucketId: String,
        currentUid: String,
        myEntry: LeagueEntry
    ): List<LeagueEntry> {
        return try {
            val database = db ?: return listOf(myEntry)
            val leagueSnap = database.collection("institutions")
                .document(bucketId)
                .collection("weeklyLeague")
                .orderBy("weeklyFocusSeconds", Query.Direction.DESCENDING)
                .limit(50)
                .get()
                .await()

            val mapped = coroutineScope {
                leagueSnap.documents.mapIndexed { idx, doc ->
                    async {
                        val id = doc.id
                        val isMe = (id == currentUid)
                        val secs = if (isMe) maxOf(myEntry.weeklyFocusSeconds, doc.getLong("weeklyFocusSeconds") ?: 0L)
                        else (doc.getLong("weeklyFocusSeconds") ?: 0L)
                        val streak = if (isMe) maxOf(myEntry.streakDays, doc.getLong("streakDays")?.toInt() ?: 0)
                        else (doc.getLong("streakDays")?.toInt() ?: 0)
                        val fCode = doc.getString("friendCode") ?: ""

                        val studentSnap = if (isMe) {
                            null
                        } else {
                            runCatching {
                                database.collection("students").document(id).get().await()
                            }.getOrNull()
                        }

                        val rawName = if (isMe) {
                            myEntry.rawName
                        } else {
                            extractPreferredName(studentSnap, doc, fallback = "Öğrenci")
                        }

                        val studentAvatarPair = if (isMe) {
                            myEntry.avatarBase64 to myEntry.avatarPresetId
                        } else {
                            val docBase64 = doc.getString("avatarBase64").orEmpty()
                            val docPreset = doc.getString("avatarPresetId").orEmpty()
                            val studentBase64 = studentSnap?.getString("avatarBase64").orEmpty()
                            val studentPreset = studentSnap?.getString("avatarPresetId").orEmpty()
                            val resolvedBase64 = studentBase64.ifBlank { docBase64 }
                            val resolvedPreset = if (resolvedBase64.isNotBlank()) {
                                ""
                            } else {
                                studentPreset.ifBlank { docPreset }
                            }
                            resolvedBase64 to resolvedPreset
                        }

                        val points = if (isMe) {
                            myEntry.mentorPoints
                        } else {
                            maxOf(
                                studentSnap?.getLong("mentorPoints")?.toInt() ?: 0,
                                doc.getLong("mentorPoints")?.toInt() ?: 0
                            )
                        }

                        val badges = if (isMe) {
                            myEntry.showcaseBadges
                        } else {
                            val fromStudent = (studentSnap?.get("showcaseBadges") as? List<*>)
                                ?.mapNotNull { it?.toString()?.trim() }
                                ?.filter { it.isNotBlank() }
                                ?.take(SharkBadgeCatalog.MAX_SHOWCASE_BADGES)
                                .orEmpty()
                            if (fromStudent.isNotEmpty()) {
                                fromStudent
                            } else {
                                (doc.get("showcaseBadges") as? List<*>)
                                    ?.mapNotNull { it?.toString()?.trim() }
                                    ?.filter { it.isNotBlank() }
                                    ?.take(SharkBadgeCatalog.MAX_SHOWCASE_BADGES)
                                    .orEmpty()
                            }
                        }

                        LeagueEntry(
                            studentId = id,
                            displayName = formatLeaderboardCallSign(rawName, idx + 1, isMe, showFullForFriends = true),
                            rawName = rawName,
                            friendCode = fCode,
                            weeklyFocusSeconds = secs,
                            dailyAverageMinutes = ((secs / 60L) / 7L).toInt(),
                            streakDays = streak,
                            tier = secs.toTier(),
                            isCurrentUser = isMe,
                            isStudyingNow = if (isMe) myEntry.isStudyingNow else (studentSnap?.getBoolean("isStudying") ?: false),
                            activeSubject = if (isMe) myEntry.activeSubject else studentSnap?.getString("activeSubject"),
                            lastNudgeSentMillis = userPrefs.getLastNudgeMillis(id),
                            avatarBase64 = studentAvatarPair.first,
                            avatarPresetId = studentAvatarPair.second,
                            mentorPoints = points,
                            showcaseBadges = badges
                        )
                    }
                }.awaitAll()
            }

            val withMe = if (mapped.any { it.isCurrentUser }) {
                mapped
            } else {
                (mapped + myEntry)
            }

            withMe.sortedByDescending { it.weeklyFocusSeconds }
                .mapIndexed { idx, entry ->
                    entry.copy(
                        displayName = formatLeaderboardCallSign(
                            name = entry.rawName,
                            rank = idx + 1,
                            isCurrentUser = entry.isCurrentUser,
                            showFullForFriends = true
                        )
                    )
                }
        } catch (e: Exception) {
            Log.w("SharkLeagueVM", "fetchLeaderboardBucket failed for $bucketId: ${e.message}")
            listOf(myEntry)
        }
    }

    private suspend fun fetchGroupsAndFriendsEntries(
        uid: String,
        myEntry: LeagueEntry,
        groupCodes: Set<String>,
        friendIds: Set<String>,
        selectedGroupCode: String?
    ): Pair<List<StudyGroupSummary>, List<LeagueEntry>> {
        val database = db ?: return emptyList<StudyGroupSummary>() to listOf(myEntry)
        val groupSummaries = mutableListOf<StudyGroupSummary>()
        val groupMemberMap = mutableMapOf<String, Set<String>>()
        val memberNamesLookup = mutableMapOf<String, String>()

        for (code in groupCodes) {
            try {
                val doc = database.collection("study_groups").document(code).get().await()
                if (doc.exists()) {
                    val gName = SyncWorker.sanitizeTurkishText(doc.getString("name") ?: "Grup $code")
                    val members = (doc.get("memberIds") as? List<*>)
                        ?.mapNotNull { it?.toString()?.trim() }
                        ?.filter { it.isNotBlank() }
                        ?.toSet()
                        ?: setOf(uid)
                    val namesMap = doc.get("memberNames") as? Map<*, *>
                    namesMap?.forEach { (k, v) ->
                        val memberKey = k?.toString()?.trim().orEmpty()
                        val memberVal = SyncWorker.sanitizeTurkishText(v?.toString()).trim()
                        if (memberKey.isNotBlank() && isMeaningfulName(memberVal)) {
                            memberNamesLookup[memberKey] = memberVal
                        }
                    }
                    groupSummaries.add(
                        StudyGroupSummary(
                            inviteCode = code,
                            name = gName,
                            memberCount = members.size.coerceAtLeast(1),
                            creatorId = doc.getString("creatorId") ?: ""
                        )
                    )
                    groupMemberMap[code] = members
                }
            } catch (e: Exception) {
                Log.w("SharkLeagueVM", "Failed to load study_group $code: ${e.message}")
            }
        }

        val targetMemberIds: Set<String> = if (!selectedGroupCode.isNullOrBlank()) {
            (groupMemberMap[selectedGroupCode] ?: emptySet()) + uid
        } else {
            val allGroupMembers = groupMemberMap.values.flatten().toSet()
            allGroupMembers + friendIds + uid
        }

        val entries = mutableListOf<LeagueEntry>()
        entries.add(myEntry)

        for (memberId in targetMemberIds) {
            if (memberId == uid || memberId.isBlank()) continue
            try {
                // Check students/{memberId} and GLOBAL_INDIVIDUAL weeklyLeague
                val studentSnap = database.collection("students").document(memberId).get().await()
                val globalLeagueSnap = try {
                    database.collection("institutions").document(GLOBAL_LEAGUE_BUCKET)
                        .collection("weeklyLeague").document(memberId).get().await()
                } catch (_: Exception) {
                    null
                }

                val extracted = extractPreferredName(studentSnap, globalLeagueSnap, fallback = "")
                val mapFallback = memberNamesLookup[memberId]
                val rawName = SyncWorker.sanitizeTurkishText(
                    when {
                        isMeaningfulName(extracted) -> extracted
                        isMeaningfulName(mapFallback) -> mapFallback!!
                        else -> extractPreferredName(studentSnap, globalLeagueSnap, fallback = "Arkadaş")
                    }
                )
                val weeklySecs = maxOf(
                    studentSnap.getLong("weeklyFocusSeconds") ?: 0L,
                    globalLeagueSnap?.getLong("weeklyFocusSeconds") ?: 0L
                )
                val streak = maxOf(
                    studentSnap.getLong("streakDays")?.toInt() ?: 0,
                    globalLeagueSnap?.getLong("streakDays")?.toInt() ?: 0
                )
                val fCode = studentSnap.getString("friendCode")
                    ?: globalLeagueSnap?.getString("friendCode")
                    ?: ""
                val isStudying = studentSnap.getBoolean("isStudying") ?: false
                val activeSubj = studentSnap.getString("activeSubject")
                val avatarBase64 = studentSnap.getString("avatarBase64")?.takeIf { it.isNotBlank() }
                    ?: globalLeagueSnap?.getString("avatarBase64").orEmpty()
                val avatarPresetId = if (avatarBase64.isNotBlank()) {
                    ""
                } else {
                    studentSnap.getString("avatarPresetId")?.takeIf { it.isNotBlank() }
                        ?: globalLeagueSnap?.getString("avatarPresetId").orEmpty()
                }
                val points = maxOf(
                    studentSnap.getLong("mentorPoints")?.toInt() ?: 0,
                    globalLeagueSnap?.getLong("mentorPoints")?.toInt() ?: 0
                )
                val studentShowcase = (studentSnap.get("showcaseBadges") as? List<*>)
                    ?.mapNotNull { it?.toString()?.trim() }
                    ?.filter { it.isNotBlank() }
                    ?.take(SharkBadgeCatalog.MAX_SHOWCASE_BADGES)
                    .orEmpty()
                val showcaseBadges = if (studentShowcase.isNotEmpty()) {
                    studentShowcase
                } else {
                    (globalLeagueSnap?.get("showcaseBadges") as? List<*>)
                        ?.mapNotNull { it?.toString()?.trim() }
                        ?.filter { it.isNotBlank() }
                        ?.take(SharkBadgeCatalog.MAX_SHOWCASE_BADGES)
                        .orEmpty()
                }

                entries.add(
                    LeagueEntry(
                        studentId = memberId,
                        displayName = rawName,
                        rawName = rawName,
                        friendCode = fCode,
                        weeklyFocusSeconds = weeklySecs,
                        dailyAverageMinutes = ((weeklySecs / 60L) / 7L).toInt(),
                        streakDays = streak,
                        tier = weeklySecs.toTier(),
                        isCurrentUser = false,
                        isStudyingNow = isStudying,
                        activeSubject = activeSubj,
                        lastNudgeSentMillis = userPrefs.getLastNudgeMillis(memberId),
                        avatarBase64 = avatarBase64,
                        avatarPresetId = avatarPresetId,
                        mentorPoints = points,
                        showcaseBadges = showcaseBadges
                    )
                )
            } catch (e: Exception) {
                Log.w("SharkLeagueVM", "Failed to load friend/member $memberId: ${e.message}")
            }
        }

        val sortedEntries = entries
            .distinctBy { it.studentId }
            .sortedByDescending { it.weeklyFocusSeconds }
            .mapIndexed { idx, item ->
                item.copy(
                    displayName = formatLeaderboardCallSign(
                        name = item.rawName,
                        rank = idx + 1,
                        isCurrentUser = item.isCurrentUser,
                        showFullForFriends = true
                    )
                )
            }

        return groupSummaries.sortedBy { it.name } to sortedEntries
    }

    private fun generateSixCharGroupCode(): String {
        val alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
        var seed = abs(System.currentTimeMillis() xor System.nanoTime())
        val sb = StringBuilder(6)
        repeat(6) { idx ->
            val charIdx = ((seed + idx * 19) % alphabet.length).toInt()
            sb.append(alphabet[charIdx])
            seed /= alphabet.length.coerceAtLeast(2)
            if (seed == 0L) seed = abs(System.nanoTime() + idx * 37)
        }
        return sb.toString()
    }

    /**
     * Renders full resolved names cleanly in both Global League and Groups & Friends
     * (never masking to initials like "Ö. #1").
     */
    private fun formatLeaderboardCallSign(
        name: String,
        @Suppress("UNUSED_PARAMETER") rank: Int,
        isCurrentUser: Boolean,
        @Suppress("UNUSED_PARAMETER") showFullForFriends: Boolean
    ): String {
        val clean = SyncWorker.sanitizeTurkishText(name.ifBlank { "Öğrenci" })
        return if (isCurrentUser) "Sen ($clean)" else clean
    }

    override fun onCleared() {
        incomingInvitesListener?.remove()
        incomingInvitesListener = null
        incomingDmListener?.remove()
        incomingDmListener = null
        super.onCleared()
    }
}
