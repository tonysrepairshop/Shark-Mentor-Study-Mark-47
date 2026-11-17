package com.example.sharkmentor.data.repository

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StudentSyncRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth
) {
    companion object {
        private const val TAG = "StudentSyncRepository"
    }

    private val currentUid: String?
        get() = try {
            FirebaseAuth.getInstance().currentUser?.uid ?: auth.currentUser?.uid
        } catch (e: Exception) {
            null
        }

    // 1. CANLI DERS PROGRAMI VE AKTİF PLAN DİNLEYİCİSİ
    fun observeStudentProfile(): Flow<Map<String, Any>?> = callbackFlow {
        val uid = currentUid
        if (FirebaseAuth.getInstance().currentUser == null || uid == null) {
            trySend(null)
            close()
            return@callbackFlow
        }

        val listener: ListenerRegistration? = runCatching {
            firestore.collection("students").document(uid)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w(TAG, "observeStudentProfile listener error: ${error.message}")
                        trySend(null)
                        close()
                        return@addSnapshotListener
                    }
                    if (snapshot != null && snapshot.exists()) {
                        trySend(snapshot.data)
                    } else {
                        trySend(null)
                    }
                }
        }.onFailure { e ->
            Log.w(TAG, "Failed to attach observeStudentProfile listener: ${e.message}")
            trySend(null)
            close()
        }.getOrNull()

        awaitClose { listener?.remove() }
    }.catch { e ->
        Log.w(TAG, "observeStudentProfile flow catch: ${e.message}")
        emit(null)
    }

    // 2. CANLI HEDEFLER DİNLEYİCİSİ (Bildirim geldiğinde ekran anında güncellenir)
    fun observeGoals(): Flow<List<Map<String, Any>>> = callbackFlow {
        val uid = currentUid
        if (FirebaseAuth.getInstance().currentUser == null || uid == null) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        val listener: ListenerRegistration? = runCatching {
            firestore.collection("students").document(uid)
                .collection("goals")
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w(TAG, "observeGoals listener error: ${error.message}")
                        trySend(emptyList())
                        close()
                        return@addSnapshotListener
                    }
                    val goals = snapshot?.documents?.mapNotNull { it.data } ?: emptyList()
                    trySend(goals)
                }
        }.onFailure { e ->
            Log.w(TAG, "Failed to attach observeGoals listener: ${e.message}")
            trySend(emptyList())
            close()
        }.getOrNull()

        awaitClose { listener?.remove() }
    }.catch { e ->
        Log.w(TAG, "observeGoals flow catch: ${e.message}")
        emit(emptyList())
    }

    // 3. CANLI PLANLAR & GÖREVLER DİNLEYİCİSİ
    fun observePlans(): Flow<List<Map<String, Any>>> = callbackFlow {
        val uid = currentUid
        if (FirebaseAuth.getInstance().currentUser == null || uid == null) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        val listener: ListenerRegistration? = runCatching {
            firestore.collection("students").document(uid)
                .collection("plans")
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w(TAG, "observePlans listener error: ${error.message}")
                        trySend(emptyList())
                        close()
                        return@addSnapshotListener
                    }
                    val plans = snapshot?.documents?.mapNotNull { it.data } ?: emptyList()
                    trySend(plans)
                }
        }.onFailure { e ->
            Log.w(TAG, "Failed to attach observePlans listener: ${e.message}")
            trySend(emptyList())
            close()
        }.getOrNull()

        awaitClose { listener?.remove() }
    }.catch { e ->
        Log.w(TAG, "observePlans flow catch: ${e.message}")
        emit(emptyList())
    }

    // 4. CANLI KONU HAKİMİYETİ DİNLEYİCİSİ
    fun observeTopicMastery(): Flow<List<Map<String, Any>>> = callbackFlow {
        val uid = currentUid
        if (FirebaseAuth.getInstance().currentUser == null || uid == null) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        val listener: ListenerRegistration? = runCatching {
            firestore.collection("students").document(uid)
                .collection("topic_mastery")
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w(TAG, "observeTopicMastery listener error: ${error.message}")
                        trySend(emptyList())
                        close()
                        return@addSnapshotListener
                    }
                    val mastery = snapshot?.documents?.mapNotNull { it.data } ?: emptyList()
                    trySend(mastery)
                }
        }.onFailure { e ->
            Log.w(TAG, "Failed to attach observeTopicMastery listener: ${e.message}")
            trySend(emptyList())
            close()
        }.getOrNull()

        awaitClose { listener?.remove() }
    }.catch { e ->
        Log.w(TAG, "observeTopicMastery flow catch: ${e.message}")
        emit(emptyList())
    }
}
