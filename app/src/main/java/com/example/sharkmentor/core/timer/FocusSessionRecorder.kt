package com.example.sharkmentor.core.timer

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.sharkmentor.core.util.GoogleServicesHelper
import com.example.sharkmentor.data.local.SharkMentorDatabase
import com.example.sharkmentor.data.local.entity.FocusSessionEntity
import com.example.sharkmentor.data.local.preferences.UserPreferences
import com.example.sharkmentor.service.sync.SyncWorker
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Single-row checkpoint & cloud recording engine for active focus sessions (especially `STOPWATCH`)
 * and external study tools (e.g., PACE, KEEP).
 *
 * Guarantees:
 * 1. Generates a unique [activeSessionId] (UUID) at session start and checkpoints state in SharedPreferences.
 * 2. Every **20 minutes** ([LOCAL_CHECKPOINT_INTERVAL_MS] = 1,200,000 ms), upserts the accumulated duration
 *    into Room via `SessionDao.upsertSession` on the **exact same row** (`isSynced = false`), never opening
 *    duplicate rows for the same session.
 * 3. Every **60 minutes** ([CLOUD_SYNC_INTERVAL_MS] = 3,600,000 ms), attempts to sync the session to
 *    Firestore (`students/{uid}/sessions/{uuid}` & `users/{uid}/sessions/{uuid}` & `students/{uid}/focus_sessions/{uuid}`).
 *    If offline, leaves `isSynced = false` locally so [SyncWorker] pushes automatically when connected.
 * 4. On service stop, reset, or crash recovery on restart, finalizes the same session row without
 *    double-counting (`lastCloudSyncedSeconds` tracks incremental `totalFocusSeconds` deltas).
 */
object FocusSessionRecorder {

    private const val TAG = "FocusSessionRecorder"
    private const val PREFS_NAME = "shark_focus_session_checkpoint_prefs"

    /** 20 minutes in milliseconds (1,200,000 ms) for local Room single-row upsert. */
    const val LOCAL_CHECKPOINT_INTERVAL_MS: Long = 20L * 60L * 1_000L

    /** 60 minutes in milliseconds (3,600,000 ms) for cloud Firestore sync. */
    const val CLOUD_SYNC_INTERVAL_MS: Long = 60L * 60L * 1_000L

    /** Minimum duration (60 seconds) required to persist a session that hasn't hit a 20-min checkpoint. */
    const val MIN_RECORDABLE_DURATION_MS: Long = 60_000L

    /** Lightweight SharedPreferences checkpoint flush interval (15 seconds) for crash resilience. */
    private const val PREFS_HEARTBEAT_INTERVAL_MS: Long = 15_000L

    private const val KEY_ACTIVE_SESSION_UUID = "active_session_uuid"
    private const val KEY_ACTIVE_ROW_ID = "active_row_id"
    private const val KEY_MODE_NAME = "mode_name"
    private const val KEY_SUBJECT_NAME = "subject_name"
    private const val KEY_STARTED_AT_MILLIS = "started_at_millis"
    private const val KEY_LAST_KNOWN_ELAPSED_MS = "last_known_elapsed_ms"
    private const val KEY_LAST_LOCAL_SAVE_ELAPSED_MS = "last_local_save_elapsed_ms"
    private const val KEY_LAST_CLOUD_SYNC_ELAPSED_MS = "last_cloud_sync_elapsed_ms"
    private const val KEY_LAST_CLOUD_SYNCED_SECONDS = "last_cloud_synced_seconds"
    private const val KEY_PHONE_LIFT_COUNT = "phone_lift_count"
    private const val KEY_SCREEN_ON_MILLIS = "screen_on_millis"
    private const val KEY_IS_FINALIZED = "is_finalized"

    private val recorderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    @Volatile
    private var activeSessionId: String? = null

    @Volatile
    private var activeRowId: Long = 0L

    @Volatile
    private var lastPrefsHeartbeatElapsedMs: Long = 0L

    @Volatile
    private var lastLocalSaveElapsedMs: Long = 0L

    @Volatile
    private var lastCloudSyncElapsedMs: Long = 0L

    @Volatile
    private var lastCloudSyncedSeconds: Long = 0L

    @Volatile
    private var isFinalized: Boolean = true

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getActiveSessionId(): String? = activeSessionId

    /**
     * Recovers and finalizes any orphaned session left in SharedPreferences after an unexpected
     * process death or crash, ensuring the same Room row is updated without double-counting.
     */
    fun recoverOrphanedSessionIfNeeded(context: Context, isTimerCurrentlyActive: Boolean) {
        if (isTimerCurrentlyActive) return
        val appContext = context.applicationContext
        recorderScope.launch {
            mutex.withLock {
                val sp = prefs(appContext)
                val wasFinalized = sp.getBoolean(KEY_IS_FINALIZED, true)
                val orphanUuid = sp.getString(KEY_ACTIVE_SESSION_UUID, null)
                if (wasFinalized || orphanUuid.isNullOrBlank()) return@withLock

                val orphanRowId = sp.getLong(KEY_ACTIVE_ROW_ID, 0L)
                val orphanElapsedMs = sp.getLong(KEY_LAST_KNOWN_ELAPSED_MS, 0L)
                val orphanMode = sp.getString(KEY_MODE_NAME, TimerMode.STOPWATCH.displayName)
                    ?: TimerMode.STOPWATCH.displayName
                val orphanSubject = sp.getString(KEY_SUBJECT_NAME, "Genel") ?: "Genel"
                val orphanLifts = sp.getInt(KEY_PHONE_LIFT_COUNT, 0)
                val orphanScreenOnMs = sp.getLong(KEY_SCREEN_ON_MILLIS, 0L)
                val orphanSyncedSec = sp.getLong(KEY_LAST_CLOUD_SYNCED_SECONDS, 0L)

                Log.w(
                    TAG,
                    "Recovering orphaned focus session uuid=$orphanUuid, rowId=$orphanRowId, elapsedMs=$orphanElapsedMs"
                )

                if (orphanElapsedMs >= MIN_RECORDABLE_DURATION_MS || orphanRowId > 0L) {
                    val savedRow = upsertSingleSessionRowInternal(
                        context = appContext,
                        sessionUuid = orphanUuid,
                        existingRowId = orphanRowId,
                        modeName = orphanMode,
                        durationMillis = orphanElapsedMs,
                        phoneLiftCount = orphanLifts,
                        screenOnMillis = orphanScreenOnMs,
                        markSynced = false
                    )
                    val syncedOk = syncSessionToFirestoreInternal(
                        context = appContext,
                        sessionEntity = savedRow,
                        sessionUuid = orphanUuid,
                        subjectName = orphanSubject,
                        previouslySyncedSeconds = orphanSyncedSec,
                        isFinal = true
                    )
                    if (syncedOk) {
                        SharkMentorDatabase.getDatabase(appContext)
                            .syncDao()
                            .markSessionSynced(savedRow.id, orphanUuid)
                    } else {
                        SyncWorker.enqueueImmediateSync(appContext)
                    }
                }

                clearCheckpointPrefs(sp)
            }
        }
    }

    /**
     * Starts a new focus session, generating a unique UUID and initializing the checkpoint.
     * If a previous session was still open, finalizes it first without double-counting.
     */
    fun startSession(
        context: Context,
        modeName: String,
        subjectName: String = "Genel"
    ): String {
        val appContext = context.applicationContext
        val newUuid = UUID.randomUUID().toString()
        val nowWall = System.currentTimeMillis()

        recorderScope.launch {
            mutex.withLock {
                val sp = prefs(appContext)
                // If an unfinalized session was left open, finalize it cleanly first
                val prevFinalized = sp.getBoolean(KEY_IS_FINALIZED, true)
                val prevUuid = sp.getString(KEY_ACTIVE_SESSION_UUID, null)
                val prevElapsed = sp.getLong(KEY_LAST_KNOWN_ELAPSED_MS, 0L)
                val prevRowId = sp.getLong(KEY_ACTIVE_ROW_ID, 0L)
                if (!prevFinalized && !prevUuid.isNullOrBlank() &&
                    (prevElapsed >= MIN_RECORDABLE_DURATION_MS || prevRowId > 0L)
                ) {
                    val prevMode = sp.getString(KEY_MODE_NAME, modeName) ?: modeName
                    val prevSubject = sp.getString(KEY_SUBJECT_NAME, subjectName) ?: subjectName
                    val prevLifts = sp.getInt(KEY_PHONE_LIFT_COUNT, 0)
                    val prevScreenOn = sp.getLong(KEY_SCREEN_ON_MILLIS, 0L)
                    val prevSyncedSec = sp.getLong(KEY_LAST_CLOUD_SYNCED_SECONDS, 0L)

                    val savedPrev = upsertSingleSessionRowInternal(
                        context = appContext,
                        sessionUuid = prevUuid,
                        existingRowId = prevRowId,
                        modeName = prevMode,
                        durationMillis = prevElapsed,
                        phoneLiftCount = prevLifts,
                        screenOnMillis = prevScreenOn,
                        markSynced = false
                    )
                    val syncedOk = syncSessionToFirestoreInternal(
                        context = appContext,
                        sessionEntity = savedPrev,
                        sessionUuid = prevUuid,
                        subjectName = prevSubject,
                        previouslySyncedSeconds = prevSyncedSec,
                        isFinal = true
                    )
                    if (syncedOk) {
                        SharkMentorDatabase.getDatabase(appContext)
                            .syncDao()
                            .markSessionSynced(savedPrev.id, prevUuid)
                    } else {
                        SyncWorker.enqueueImmediateSync(appContext)
                    }
                }

                activeSessionId = newUuid
                activeRowId = 0L
                lastPrefsHeartbeatElapsedMs = 0L
                lastLocalSaveElapsedMs = 0L
                lastCloudSyncElapsedMs = 0L
                lastCloudSyncedSeconds = 0L
                isFinalized = false

                sp.edit()
                    .putString(KEY_ACTIVE_SESSION_UUID, newUuid)
                    .putLong(KEY_ACTIVE_ROW_ID, 0L)
                    .putString(KEY_MODE_NAME, modeName)
                    .putString(KEY_SUBJECT_NAME, subjectName)
                    .putLong(KEY_STARTED_AT_MILLIS, nowWall)
                    .putLong(KEY_LAST_KNOWN_ELAPSED_MS, 0L)
                    .putLong(KEY_LAST_LOCAL_SAVE_ELAPSED_MS, 0L)
                    .putLong(KEY_LAST_CLOUD_SYNC_ELAPSED_MS, 0L)
                    .putLong(KEY_LAST_CLOUD_SYNCED_SECONDS, 0L)
                    .putInt(KEY_PHONE_LIFT_COUNT, 0)
                    .putLong(KEY_SCREEN_ON_MILLIS, 0L)
                    .putBoolean(KEY_IS_FINALIZED, false)
                    .apply()

                Log.d(TAG, "Started new focus session uuid=$newUuid, mode=$modeName, subject=$subjectName")
            }
        }
        return newUuid
    }

    /**
     * Called on each timer tick while focusing.
     * - Flushes lightweight crash-recovery checkpoint every 15 seconds.
     * - Upserts the single Room row every **20 minutes** (`1_200_000 ms`) with `isSynced = false`.
     * - Syncs to Firestore (`students/{uid}/sessions` & `users/{uid}/sessions`) every **60 minutes** (`3_600_000 ms`).
     */
    fun onTick(
        context: Context,
        elapsedMillis: Long,
        modeName: String,
        subjectName: String,
        phoneLiftCount: Int,
        screenOnMillis: Long
    ) {
        if (elapsedMillis <= 0L || isFinalized) return

        val shouldFlushHeartbeat =
            (elapsedMillis - lastPrefsHeartbeatElapsedMs) >= PREFS_HEARTBEAT_INTERVAL_MS
        val shouldSaveLocal20Min =
            (elapsedMillis - lastLocalSaveElapsedMs) >= LOCAL_CHECKPOINT_INTERVAL_MS
        val shouldSyncCloud60Min =
            (elapsedMillis - lastCloudSyncElapsedMs) >= CLOUD_SYNC_INTERVAL_MS

        if (!shouldFlushHeartbeat && !shouldSaveLocal20Min && !shouldSyncCloud60Min) {
            return
        }

        val appContext = context.applicationContext
        if (shouldFlushHeartbeat) {
            lastPrefsHeartbeatElapsedMs = elapsedMillis
        }
        if (shouldSaveLocal20Min) {
            lastLocalSaveElapsedMs =
                (elapsedMillis / LOCAL_CHECKPOINT_INTERVAL_MS) * LOCAL_CHECKPOINT_INTERVAL_MS
        }
        if (shouldSyncCloud60Min) {
            lastCloudSyncElapsedMs =
                (elapsedMillis / CLOUD_SYNC_INTERVAL_MS) * CLOUD_SYNC_INTERVAL_MS
        }

        recorderScope.launch {
            mutex.withLock {
                if (isFinalized) return@withLock
                val sp = prefs(appContext)
                val uuid = activeSessionId
                    ?: sp.getString(KEY_ACTIVE_SESSION_UUID, null)
                    ?: UUID.randomUUID().toString().also { activeSessionId = it }

                // 1. Always update SharedPreferences checkpoint
                sp.edit()
                    .putString(KEY_ACTIVE_SESSION_UUID, uuid)
                    .putString(KEY_MODE_NAME, modeName)
                    .putString(KEY_SUBJECT_NAME, subjectName)
                    .putLong(KEY_LAST_KNOWN_ELAPSED_MS, elapsedMillis)
                    .putInt(KEY_PHONE_LIFT_COUNT, phoneLiftCount)
                    .putLong(KEY_SCREEN_ON_MILLIS, screenOnMillis)
                    .apply()

                // 2. Every 20 minutes (or when 60-minute cloud sync triggers): Upsert the SAME Room row
                if (shouldSaveLocal20Min || shouldSyncCloud60Min) {
                    val savedEntity = upsertSingleSessionRowInternal(
                        context = appContext,
                        sessionUuid = uuid,
                        existingRowId = activeRowId.takeIf { it > 0L } ?: sp.getLong(KEY_ACTIVE_ROW_ID, 0L),
                        modeName = modeName,
                        durationMillis = elapsedMillis,
                        phoneLiftCount = phoneLiftCount,
                        screenOnMillis = screenOnMillis,
                        markSynced = false
                    )
                    activeRowId = savedEntity.id
                    sp.edit()
                        .putLong(KEY_ACTIVE_ROW_ID, savedEntity.id)
                        .putLong(KEY_LAST_LOCAL_SAVE_ELAPSED_MS, lastLocalSaveElapsedMs)
                        .apply()

                    Log.i(
                        TAG,
                        "20-min local checkpoint saved (single-row upsert): rowId=${savedEntity.id}, uuid=$uuid, durationSec=${savedEntity.durationSeconds}"
                    )

                    // 3. Every 60 minutes: Attempt Firestore sync; if offline, keep isSynced=false for SyncWorker
                    if (shouldSyncCloud60Min) {
                        val prevSyncedSec = lastCloudSyncedSeconds.takeIf { it > 0L }
                            ?: sp.getLong(KEY_LAST_CLOUD_SYNCED_SECONDS, 0L)
                        val syncedOk = syncSessionToFirestoreInternal(
                            context = appContext,
                            sessionEntity = savedEntity,
                            sessionUuid = uuid,
                            subjectName = subjectName,
                            previouslySyncedSeconds = prevSyncedSec,
                            isFinal = false
                        )
                        if (syncedOk) {
                            lastCloudSyncedSeconds = savedEntity.durationSeconds
                            sp.edit()
                                .putLong(KEY_LAST_CLOUD_SYNC_ELAPSED_MS, lastCloudSyncElapsedMs)
                                .putLong(KEY_LAST_CLOUD_SYNCED_SECONDS, savedEntity.durationSeconds)
                                .apply()
                            SharkMentorDatabase.getDatabase(appContext)
                                .syncDao()
                                .markSessionSynced(savedEntity.id, uuid)
                            Log.i(
                                TAG,
                                "60-min cloud sync succeeded: uuid=$uuid, durationSec=${savedEntity.durationSeconds}"
                            )
                        } else {
                            sp.edit()
                                .putLong(KEY_LAST_CLOUD_SYNC_ELAPSED_MS, lastCloudSyncElapsedMs)
                                .apply()
                            SyncWorker.enqueueImmediateSync(appContext)
                            Log.w(
                                TAG,
                                "60-min cloud sync deferred (offline/error); left isSynced=false for SyncWorker (uuid=$uuid)"
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Called when the user pauses the timer so the exact paused elapsed time is persisted in the checkpoint.
     */
    fun onPauseCheckpoint(
        context: Context,
        elapsedMillis: Long,
        modeName: String,
        subjectName: String,
        phoneLiftCount: Int,
        screenOnMillis: Long
    ) {
        val appContext = context.applicationContext
        recorderScope.launch {
            mutex.withLock {
                if (isFinalized) return@withLock
                val sp = prefs(appContext)
                sp.edit()
                    .putLong(KEY_LAST_KNOWN_ELAPSED_MS, elapsedMillis)
                    .putString(KEY_MODE_NAME, modeName)
                    .putString(KEY_SUBJECT_NAME, subjectName)
                    .putInt(KEY_PHONE_LIFT_COUNT, phoneLiftCount)
                    .putLong(KEY_SCREEN_ON_MILLIS, screenOnMillis)
                    .apply()
            }
        }
    }

    /**
     * Finalizes the active session upon Stop, Reset, Pomodoro/Block completion, or Service destroy.
     * Updates the exact same Room row (`activeRowId` / `cloudId = activeSessionId`) and syncs only
     * the remaining delta seconds to Firestore so double-counting never occurs.
     */
    fun finalizeSession(
        context: Context,
        elapsedMillis: Long,
        modeName: String,
        subjectName: String = "Genel",
        phoneLiftCount: Int = 0,
        screenOnMillis: Long = 0L
    ) {
        val appContext = context.applicationContext
        recorderScope.launch {
            mutex.withLock {
                val sp = prefs(appContext)
                val alreadyFinalized = isFinalized && sp.getBoolean(KEY_IS_FINALIZED, true)
                if (alreadyFinalized) {
                    return@withLock
                }

                val uuid = activeSessionId
                    ?: sp.getString(KEY_ACTIVE_SESSION_UUID, null)
                    ?: UUID.randomUUID().toString()
                val existingRowId = activeRowId.takeIf { it > 0L } ?: sp.getLong(KEY_ACTIVE_ROW_ID, 0L)
                val effectiveElapsedMs = maxOf(elapsedMillis, sp.getLong(KEY_LAST_KNOWN_ELAPSED_MS, 0L))
                val prevSyncedSec = maxOf(
                    lastCloudSyncedSeconds,
                    sp.getLong(KEY_LAST_CLOUD_SYNCED_SECONDS, 0L)
                )

                // Mark finalized immediately before async I/O to prevent any concurrent duplicate finalize
                isFinalized = true
                activeSessionId = null
                activeRowId = 0L
                lastPrefsHeartbeatElapsedMs = 0L
                lastLocalSaveElapsedMs = 0L
                lastCloudSyncElapsedMs = 0L
                lastCloudSyncedSeconds = 0L
                clearCheckpointPrefs(sp)

                if (effectiveElapsedMs < MIN_RECORDABLE_DURATION_MS && existingRowId <= 0L) {
                    Log.d(
                        TAG,
                        "Session shorter than 60s (${effectiveElapsedMs}ms) with no checkpoint row; skipping save."
                    )
                    return@withLock
                }

                val finalEntity = upsertSingleSessionRowInternal(
                    context = appContext,
                    sessionUuid = uuid,
                    existingRowId = existingRowId,
                    modeName = modeName,
                    durationMillis = effectiveElapsedMs,
                    phoneLiftCount = phoneLiftCount,
                    screenOnMillis = screenOnMillis,
                    markSynced = false
                )

                Log.i(
                    TAG,
                    "Finalized session in Room (single-row upsert): rowId=${finalEntity.id}, uuid=$uuid, totalSec=${finalEntity.durationSeconds}"
                )

                val syncedOk = syncSessionToFirestoreInternal(
                    context = appContext,
                    sessionEntity = finalEntity,
                    sessionUuid = uuid,
                    subjectName = subjectName,
                    previouslySyncedSeconds = prevSyncedSec,
                    isFinal = true
                )

                if (syncedOk) {
                    SharkMentorDatabase.getDatabase(appContext)
                        .syncDao()
                        .markSessionSynced(finalEntity.id, uuid)
                } else {
                    SyncWorker.enqueueImmediateSync(appContext)
                }
            }
        }
    }

    /**
     * Public helper for external study tools (e.g. PACE, KEEP, PDF Reader, Flashcards) to record
     * focus duration into Room (`focus_sessions`) and Firestore (`students/{uid}/sessions` & `users/{uid}/sessions`)
     * without double-counting.
     *
     * Passing the same [sessionUuid] across periodic updates from an external tool will upsert the same
     * Room row and Firestore document.
     */
    suspend fun recordExternalToolSession(
        context: Context,
        toolName: String,
        durationSeconds: Long,
        subjectName: String = "Genel",
        sessionUuid: String = UUID.randomUUID().toString(),
        previouslySyncedSeconds: Long = 0L,
        isFinal: Boolean = true
    ): Long = withContext(Dispatchers.IO) {
        if (durationSeconds <= 0L) return@withContext 0L
        val appContext = context.applicationContext
        mutex.withLock {
            val sessionDao = SharkMentorDatabase.getDatabase(appContext).sessionDao()
            val existing = sessionDao.getSessionByCloudId(sessionUuid)
            val normalizedModeName = if (toolName.isBlank()) "Harici Araç" else toolName.trim()
            val savedEntity = upsertSingleSessionRowInternal(
                context = appContext,
                sessionUuid = sessionUuid,
                existingRowId = existing?.id ?: 0L,
                modeName = normalizedModeName,
                durationMillis = durationSeconds * 1_000L,
                phoneLiftCount = existing?.phoneLiftCount ?: 0,
                screenOnMillis = ((existing?.screenOnMinutes ?: 0) * 60_000L),
                markSynced = false
            )
            val syncedOk = syncSessionToFirestoreInternal(
                context = appContext,
                sessionEntity = savedEntity,
                sessionUuid = sessionUuid,
                subjectName = subjectName,
                previouslySyncedSeconds = previouslySyncedSeconds,
                isFinal = isFinal
            )
            if (syncedOk) {
                SharkMentorDatabase.getDatabase(appContext)
                    .syncDao()
                    .markSessionSynced(savedEntity.id, sessionUuid)
            } else {
                SyncWorker.enqueueImmediateSync(appContext)
            }
            savedEntity.id
        }
    }

    /**
     * Fire-and-forget convenience wrapper for external tools (PACE / KEEP) calling from UI or callbacks.
     */
    fun recordExternalToolSessionAsync(
        context: Context,
        toolName: String,
        durationSeconds: Long,
        subjectName: String = "Genel",
        sessionUuid: String = UUID.randomUUID().toString()
    ) {
        val appContext = context.applicationContext
        recorderScope.launch {
            recordExternalToolSession(
                context = appContext,
                toolName = toolName,
                durationSeconds = durationSeconds,
                subjectName = subjectName,
                sessionUuid = sessionUuid,
                previouslySyncedSeconds = 0L,
                isFinal = true
            )
        }
    }

    // ── Internal Single-Row Room Upsert & Firestore Sync ─────────────────────

    private suspend fun upsertSingleSessionRowInternal(
        context: Context,
        sessionUuid: String,
        existingRowId: Long,
        modeName: String,
        durationMillis: Long,
        phoneLiftCount: Int,
        screenOnMillis: Long,
        markSynced: Boolean
    ): FocusSessionEntity {
        val durationSeconds = (durationMillis / 1_000L).coerceAtLeast(1L)
        val screenOnMinutes = (screenOnMillis / 60_000L).toInt()
        val afkScore = (10.0f - (phoneLiftCount * 2.0f)).coerceAtLeast(0.0f)
        val now = System.currentTimeMillis()
        val institutionId = UserPreferences.getInstance(context).institutionId

        val db = SharkMentorDatabase.getDatabase(context)
        val sessionDao = db.sessionDao()

        val currentEntity = when {
            existingRowId > 0L -> sessionDao.getSessionById(existingRowId)
            else -> sessionDao.getSessionByCloudId(sessionUuid)
        }

        val entityToSave = FocusSessionEntity(
            id = currentEntity?.id ?: existingRowId.coerceAtLeast(0L),
            modeName = modeName,
            durationSeconds = maxOf(durationSeconds, currentEntity?.durationSeconds ?: 0L),
            completedAt = now,
            afkScore = afkScore,
            phoneLiftCount = phoneLiftCount,
            screenOnMinutes = screenOnMinutes,
            isSynced = markSynced,
            lastModifiedMillis = now,
            cloudId = sessionUuid,
            institutionId = institutionId,
            assignedCoachId = currentEntity?.assignedCoachId
        )

        val rowId = sessionDao.upsertSession(entityToSave)
        val finalRowId = if (entityToSave.id > 0L) entityToSave.id else rowId
        return entityToSave.copy(id = finalRowId)
    }

    /**
     * Syncs a session document to Firestore using [sessionUuid] as the deterministic document ID
     * across `students/{uid}/sessions/{uuid}`, `users/{uid}/sessions/{uuid}`, and
     * `students/{uid}/focus_sessions/{uuid}` so 60-minute checkpoints and finalization update the
     * exact same cloud document without duplication.
     */
    private suspend fun syncSessionToFirestoreInternal(
        context: Context,
        sessionEntity: FocusSessionEntity,
        sessionUuid: String,
        subjectName: String,
        previouslySyncedSeconds: Long,
        isFinal: Boolean
    ): Boolean {
        return try {
            val currentUserId = GoogleServicesHelper.getCurrentUser(context = context)?.uid
            if (currentUserId.isNullOrBlank() || currentUserId == "student_default") {
                return false
            }
            val db = GoogleServicesHelper.getFirebaseFirestore(context) ?: return false
            val userPrefs = UserPreferences.getInstance(context)
            val instId = sessionEntity.institutionId?.takeIf { it.isNotBlank() }
                ?: userPrefs.institutionId?.takeIf { it.isNotBlank() }
                ?: "INDIVIDUAL"
            val studentName = SyncWorker.sanitizeTurkishText(userPrefs.studentName ?: "Öğrenci")

            val durationSec = sessionEntity.durationSeconds
            val minutes = durationSec / 60L
            val seconds = durationSec % 60L
            val formattedDuration = if (minutes > 0L) "${minutes} dk ${seconds} sn" else "${seconds} sn"

            val sessionData = hashMapOf<String, Any?>(
                "id" to sessionUuid,
                "cloudId" to sessionUuid,
                "localId" to sessionEntity.id,
                "studentId" to currentUserId,
                "studentName" to studentName,
                "mode" to sessionEntity.modeName,
                "modeName" to sessionEntity.modeName,
                "subject" to subjectName,
                "durationSeconds" to durationSec,
                "durationMinutes" to minutes,
                "durationFormatted" to formattedDuration,
                "afkScore" to sessionEntity.afkScore,
                "phoneLiftCount" to sessionEntity.phoneLiftCount,
                "screenOnMinutes" to sessionEntity.screenOnMinutes,
                "institutionId" to instId,
                "isIndividual" to (instId == "INDIVIDUAL"),
                "isFinalized" to isFinal,
                "isDeleted" to false,
                "timestamp" to sessionEntity.completedAt,
                "completedAt" to FieldValue.serverTimestamp(),
                "lastModifiedMillis" to sessionEntity.lastModifiedMillis,
                "syncedAt" to System.currentTimeMillis()
            )

            // 1. Write to students/{uid}/sessions/{sessionUuid}
            db.collection("students").document(currentUserId)
                .collection("sessions").document(sessionUuid)
                .set(sessionData, SetOptions.merge())
                .await()

            // 2. Write to users/{uid}/sessions/{sessionUuid}
            try {
                db.collection("users").document(currentUserId)
                    .collection("sessions").document(sessionUuid)
                    .set(sessionData, SetOptions.merge())
                    .await()
            } catch (e: Exception) {
                Log.w(TAG, "users/$currentUserId/sessions write warning: ${e.message}")
            }

            // 3. Write to students/{uid}/focus_sessions/{sessionUuid} for backward compatibility with web coach panel
            try {
                db.collection("students").document(currentUserId)
                    .collection("focus_sessions").document(sessionUuid)
                    .set(sessionData, SetOptions.merge())
                    .await()
            } catch (e: Exception) {
                Log.w(TAG, "students/$currentUserId/focus_sessions write warning: ${e.message}")
            }

            // 4. Increment totalFocusSeconds only by the unsynced delta to prevent double-counting
            val deltaSeconds = (durationSec - previouslySyncedSeconds).coerceAtLeast(0L)
            val studentUpdate = hashMapOf<String, Any>(
                "id" to currentUserId,
                "name" to studentName,
                "lastActiveAt" to FieldValue.serverTimestamp()
            )
            if (deltaSeconds > 0L) {
                studentUpdate["totalFocusSeconds"] = FieldValue.increment(deltaSeconds)
            }
            if (instId != "INDIVIDUAL") {
                studentUpdate["coachCode"] = instId
            }
            db.collection("students").document(currentUserId)
                .set(studentUpdate, SetOptions.merge())
                .await()

            true
        } catch (e: Throwable) {
            Log.w(TAG, "Firestore session sync failed (offline or network issue): ${e.message}")
            false
        }
    }

    private fun clearCheckpointPrefs(sp: SharedPreferences) {
        sp.edit()
            .putBoolean(KEY_IS_FINALIZED, true)
            .remove(KEY_ACTIVE_SESSION_UUID)
            .remove(KEY_ACTIVE_ROW_ID)
            .remove(KEY_LAST_KNOWN_ELAPSED_MS)
            .remove(KEY_LAST_LOCAL_SAVE_ELAPSED_MS)
            .remove(KEY_LAST_CLOUD_SYNC_ELAPSED_MS)
            .remove(KEY_LAST_CLOUD_SYNCED_SECONDS)
            .apply()
    }
}
