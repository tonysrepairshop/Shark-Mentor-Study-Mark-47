package com.example.sharkmentor.ui.screens.notes

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.sharkmentor.ImportedNotePayload
import com.example.sharkmentor.data.local.SharkMentorDatabase
import com.example.sharkmentor.data.local.entity.NoteEntity
import com.example.sharkmentor.data.repository.NoteRepositoryImpl
import com.example.sharkmentor.ui.theme.NotesPalette

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(
    onBackClick: (() -> Unit)? = null,
    showTopBar: Boolean = true,
    importedNote: ImportedNotePayload? = null,
    onImportedNoteConsumed: () -> Unit = {},
    onEditorStateChanged: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val bgColor = androidx.compose.material3.MaterialTheme.colorScheme.background
    val cardColor = androidx.compose.material3.MaterialTheme.colorScheme.surface
    val textColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurface
    val textMutedColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)

    val viewModel: NotesViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val db = SharkMentorDatabase.getDatabase(context)
                val repo = NoteRepositoryImpl(db.noteDao(), context)
                return NotesViewModel(repo) as T
            }
        }
    )

    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()

    var selectedNoteId by remember { mutableStateOf<String?>(null) }
    var noteToDelete by remember { mutableStateOf<NoteEntity?>(null) }
    var editorInitialTitle by remember { mutableStateOf("") }
    var editorInitialContent by remember { mutableStateOf("") }
    var editorIncomingUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var editorPdfPreview by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var editorPdfPagePaths by remember { mutableStateOf<List<String>>(emptyList()) }
    var showEditor by remember { mutableStateOf(false) }

    LaunchedEffect(showEditor) {
        onEditorStateChanged(showEditor)
    }

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            onEditorStateChanged(false)
        }
    }

    LaunchedEffect(importedNote) {
        if (importedNote != null) {
            selectedNoteId = null
            editorInitialTitle = importedNote.title
            editorInitialContent = importedNote.content
            editorIncomingUri = importedNote.sourceUri
            editorPdfPreview = importedNote.pdfPreviewBitmap
            editorPdfPagePaths = importedNote.pdfPageImagePaths
            showEditor = true
            onImportedNoteConsumed()
        }
    }

    if (showEditor) {
        NoteDetailScreen(
            noteId = selectedNoteId,
            initialTitle = editorInitialTitle,
            initialContent = editorInitialContent,
            incomingFileUri = editorIncomingUri,
            initialPdfPreview = editorPdfPreview,
            initialPdfPagePaths = editorPdfPagePaths,
            onDismiss = { showEditor = false },
            onSave = { _, _, _, _ ->
                showEditor = false
            }
        )
    } else {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(bgColor)
        ) {
            val screenWidth = maxWidth
            val gridColumns = when {
                screenWidth < 600.dp -> StaggeredGridCells.Fixed(2)
                screenWidth < 840.dp -> StaggeredGridCells.Fixed(3)
                screenWidth < 1150.dp -> StaggeredGridCells.Fixed(4)
                screenWidth < 1450.dp -> StaggeredGridCells.Fixed(5)
                else -> StaggeredGridCells.Fixed(6)
            }

            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (showTopBar) {
                    CenterAlignedTopAppBar(
                        title = { Text("Notlar", fontWeight = FontWeight.Bold, color = textColor) },
                        navigationIcon = {
                            if (onBackClick != null) {
                                IconButton(onClick = onBackClick) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Geri", tint = textColor)
                                }
                            }
                        },
                        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                            containerColor = bgColor
                        )
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { viewModel.updateSearchQuery(it) },
                        placeholder = { Text("Notlarda ara...", color = textMutedColor) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = textMutedColor) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 720.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NotesPalette.TextMutedDark,
                            unfocusedBorderColor = NotesPalette.TextMutedDark.copy(alpha = 0.5f),
                            focusedTextColor = textColor,
                            unfocusedTextColor = textColor,
                            cursorColor = textColor
                        )
                    )
                }

                val pinnedNotes = notes.filter { it.isPinned }
                val unpinnedNotes = notes.filter { !it.isPinned }
                val isListEmpty = pinnedNotes.isEmpty() && unpinnedNotes.isEmpty()

                if (isListEmpty) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 32.dp, vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = if (searchQuery.isNotBlank()) Icons.Default.Search else Icons.Outlined.PushPin,
                                contentDescription = null,
                                tint = textMutedColor.copy(alpha = 0.4f),
                                modifier = Modifier.size(56.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = if (searchQuery.isNotBlank()) "Eşleşen not bulunamadı" else "Henüz bir not eklenmedi",
                                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = textColor
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = if (searchQuery.isNotBlank()) "Farklı bir arama terimi deneyebilirsiniz." else "Aşağıdaki + butonuna dokunarak yeni bir not oluşturabilirsiniz.",
                                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                                color = textMutedColor,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    LazyVerticalStaggeredGrid(
                        columns = gridColumns,
                        contentPadding = PaddingValues(
                            start = if (screenWidth >= 840.dp) 24.dp else 16.dp,
                            end = if (screenWidth >= 840.dp) 24.dp else 16.dp,
                            top = 8.dp,
                            bottom = 88.dp
                        ),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalItemSpacing = 14.dp,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        if (pinnedNotes.isNotEmpty()) {
                            item(span = StaggeredGridItemSpan.FullLine) {
                                Text(
                                    "Sabitlenenler (${pinnedNotes.size})",
                                    fontWeight = FontWeight.Bold,
                                    color = textColor,
                                    modifier = Modifier.padding(bottom = 8.dp, start = 8.dp)
                                )
                            }
                            items(pinnedNotes, key = { it.id }) { note ->
                                NoteCardItem(
                                    note = note,
                                    cardColor = cardColor,
                                    textColor = textColor,
                                    textMutedColor = textMutedColor,
                                    onClick = {
                                        selectedNoteId = note.id
                                        editorInitialTitle = ""
                                        editorInitialContent = ""
                                        editorIncomingUri = null
                                        editorPdfPreview = null
                                        editorPdfPagePaths = emptyList()
                                        showEditor = true
                                    },
                                    onDelete = { noteToDelete = note },
                                    onTogglePin = { viewModel.togglePin(note) }
                                )
                            }
                        }
                        if (unpinnedNotes.isNotEmpty()) {
                            if (pinnedNotes.isNotEmpty()) {
                                item(span = StaggeredGridItemSpan.FullLine) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        "Notlar",
                                        fontWeight = FontWeight.Bold,
                                        color = textColor,
                                        modifier = Modifier.padding(bottom = 8.dp, start = 8.dp)
                                    )
                                }
                            }
                            items(unpinnedNotes, key = { it.id }) { note ->
                                NoteCardItem(
                                    note = note,
                                    cardColor = cardColor,
                                    textColor = textColor,
                                    textMutedColor = textMutedColor,
                                    onClick = {
                                        selectedNoteId = note.id
                                        editorInitialTitle = ""
                                        editorInitialContent = ""
                                        editorIncomingUri = null
                                        editorPdfPreview = null
                                        editorPdfPagePaths = emptyList()
                                        showEditor = true
                                    },
                                    onDelete = { noteToDelete = note },
                                    onTogglePin = { viewModel.togglePin(note) }
                                )
                            }
                        }
                    }
                }
            }

            FloatingActionButton(
                onClick = {
                    selectedNoteId = null
                    editorInitialTitle = ""
                    editorInitialContent = ""
                    editorIncomingUri = null
                    editorPdfPreview = null
                    editorPdfPagePaths = emptyList()
                    showEditor = true
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(24.dp),
                containerColor = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                contentColor = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Default.Add, contentDescription = "Yeni Not")
            }
        }
    }

    if (noteToDelete != null) {
        AlertDialog(
            onDismissRequest = { noteToDelete = null },
            title = {
                Text(
                    text = "Notu Sil",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text("Bu not kalıcı olarak silinecek. Onaylıyor musunuz?")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        noteToDelete?.id?.let { viewModel.deleteNote(it) }
                        noteToDelete = null
                    }
                ) {
                    Text(
                        "Sil",
                        color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { noteToDelete = null }) {
                    Text("İptal")
                }
            }
        )
    }
}

@Composable
fun NoteCardItem(
    note: NoteEntity,
    cardColor: Color,
    textColor: Color,
    textMutedColor: Color,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val accentColor = remember(note.colorHex, textColor) {
        try {
            val tintHex = note.colorHex.substringBefore("|").trim()
            if (tintHex.isBlank() || tintHex.equals("#F8FAFC", ignoreCase = true) || tintHex.equals("#FFFFFF", ignoreCase = true)) {
                textColor
            } else {
                Color(android.graphics.Color.parseColor(tintHex))
            }
        } catch (_: Exception) {
            textColor
        }
    }

    val primaryColor = androidx.compose.material3.MaterialTheme.colorScheme.primary
    val primaryContainer = androidx.compose.material3.MaterialTheme.colorScheme.primaryContainer
    val onPrimaryContainer = androidx.compose.material3.MaterialTheme.colorScheme.onPrimaryContainer

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        border = if (note.isPinned) androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFFF59E0B).copy(alpha = 0.75f)) else null
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = note.title.ifBlank { "Başlıksız Not" },
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = accentColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = onTogglePin,
                    modifier = Modifier
                        .size(30.dp)
                        .padding(start = 4.dp)
                ) {
                    if (note.isPinned) {
                        androidx.compose.material3.Surface(
                            shape = CircleShape,
                            color = Color(0xFFF59E0B).copy(alpha = 0.22f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF59E0B)),
                            modifier = Modifier.size(26.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.PushPin,
                                    contentDescription = "Sabitlendi",
                                    tint = Color(0xFFF59E0B),
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.PushPin,
                            contentDescription = "Sabitle",
                            tint = textMutedColor.copy(alpha = 0.45f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            val displayPreview = remember(note.content) {
                val withoutTags = note.content.replace(Regex("""\[PDF_PAGE:[^\]]+\]\r?\n?"""), "").trim()
                NoteRichTextHelper.toPlainText(withoutTags)
                    .lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .take(6)
                    .joinToString("\n")
                    .take(600)
                    .trim()
            }

            if (displayPreview.isNotBlank()) {
                Text(
                    text = displayPreview,
                    fontSize = 14.sp,
                    color = textColor,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 20.sp
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(
                    onClick = {
                        val cleanShareText = note.content.replace(Regex("""\[PDF_PAGE:[^\]]+\]\r?\n?"""), "").trim()
                        val shareIntent = Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_TITLE, note.title)
                            putExtra(Intent.EXTRA_TEXT, "${note.title}\n\n${NoteRichTextHelper.toPlainText(cleanShareText)}")
                            type = "text/plain"
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Notu Paylaş"))
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Paylaş",
                        tint = textMutedColor,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = {
                        val cleanCopyText = note.content.replace(Regex("""\[PDF_PAGE:[^\]]+\]\r?\n?"""), "").trim()
                        clipboardManager.setText(AnnotatedString(NoteRichTextHelper.toPlainText(cleanCopyText)))
                        Toast.makeText(context, "Kopyalandı", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Kopyala",
                        tint = textMutedColor,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Sil",
                        tint = textMutedColor,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
