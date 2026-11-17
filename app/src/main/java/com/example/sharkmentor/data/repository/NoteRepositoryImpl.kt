package com.example.sharkmentor.data.repository

import android.content.Context
import android.util.Log
import com.example.sharkmentor.core.util.GoogleServicesHelper
import com.example.sharkmentor.data.local.dao.NoteDao
import com.example.sharkmentor.data.local.entity.NoteEntity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NoteRepositoryImpl(
    private val noteDao: NoteDao,
    private val context: Context
) : NoteRepository {

    companion object {
        private const val TAG = "NoteRepositoryImpl"
    }

    private val firestore by lazy { GoogleServicesHelper.getFirebaseFirestore(context) }
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var notesListenerRegistration: ListenerRegistration? = null

    private val currentUid: String?
        get() = try {
            FirebaseAuth.getInstance().currentUser?.uid
                ?: GoogleServicesHelper.getFirebaseAuth(context)?.currentUser?.uid
        } catch (e: Exception) {
            null
        }

    private fun attachRemoteListenerIfAuthenticated() {
        val currentUser = try {
            FirebaseAuth.getInstance().currentUser
                ?: GoogleServicesHelper.getFirebaseAuth(context)?.currentUser
        } catch (e: Exception) {
            null
        }
        if (currentUser == null) {
            // Operate strictly in offline/Room-only mode when unauthenticated
            notesListenerRegistration?.remove()
            notesListenerRegistration = null
            return
        }

        if (notesListenerRegistration != null) return
        val uid = currentUser.uid
        val fs = firestore ?: return

        try {
            notesListenerRegistration = fs.collection("users")
                .document(uid)
                .collection("notes")
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        if (error.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                            notesListenerRegistration?.remove()
                            notesListenerRegistration = null
                            return@addSnapshotListener
                        }
                        Log.w(TAG, "Firestore notes listener error (operating offline): ${error.message}")
                        return@addSnapshotListener
                    }
                    if (snapshot == null) return@addSnapshotListener
                    repositoryScope.launch {
                        snapshot.documents.forEach { doc ->
                            runCatching {
                                val entity = NoteEntity(
                                    id = doc.id,
                                    title = doc.getString("title") ?: "",
                                    content = doc.getString("content") ?: "",
                                    colorHex = doc.getString("colorHex") ?: "#F8FAFC",
                                    isPinned = doc.getBoolean("isPinned") ?: doc.getBoolean("pinned") ?: false,
                                    createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis(),
                                    updatedAt = doc.getLong("updatedAt") ?: System.currentTimeMillis(),
                                    isSynced = true
                                )
                                noteDao.upsertNote(entity)
                            }.onFailure { e ->
                                Log.w(TAG, "Failed to parse remote note ${doc.id}: ${e.message}")
                            }
                        }
                    }
                }
        } catch (e: Exception) {
            Log.w(TAG, "Could not attach Firestore notes listener: ${e.message}")
        }
    }

    override fun getAllNotesFlow(): Flow<List<NoteEntity>> {
        return noteDao.getAllNotesFlow()
            .onStart { attachRemoteListenerIfAuthenticated() }
            .catch { e ->
                Log.e(TAG, "Error observing notes from Room: ${e.message}", e)
                emit(emptyList())
            }
    }

    override suspend fun getNoteById(id: String): NoteEntity? {
        return withContext(Dispatchers.IO) {
            runCatching { noteDao.getNoteById(id) }.getOrNull()
        }
    }

    override suspend fun insertOrUpdateNote(note: NoteEntity) {
        withContext(Dispatchers.IO) {
            val uid = currentUid
            val updatedNote = note.copy(
                updatedAt = System.currentTimeMillis(),
                isSynced = uid != null
            )
            noteDao.upsertNote(updatedNote)

            if (FirebaseAuth.getInstance().currentUser == null || uid == null) {
                return@withContext
            }

            try {
                val noteMap = mapOf(
                    "id" to updatedNote.id,
                    "title" to updatedNote.title,
                    "content" to updatedNote.content,
                    "colorHex" to updatedNote.colorHex,
                    "isPinned" to updatedNote.isPinned,
                    "pinned" to updatedNote.isPinned,
                    "createdAt" to updatedNote.createdAt,
                    "updatedAt" to updatedNote.updatedAt,
                    "isSynced" to true
                )
                firestore?.collection("users")?.document(uid)
                    ?.collection("notes")?.document(updatedNote.id)
                    ?.set(noteMap)
                    ?.addOnFailureListener { e ->
                        Log.w(TAG, "Firestore note sync failed (saved locally in Room): ${e.message}")
                    }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to sync note to Firestore: ${e.message}")
            }
        }
    }

    override suspend fun saveNote(note: NoteEntity) {
        insertOrUpdateNote(note)
    }

    override suspend fun deleteNote(noteId: String) {
        withContext(Dispatchers.IO) {
            noteDao.deleteNoteById(noteId)

            val uid = currentUid
            if (FirebaseAuth.getInstance().currentUser == null || uid == null) {
                return@withContext
            }

            try {
                firestore?.collection("users")?.document(uid)
                    ?.collection("notes")?.document(noteId)
                    ?.delete()
                    ?.addOnFailureListener { e ->
                        Log.w(TAG, "Firestore note delete failed: ${e.message}")
                    }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete note from Firestore: ${e.message}")
            }
        }
    }
}
