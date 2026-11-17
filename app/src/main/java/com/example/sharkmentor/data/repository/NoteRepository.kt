package com.example.sharkmentor.data.repository

import com.example.sharkmentor.data.local.entity.NoteEntity
import kotlinx.coroutines.flow.Flow

interface NoteRepository {
    fun getAllNotesFlow(): Flow<List<NoteEntity>>
    suspend fun getNoteById(id: String): NoteEntity?
    suspend fun insertOrUpdateNote(note: NoteEntity)
    suspend fun saveNote(note: NoteEntity)
    suspend fun deleteNote(noteId: String)
}
