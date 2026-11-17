package com.example.sharkmentor.ui.screens.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sharkmentor.data.local.entity.NoteEntity
import com.example.sharkmentor.data.repository.NoteRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class NotesViewModel(
    private val repository: NoteRepository
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    val notes: StateFlow<List<NoteEntity>> = combine(
        repository.getAllNotesFlow(),
        _searchQuery
    ) { allNotes, query ->
        if (query.isBlank()) {
            allNotes
        } else {
            allNotes.filter {
                it.title.contains(query, ignoreCase = true) ||
                it.content.contains(query, ignoreCase = true)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun saveNote(id: String?, title: String, content: String, colorHex: String, isPinned: Boolean = false) {
        viewModelScope.launch {
            val noteId = id ?: UUID.randomUUID().toString()
            val note = NoteEntity(
                id = noteId,
                title = title,
                content = content,
                colorHex = colorHex,
                isPinned = isPinned,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            repository.saveNote(note)
        }
    }

    fun togglePin(note: NoteEntity) {
        viewModelScope.launch {
            repository.saveNote(note.copy(isPinned = !note.isPinned))
        }
    }

    fun deleteNote(noteId: String) {
        viewModelScope.launch {
            repository.deleteNote(noteId)
        }
    }
}
