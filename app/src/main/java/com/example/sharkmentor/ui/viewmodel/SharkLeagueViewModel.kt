package com.example.sharkmentor.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.sharkmentor.core.util.AvatarHelper
import com.example.sharkmentor.core.util.GoogleServicesHelper
import com.example.sharkmentor.data.local.SharkMentorDatabase
import com.example.sharkmentor.data.local.preferences.UserPreferences
import com.example.sharkmentor.service.sync.SyncWorker
import com.google.firebase.firestore.FieldValue
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

/** 12-hour anti-spam cooldown for nudging the same friend/group member. */
const val NUDGE_COOLDOWN_MS: Long = 12L * 60L * 60L * 1_000L

const val GLOBAL_LEAGUE_BUCKET = "GLOBAL_INDIVIDUAL"

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
    val avatarPresetId: String = ""
) {
    fun canBeNudged(nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (isCurrentUser || studentId.isBlank()) return false
        return (nowMillis - lastNudgeSentMillis) >= NUDGE_COOLDOWN_MS
    }

    fun remainingNudgeCooldownLabel(nowMillis: Long = System.currentTimeMillis()): String {
        val remMs = (NUDGE_COOLDOWN_MS - (nowMillis - lastNudgeSentMillis)).coerceAtLeast(0L)
        if (remMs <= 0L) return "Dürt"
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
    val error: String? = null
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
            )
        )
    )
    val uiState: StateFlow<SharkLeagueUiState> = _uiState.asStateFlow()

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

                val localName = SyncWorker.sanitizeTurkishText(
                    userPrefs.studentName?.takeIf { it.isNotBlank() } ?: "Öğrenci"
                )

                val database = db
                if (database == null || uid == "local_student" || uid == "student_default") {
                    val fallbackMe = buildMyLeagueEntry(
                        uid = uid,
                        name = localName,
                        friendCode = myFriendCode,
                        weeklySeconds = localWeeklySeconds,
                        streakDays = localStreakDays
                    )
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        myEntry = fallbackMe,
                        myFriendCode = myFriendCode,
                        leaderboard = listOf(fallbackMe),
                        friendsAndGroupMembers = listOf(fallbackMe)
                    )
                    return@launch
                }

                // 2. Read student doc from Firestore (without requiring branchCode!)
                val studentDoc = try {
                    database.collection("students").document(uid).get().await()
                } catch (e: Exception) {
                    null
                }

                val branchCode = (studentDoc?.getString("branchCode")
                    ?: studentDoc?.getString("institutionCode")
                    ?: userPrefs.institutionId)
                    ?.takeIf { it.isNotBlank() && it != "INDIVIDUAL" }
                    ?: ""

                val myName = SyncWorker.sanitizeTurkishText(
                    studentDoc?.getString("name")?.takeIf { it.isNotBlank() } ?: localName
                )
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

                // 4. Register friendCode & upsert into GLOBAL_INDIVIDUAL (and institution league if any)
                registerFriendCodeAndProfile(
                    uid = uid,
                    name = myName,
                    friendCode = effectiveFriendCode,
                    weeklyFocusSeconds = weeklySeconds,
                    streakDays = streakDays,
                    branchCode = branchCode,
                    avatarBase64 = myAvatarBase64,
                    avatarPresetId = myAvatarPresetId
                )

                val myEntry = buildMyLeagueEntry(
                    uid = uid,
                    name = myName,
                    friendCode = effectiveFriendCode,
                    weeklySeconds = weeklySeconds,
                    streakDays = streakDays,
                    avatarBase64 = myAvatarBase64,
                    avatarPresetId = myAvatarPresetId
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

                val (groupSummaries, socialEntries) = fetchGroupsAndFriendsEntries(
                    uid = uid,
                    myEntry = myEntry,
                    groupCodes = allGroupCodes,
                    friendIds = allFriendIds,
                    selectedGroupCode = _uiState.value.selectedGroupCode
                )

                val activeGroupSummary = groupSummaries.firstOrNull {
                    it.inviteCode == _uiState.value.selectedGroupCode
                }

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    myEntry = myEntry,
                    myFriendCode = effectiveFriendCode,
                    leaderboard = globalEntries,
                    friendsAndGroupMembers = socialEntries,
                    joinedGroups = groupSummaries,
                    institutionCode = branchCode,
                    groupName = activeGroupSummary?.name,
                    error = null
                )
            } catch (e: Exception) {
                Log.e("SharkLeagueVM", "League load error", e)
                val (localWeeklySeconds, localStreakDays) = withContext(Dispatchers.IO) {
                    computeLocalWeeklyStats()
                }
                val uid = GoogleServicesHelper.getCurrentUser(auth, appContext)?.uid ?: "local_student"
                val myFriendCode = userPrefs.getOrCreateFriendCode(uid)
                val fallbackMe = buildMyLeagueEntry(
                    uid = uid,
                    name = SyncWorker.sanitizeTurkishText(userPrefs.studentName ?: "Öğrenci"),
                    friendCode = myFriendCode,
                    weeklySeconds = localWeeklySeconds,
                    streakDays = localStreakDays
                )
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    myEntry = fallbackMe,
                    myFriendCode = myFriendCode,
                    leaderboard = _uiState.value.leaderboard.ifEmpty { listOf(fallbackMe) },
                    friendsAndGroupMembers = _uiState.value.friendsAndGroupMembers.ifEmpty { listOf(fallbackMe) },
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

                if (database != null && !uid.isNullOrBlank() && uid != "student_default") {
                    val myName = _uiState.value.myEntry?.rawName?.ifBlank { "Öğrenci" } ?: "Öğrenci"
                    val groupData = hashMapOf<String, Any>(
                        "inviteCode" to inviteCode,
                        "name" to cleanName,
                        "creatorId" to uid,
                        "creatorName" to myName,
                        "memberIds" to listOf(uid),
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
                        friendName = codeDoc.getString("displayName")
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
                            friendName = doc.getString("name") ?: doc.getString("displayName")
                        }
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
                    database.collection("students").document(uid)
                        .set(mapOf("friendIds" to FieldValue.arrayUnion(friendUid)), SetOptions.merge())
                        .await()
                    try {
                        database.collection("students").document(friendUid)
                            .set(mapOf("friendIds" to FieldValue.arrayUnion(uid)), SetOptions.merge())
                            .await()
                    } catch (_: Exception) {}

                    val cleanFriendName = SyncWorker.sanitizeTurkishText(friendName ?: "Arkadaş")
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
                    userPrefs.joinedGroupCodes = userPrefs.joinedGroupCodes + groupCode

                    database.collection("study_groups").document(groupCode)
                        .set(
                            mapOf(
                                "memberIds" to FieldValue.arrayUnion(uid),
                                "updatedAt" to FieldValue.serverTimestamp()
                            ),
                            SetOptions.merge()
                        )
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

    // ─── Nudge ("Dürt") with 12-Hour Anti-Spam Protection ────────────────────

    fun sendNudge(target: LeagueEntry) {
        if (target.isCurrentUser || target.studentId.isBlank()) return
        val now = System.currentTimeMillis()
        val lastLocalNudge = maxOf(target.lastNudgeSentMillis, userPrefs.getLastNudgeMillis(target.studentId))

        if (now - lastLocalNudge < NUDGE_COOLDOWN_MS) {
            val waitLabel = target.copy(lastNudgeSentMillis = lastLocalNudge).remainingNudgeCooldownLabel(now)
            _uiState.value = _uiState.value.copy(
                statusMessage = "${target.displayName} için dürtme koruması aktif. $waitLabel sonra tekrar dürtebilirsin."
            )
            return
        }

        viewModelScope.launch {
            try {
                val uid = GoogleServicesHelper.getCurrentUser(auth, appContext)?.uid
                val database = db
                if (database == null || uid.isNullOrBlank() || uid == "student_default") {
                    _uiState.value = _uiState.value.copy(
                        statusMessage = "Dürtme göndermek için internet bağlantısı gereklidir."
                    )
                    return@launch
                }

                // Check server-side 12h rate limit record if available
                val sentNudgeRef = database.collection("students").document(uid)
                    .collection("sent_nudges").document(target.studentId)
                val existingSnap = try { sentNudgeRef.get().await() } catch (_: Exception) { null }
                val remoteLastMs = existingSnap?.getLong("lastSentAtMillis") ?: 0L
                if (now - remoteLastMs < NUDGE_COOLDOWN_MS) {
                    userPrefs.recordNudgeSent(target.studentId, remoteLastMs)
                    updateEntryNudgeTimestamp(target.studentId, remoteLastMs)
                    val waitLabel = target.copy(lastNudgeSentMillis = remoteLastMs).remainingNudgeCooldownLabel(now)
                    _uiState.value = _uiState.value.copy(
                        statusMessage = "${target.displayName} yakın zamanda dürtüldü ($waitLabel kaldı)."
                    )
                    return@launch
                }

                val myName = SyncWorker.sanitizeTurkishText(
                    _uiState.value.myEntry?.rawName?.ifBlank { userPrefs.studentName ?: "Arkadaşın" }
                        ?: "Arkadaşın"
                )
                val nudgeDocId = "${uid}_${target.studentId}_${now / NUDGE_COOLDOWN_MS}"
                val nudgePayload = hashMapOf<String, Any?>(
                    "id" to nudgeDocId,
                    "senderId" to uid,
                    "senderName" to myName,
                    "receiverId" to target.studentId,
                    "receiverName" to target.rawName.ifBlank { target.displayName },
                    "groupId" to (_uiState.value.selectedGroupCode ?: GLOBAL_LEAGUE_BUCKET),
                    "message" to "🦈 $myName seni masaya çağırıyor! Kronometreni başlat ve odak yarışında geri kalma!",
                    "createdAt" to FieldValue.serverTimestamp(),
                    "createdAtMillis" to now
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
                    statusMessage = "🦈 ${target.displayName} dürtüldü! Bildirim gönderildi."
                )
            } catch (e: Exception) {
                Log.e("SharkLeagueVM", "sendNudge error", e)
                _uiState.value = _uiState.value.copy(
                    statusMessage = "Dürtme gönderilemedi: ${e.localizedMessage ?: "Bağlantıyı kontrol edin"}"
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
        avatarPresetId: String = AvatarHelper.getSavedAvatarPresetId(appContext)
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
            avatarPresetId = effectivePresetId
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
        avatarPresetId: String = ""
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
            database.collection("students").document(uid)
                .set(
                    mapOf(
                        "id" to uid,
                        "name" to name,
                        "friendCode" to friendCode,
                        "weeklyFocusSeconds" to weeklyFocusSeconds,
                        "streakDays" to streakDays,
                        "lastActiveAt" to FieldValue.serverTimestamp()
                    ),
                    SetOptions.merge()
                )
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
                        val rawName = SyncWorker.sanitizeTurkishText(doc.getString("displayName") ?: "Öğrenci")
                        val streak = if (isMe) maxOf(myEntry.streakDays, doc.getLong("streakDays")?.toInt() ?: 0)
                        else (doc.getLong("streakDays")?.toInt() ?: 0)
                        val fCode = doc.getString("friendCode") ?: ""

                        val studentAvatarPair = if (isMe) {
                            myEntry.avatarBase64 to myEntry.avatarPresetId
                        } else {
                            val studentSnap = runCatching {
                                database.collection("students").document(id).get().await()
                            }.getOrNull()
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

                        LeagueEntry(
                            studentId = id,
                            displayName = formatLeaderboardCallSign(rawName, idx + 1, isMe, showFullForFriends = false),
                            rawName = rawName,
                            friendCode = fCode,
                            weeklyFocusSeconds = secs,
                            dailyAverageMinutes = ((secs / 60L) / 7L).toInt(),
                            streakDays = streak,
                            tier = secs.toTier(),
                            isCurrentUser = isMe,
                            lastNudgeSentMillis = userPrefs.getLastNudgeMillis(id),
                            avatarBase64 = studentAvatarPair.first,
                            avatarPresetId = studentAvatarPair.second
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
                            entry.rawName,
                            idx + 1,
                            entry.isCurrentUser,
                            showFullForFriends = false
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
                // First check GLOBAL_INDIVIDUAL weeklyLeague or students/{memberId}
                val studentSnap = database.collection("students").document(memberId).get().await()
                val globalLeagueSnap = try {
                    database.collection("institutions").document(GLOBAL_LEAGUE_BUCKET)
                        .collection("weeklyLeague").document(memberId).get().await()
                } catch (_: Exception) {
                    null
                }

                val rawName = SyncWorker.sanitizeTurkishText(
                    studentSnap.getString("name")
                        ?: globalLeagueSnap?.getString("displayName")
                        ?: "Arkadaş"
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
                        avatarPresetId = avatarPresetId
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
     * In the Global League, non-current users get privacy-friendly call-signs (e.g., "A.K. #3"),
     * whereas in Friends & Study Groups (`showFullForFriends = true`), full names are displayed.
     */
    private fun formatLeaderboardCallSign(
        name: String,
        rank: Int,
        isCurrentUser: Boolean,
        showFullForFriends: Boolean
    ): String {
        val clean = SyncWorker.sanitizeTurkishText(name.ifBlank { "Öğrenci" })
        if (isCurrentUser) return "Sen ($clean)"
        if (showFullForFriends) return clean
        val initials = clean.split(" ")
            .filter { it.isNotBlank() }
            .take(2)
            .mapNotNull { it.firstOrNull()?.uppercase() }
            .joinToString(".")
        return if (initials.isNotBlank()) "$initials. #$rank" else "Shark #$rank"
    }
}
