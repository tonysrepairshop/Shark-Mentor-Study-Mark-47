package com.example.sharkmentor.ui.screens.pdf

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BorderColor
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.sharkmentor.core.ai.AiProviderChoice
import com.example.sharkmentor.core.ai.DeepSeekClient
import com.example.sharkmentor.core.ai.GeminiAiFactory
import com.example.sharkmentor.core.ai.MultiProviderAiClient
import com.example.sharkmentor.core.ai.UserAiPreferences
import com.example.sharkmentor.core.data.ThemePreferences
import com.example.sharkmentor.core.theme.ThemeManager
import com.example.sharkmentor.core.util.DocumentTextExtractor
import com.example.sharkmentor.core.util.ImportedPdfFile
import com.example.sharkmentor.core.util.LazyPdfPageRenderer
import com.example.sharkmentor.core.util.PdfBitmapConverter
import com.example.sharkmentor.data.local.SharkMentorDatabase
import com.example.sharkmentor.data.local.entity.NoteEntity
import com.example.sharkmentor.data.repository.NoteRepositoryImpl
import com.example.sharkmentor.ui.screens.notes.NoteDetailScreen
import com.example.sharkmentor.ui.screens.notes.NoteDetailViewModel
import com.example.sharkmentor.ui.screens.notes.drawing.DrawingCanvasOverlay
import com.example.sharkmentor.ui.screens.notes.drawing.DrawingController
import com.example.sharkmentor.ui.screens.notes.drawing.DrawingSerializer
import com.example.sharkmentor.ui.screens.notes.drawing.DrawingStudioBar
import com.example.sharkmentor.ui.screens.notes.drawing.DrawingTool
import com.example.sharkmentor.ui.theme.SharkMentorTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.roundToInt

private val pdfNotesPersistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

enum class PdfOpenMode {
    CHOOSER,
    PDF_READER,
    EDITABLE_NOTE
}

/**
 * Independent Activity for opening & annotating PDF documents without OOM or Files-app task lockups.
 * Uses on-demand [LazyPdfPageRenderer] (visible ±1 page with LruCache) and per-page JSON annotations.
 */
class PdfReaderActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PDF_FILE_PATH = "extra_pdf_file_path"
        const val EXTRA_PDF_TITLE = "extra_pdf_title"
        const val EXTRA_SKIP_CHOOSER = "extra_skip_chooser"
        const val EXTRA_INITIAL_MODE = "extra_initial_mode"

        fun createIntent(
            context: Context,
            pdfFilePath: String,
            title: String = "PDF Belgesi",
            skipChooser: Boolean = true,
            openAsEditableNote: Boolean = false
        ): Intent {
            return Intent(context, PdfReaderActivity::class.java).apply {
                putExtra(EXTRA_PDF_FILE_PATH, pdfFilePath)
                putExtra(EXTRA_PDF_TITLE, title)
                putExtra(EXTRA_SKIP_CHOOSER, skipChooser)
                putExtra(
                    EXTRA_INITIAL_MODE,
                    if (openAsEditableNote) PdfOpenMode.EDITABLE_NOTE.name else PdfOpenMode.PDF_READER.name
                )
            }
        }
    }

    private var importedPdfState by mutableStateOf<ImportedPdfFile?>(null)
    private var isImporting by mutableStateOf(true)
    private var importError by mutableStateOf<String?>(null)
    private var activeMode by mutableStateOf(PdfOpenMode.CHOOSER)
    private var editableNoteText by mutableStateOf("")
    private var isExtractingText by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        processIncomingIntent(intent)

        setContent {
            val themePrefs = remember { ThemePreferences.initialize(this) }
            val themeMode by themePrefs.themeMode.collectAsStateWithLifecycle()
            val currentPalette by themePrefs.palette.collectAsStateWithLifecycle()
            val isZenModePref by themePrefs.isZenMode.collectAsStateWithLifecycle()
            val isZenMinimalist by ThemeManager.isZenMinimalist.collectAsStateWithLifecycle()
            val isZenMode = isZenModePref || isZenMinimalist

            val coroutineScope = rememberCoroutineScope()

            SharkMentorTheme(
                themeMode = themeMode,
                palette = currentPalette,
                isZenMode = isZenMode
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val pdfDoc = importedPdfState
                    when {
                        isImporting || isExtractingText -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                                    Text(
                                        text = if (isExtractingText) "Metin çıkarılıyor..." else "PDF yükleniyor...",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                        importError != null || pdfDoc == null -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PictureAsPdf,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(48.dp)
                                    )
                                    Text(
                                        text = importError ?: "PDF dosyası açılamadı.",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        textAlign = TextAlign.Center
                                    )
                                    Button(onClick = { finish() }) {
                                        Text("Kapat")
                                    }
                                }
                            }
                        }
                        activeMode == PdfOpenMode.CHOOSER -> {
                            PdfOpenChoiceScreen(
                                pdfFile = pdfDoc,
                                onOpenAsPdfReader = {
                                    activeMode = PdfOpenMode.PDF_READER
                                },
                                onOpenAsEditableNote = {
                                    isExtractingText = true
                                    coroutineScope.launch(Dispatchers.IO) {
                                        val cleanText = runCatching {
                                            DocumentTextExtractor.extractCleanText(
                                                context = this@PdfReaderActivity,
                                                uri = pdfDoc.localUri,
                                                displayName = pdfDoc.displayName,
                                                mimeType = "application/pdf"
                                            )
                                        }.getOrDefault("")
                                        withContext(Dispatchers.Main) {
                                            if (cleanText.isBlank()) {
                                                Toast.makeText(
                                                    this@PdfReaderActivity,
                                                    "Belgede metin bulunamadı",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                            editableNoteText = cleanText.take(30_000)
                                            isExtractingText = false
                                            activeMode = PdfOpenMode.EDITABLE_NOTE
                                        }
                                    }
                                },
                                onCancel = { finish() }
                            )
                        }
                        activeMode == PdfOpenMode.EDITABLE_NOTE -> {
                            NoteDetailScreen(
                                noteId = null,
                                initialTitle = pdfDoc.title,
                                initialContent = editableNoteText,
                                incomingFileUri = pdfDoc.localUri,
                                initialPdfPreview = null,
                                initialPdfPagePaths = emptyList(),
                                onDismiss = { finish() },
                                onSave = { _, _, _, _ ->
                                    Toast.makeText(
                                        this@PdfReaderActivity,
                                        "Not kaydedildi",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            )
                        }
                        activeMode == PdfOpenMode.PDF_READER -> {
                            PdfReaderScreen(
                                pdfFile = pdfDoc,
                                onBackClick = { finish() },
                                onSwitchToEditableNote = {
                                    isExtractingText = true
                                    coroutineScope.launch(Dispatchers.IO) {
                                        val cleanText = runCatching {
                                            DocumentTextExtractor.extractCleanText(
                                                context = this@PdfReaderActivity,
                                                uri = pdfDoc.localUri,
                                                displayName = pdfDoc.displayName,
                                                mimeType = "application/pdf"
                                            )
                                        }.getOrDefault("")
                                        withContext(Dispatchers.Main) {
                                            if (cleanText.isBlank()) {
                                                Toast.makeText(
                                                    this@PdfReaderActivity,
                                                    "Belgede metin bulunamadı",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                            editableNoteText = cleanText.take(30_000)
                                            isExtractingText = false
                                            activeMode = PdfOpenMode.EDITABLE_NOTE
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        processIncomingIntent(intent)
    }

    private fun processIncomingIntent(intent: Intent?) {
        if (intent == null) {
            isImporting = false
            importError = "Geçersiz PDF isteği."
            return
        }

        val skipChooser = intent.getBooleanExtra(EXTRA_SKIP_CHOOSER, false)
        val initialModeStr = intent.getStringExtra(EXTRA_INITIAL_MODE)
        val explicitPath = intent.getStringExtra(EXTRA_PDF_FILE_PATH)
        val explicitTitle = intent.getStringExtra(EXTRA_PDF_TITLE)

        isImporting = true
        importError = null

        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1. Check if launched internally with an already-copied local file path
                if (!explicitPath.isNullOrBlank()) {
                    val file = File(explicitPath)
                    if (file.exists() && file.length() > 0L) {
                        val count = PdfBitmapConverter.readPageCountSafely(file)
                        val title = explicitTitle?.takeIf { it.isNotBlank() }
                            ?: file.name.substringBeforeLast('.', file.name)
                        val imported = ImportedPdfFile(
                            localFile = file,
                            localUri = Uri.fromFile(file),
                            displayName = file.name,
                            title = title,
                            pageCount = count
                        )
                        val targetMode = if (skipChooser) {
                            runCatching { PdfOpenMode.valueOf(initialModeStr ?: "") }
                                .getOrDefault(PdfOpenMode.PDF_READER)
                        } else {
                            PdfOpenMode.CHOOSER
                        }

                        if (targetMode == PdfOpenMode.EDITABLE_NOTE) {
                            val extracted = runCatching {
                                DocumentTextExtractor.extractCleanText(
                                    context = this@PdfReaderActivity,
                                    uri = imported.localUri,
                                    displayName = imported.displayName,
                                    mimeType = "application/pdf"
                                )
                            }.getOrDefault("").take(30_000)
                            withContext(Dispatchers.Main) {
                                if (extracted.isBlank()) {
                                    Toast.makeText(
                                        this@PdfReaderActivity,
                                        "Belgede metin bulunamadı",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                                editableNoteText = extracted
                            }
                        }

                        withContext(Dispatchers.Main) {
                            importedPdfState = imported
                            activeMode = targetMode
                            isImporting = false
                        }
                        return@launch
                    }
                }

                // 2. External ACTION_VIEW or ACTION_SEND URI
                val incomingUri: Uri? = when (intent.action) {
                    Intent.ACTION_VIEW -> intent.data
                    Intent.ACTION_SEND -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(Intent.EXTRA_STREAM)
                        }
                    }
                    else -> intent.data
                }

                if (incomingUri == null) {
                    withContext(Dispatchers.Main) {
                        isImporting = false
                        importError = "PDF dosya bağlantısı (URI) bulunamadı."
                    }
                    return@launch
                }

                val copied = PdfBitmapConverter.copyUriToPrivatePdfsDir(this@PdfReaderActivity, incomingUri)
                withContext(Dispatchers.Main) {
                    if (copied != null && copied.pageCount > 0) {
                        importedPdfState = copied
                        activeMode = if (skipChooser) PdfOpenMode.PDF_READER else PdfOpenMode.CHOOSER
                        isImporting = false
                    } else {
                        isImporting = false
                        importError = "PDF belgesi okunamadı veya bozuk."
                    }
                }
            } catch (e: Exception) {
                Log.e("PdfReaderActivity", "Failed processing incoming PDF intent", e)
                withContext(Dispatchers.Main) {
                    isImporting = false
                    importError = "PDF yüklenirken hata oluştu: ${e.localizedMessage}"
                }
            }
        }
    }
}

/**
 * Modern selection dialog presented when opening a PDF:
 * 1. "PDF Olarak Aç" (Lazy PdfRenderer, Pinch-to-Zoom, Highlighter/Pen/Lasso Annotations, AI Assistant)
 * 2. "Not Olarak Aç (Düzenlenebilir)" (Clean text extraction via DocumentTextExtractor into Rich Note Editor)
 */
@Composable
fun PdfOpenChoiceDialog(
    title: String,
    pageCount: Int,
    onOpenAsPdfReader: () -> Unit,
    onOpenAsEditableNote: () -> Unit,
    onDismiss: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = colorScheme.surface,
            tonalElevation = 8.dp,
            shadowElevation = 12.dp,
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 420.dp)
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = colorScheme.primaryContainer,
                        modifier = Modifier.size(46.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.PictureAsPdf,
                                contentDescription = null,
                                tint = colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (pageCount > 0) {
                            Text(
                                text = "$pageCount sayfa",
                                style = MaterialTheme.typography.labelMedium,
                                color = colorScheme.onSurface.copy(alpha = 0.65f)
                            )
                        }
                    }
                }

                // Option 1: PDF Olarak Aç
                Surface(
                    onClick = onOpenAsPdfReader,
                    shape = RoundedCornerShape(16.dp),
                    color = colorScheme.primaryContainer.copy(alpha = 0.55f),
                    border = BorderStroke(1.5.dp, colorScheme.primary)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = colorScheme.primary,
                            modifier = Modifier.size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.PictureAsPdf,
                                    contentDescription = null,
                                    tint = colorScheme.onPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "PDF Olarak Aç",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = colorScheme.onSurface
                            )
                            Text(
                                text = "Yakınlaştırma, çizim ve vurgu",
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.onSurface.copy(alpha = 0.72f),
                                lineHeight = 16.sp
                            )
                        }
                    }
                }

                // Option 2: Not Olarak Aç (Düzenlenebilir)
                Surface(
                    onClick = onOpenAsEditableNote,
                    shape = RoundedCornerShape(16.dp),
                    color = colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, colorScheme.onSurface.copy(alpha = 0.18f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = colorScheme.secondaryContainer,
                            modifier = Modifier.size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = null,
                                    tint = colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Not Olarak Aç (Düzenlenebilir)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = colorScheme.onSurface
                            )
                            Text(
                                text = "Metni düzenlenebilir nota aktar",
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.onSurface.copy(alpha = 0.72f),
                                lineHeight = 16.sp
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("İptal")
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfOpenChoiceScreen(
    pdfFile: ImportedPdfFile,
    onOpenAsPdfReader: () -> Unit,
    onOpenAsEditableNote: () -> Unit,
    onCancel: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        PdfOpenChoiceDialog(
            title = pdfFile.title,
            pageCount = pdfFile.pageCount,
            onOpenAsPdfReader = onOpenAsPdfReader,
            onOpenAsEditableNote = onOpenAsEditableNote,
            onDismiss = onCancel
        )
    }
}

/**
 * Full-featured, OOM-safe PDF Reader with:
 * - Lazy on-demand [LazyPdfPageRenderer] (only visible ±1 pages in LruCache)
 * - Pinch-to-zoom (1.0x..4.0x), pan, double-tap zoom, and quick zoom controls
 * - Page indicator badge & quick scrubber slider
 * - Underline / Highlighter & full Drawing Studio per-page JSON annotations
 * - Integrated AI Note Assistant (Vision + Text analysis + Save as Note)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfReaderScreen(
    pdfFile: ImportedPdfFile,
    onBackClick: () -> Unit,
    onSwitchToEditableNote: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val colorScheme = MaterialTheme.colorScheme
    val surfaceColor = colorScheme.surface
    val onSurfaceColor = colorScheme.onSurface
    val primaryColor = colorScheme.primary
    val textMutedColor = onSurfaceColor.copy(alpha = 0.6f)

    val pdfIdentifier = pdfFile.localFile.absolutePath
    val lazyRenderer = remember(pdfIdentifier) {
        LazyPdfPageRenderer(pdfFile.localFile)
    }

    DisposableEffect(lazyRenderer) {
        onDispose {
            lazyRenderer.close()
        }
    }

    val totalPages = lazyRenderer.pageCount.coerceAtLeast(1)
    val initialPage = remember(pdfIdentifier, totalPages) {
        DrawingSerializer.loadPdfLastReadPage(context, pdfIdentifier)
            .coerceIn(0, (totalPages - 1).coerceAtLeast(0))
    }

    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialPage)

    val currentPageIndex by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val visibleItems = layoutInfo.visibleItemsInfo
            if (visibleItems.isEmpty()) {
                listState.firstVisibleItemIndex.coerceIn(0, totalPages - 1)
            } else {
                val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
                visibleItems.minByOrNull { item ->
                    kotlin.math.abs((item.offset + item.size / 2) - viewportCenter)
                }?.index?.coerceIn(0, totalPages - 1) ?: listState.firstVisibleItemIndex
            }
        }
    }

    // Persist last read page and trim LruCache around visible ±1 page
    LaunchedEffect(currentPageIndex) {
        lazyRenderer.trimAroundVisiblePage(currentPageIndex, keepRadius = 1)
        withContext(Dispatchers.IO) {
            DrawingSerializer.savePdfLastReadPage(context, pdfIdentifier, currentPageIndex)
        }
    }

    // Per-page DrawingController cache so each page has its own annotations & 60-step Undo/Redo
    val pageControllers = remember(pdfIdentifier) {
        mutableStateMapOf<Int, DrawingController>()
    }

    // Shared active tool state synchronized across pages so switching page keeps the selected pen/highlighter
    var showStudioToolbar by remember { mutableStateOf(false) }
    var globalActiveTool by remember { mutableStateOf(DrawingTool.NONE) }
    var globalPenColor by remember { mutableStateOf(Color(0xFF1E88E5)) }
    var globalStrokeWidth by remember { mutableFloatStateOf(4.5f) }
    var globalAlpha by remember { mutableFloatStateOf(1.0f) }

    fun getOrCreatePageController(pageIdx: Int): DrawingController {
        return pageControllers.getOrPut(pageIdx) {
            val loadedStrokes = DrawingSerializer.loadPdfPageAnnotations(context, pdfIdentifier, pageIdx)
            DrawingController(
                maxHistorySteps = 60,
                onStrokesChanged = { updatedStrokes ->
                    coroutineScope.launch(Dispatchers.IO) {
                        DrawingSerializer.savePdfPageAnnotations(
                            context = context,
                            pdfIdentifier = pdfIdentifier,
                            pageIndex = pageIdx,
                            strokes = updatedStrokes
                        )
                    }
                }
            ).apply {
                setInitialStrokes(loadedStrokes)
                selectTool(globalActiveTool)
                if (!globalActiveTool.isPenTool) {
                    penColor = globalPenColor
                    strokeWidth = globalStrokeWidth
                    alpha = globalAlpha
                }
            }
        }
    }

    val activePageController = getOrCreatePageController(currentPageIndex)

    // Keep global tool synced when user interacts with DrawingStudioBar
    LaunchedEffect(
        activePageController.activeTool,
        activePageController.penColor,
        activePageController.strokeWidth,
        activePageController.alpha,
        currentPageIndex
    ) {
        globalActiveTool = activePageController.activeTool
        globalPenColor = activePageController.penColor
        globalStrokeWidth = activePageController.strokeWidth
        globalAlpha = activePageController.alpha

        pageControllers.values.forEach { ctrl ->
            if (ctrl !== activePageController) {
                if (ctrl.activeTool != globalActiveTool) {
                    ctrl.selectTool(globalActiveTool)
                }
                ctrl.penColor = globalPenColor
                ctrl.strokeWidth = globalStrokeWidth
                ctrl.alpha = globalAlpha
            }
        }
    }

    // Pinch-to-Zoom & Pan state
    var zoomScale by remember { mutableFloatStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }

    var showMoreMenu by remember { mutableStateOf(false) }
    var showPageScrubber by remember { mutableStateOf(true) }

    // AI Assistant state
    var showAiSheet by remember { mutableStateOf(false) }
    var aiQueryText by remember { mutableStateOf("") }
    var aiResult by remember { mutableStateOf<String?>(null) }
    var isAiLoading by remember { mutableStateOf(false) }
    var aiError by remember { mutableStateOf<String?>(null) }
    var aiSavedAsNote by remember { mutableStateOf(false) }

    // Note persistence state for this PDF
    var isSavedToNotes by remember(pdfIdentifier) { mutableStateOf(false) }
    var isSavingToNotes by remember(pdfIdentifier) { mutableStateOf(false) }

    LaunchedEffect(pdfIdentifier) {
        val alreadySaved = withContext(Dispatchers.IO) {
            runCatching {
                val db = SharkMentorDatabase.getDatabase(context.applicationContext)
                val repo = NoteRepositoryImpl(db.noteDao(), context.applicationContext)
                val allNotes = withTimeoutOrNull(1500L) { repo.getAllNotesFlow().first() }.orEmpty()
                val pdfTag = "[PDF_FILE:$pdfIdentifier]"
                allNotes.any { it.content.contains(pdfTag) }
            }.getOrDefault(false)
        }
        isSavedToNotes = alreadySaved
    }

    fun savePdfDocumentToNotes() {
        if (isSavingToNotes) return
        isSavingToNotes = true
        val appContext = context.applicationContext
        val pdfAbsPath = pdfFile.localFile.absolutePath
        val noteTitle = pdfFile.title.ifBlank { pdfFile.displayName.substringBeforeLast('.', "PDF Belgesi") }
        val pageCountSnapshot = totalPages

        pdfNotesPersistenceScope.launch {
            val savedOk = runCatching {
                val db = SharkMentorDatabase.getDatabase(appContext)
                val repo = NoteRepositoryImpl(db.noteDao(), appContext)
                val pdfTag = "[PDF_FILE:$pdfAbsPath]"

                val existingNotes = withTimeoutOrNull(1500L) { repo.getAllNotesFlow().first() }.orEmpty()
                val existingNote = existingNotes.firstOrNull { it.content.contains(pdfTag) }

                // Do NOT recycle thumbBitmap because LruCache in LazyPdfPageRenderer owns it
                var thumbPath: String? = null
                val thumbBitmap = runCatching {
                    lazyRenderer.renderPage(
                        pageIndex = 0,
                        maxWidthPx = 720,
                        visiblePageHint = currentPageIndex
                    )
                }.getOrNull()
                if (thumbBitmap != null && !thumbBitmap.isRecycled) {
                    runCatching {
                        val thumbDir = File(appContext.filesDir, "rendered_pdf_pages").apply {
                            if (!exists()) mkdirs()
                        }
                        val docKey = DrawingSerializer.computePdfDocKey(pdfAbsPath)
                        val thumbFile = File(thumbDir, "pdf_thumb_${docKey}_p1.jpg")
                        FileOutputStream(thumbFile).use { out ->
                            thumbBitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
                            out.flush()
                        }
                        if (thumbFile.exists() && thumbFile.length() > 0L) {
                            thumbPath = thumbFile.absolutePath
                        }
                    }
                }

                val noteContent = buildString {
                    append(pdfTag)
                    if (!thumbPath.isNullOrBlank()) {
                        append("\n[PDF_PAGE:")
                        append(thumbPath)
                        append("]")
                    }
                    append("\n\n")
                    append("$pageCountSnapshot sayfa")
                }

                val now = System.currentTimeMillis()
                val entity = if (existingNote != null) {
                    val hasThumbTag = existingNote.content.contains("[PDF_PAGE:")
                    val updatedBody = when {
                        existingNote.content.isBlank() -> noteContent
                        !hasThumbTag && !thumbPath.isNullOrBlank() -> existingNote.content.replaceFirst(
                            pdfTag,
                            "$pdfTag\n[PDF_PAGE:$thumbPath]"
                        )
                        else -> existingNote.content
                    }
                    existingNote.copy(
                        title = existingNote.title.ifBlank { noteTitle },
                        content = updatedBody,
                        updatedAt = now
                    )
                } else {
                    NoteEntity(
                        id = UUID.randomUUID().toString(),
                        title = noteTitle,
                        content = noteContent,
                        colorHex = "#F8FAFC",
                        isPinned = false,
                        createdAt = now,
                        updatedAt = now
                    )
                }
                repo.insertOrUpdateNote(entity)
                true
            }.getOrElse { e ->
                Log.e("PdfReaderActivity", "Failed saving PDF note", e)
                false
            }

            withContext(Dispatchers.Main) {
                isSavingToNotes = false
                if (savedOk) {
                    isSavedToNotes = true
                    Toast.makeText(context, "Notlara kaydedildi", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Not kaydedilemedi", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun runPdfAiAnalysis(promptQuery: String) {
        isAiLoading = true
        aiError = null
        aiResult = ""
        aiSavedAsNote = false

        val mainHandler = Handler(Looper.getMainLooper())
        coroutineScope.launch(Dispatchers.IO) {
            try {
                UserAiPreferences.initialize(context.applicationContext)
                val selectedProvider = UserAiPreferences.getOrNull(context)?.getSelectedProvider()
                    ?: AiProviderChoice.AUTO

                val effectivePrompt = promptQuery.trim().ifBlank {
                    "Bu PDF sayfasını ve belgeyi detaylıca incele, ana konuları özetle ve önemli çalışma noktalarını maddeler halinde çıkar."
                }

                // 1. Try Vision analysis on the current visible PDF page first
                // NOTE: Do NOT recycle currentPageBitmap — LruCache inside LazyPdfPageRenderer owns it.
                val currentPageBitmap = lazyRenderer.renderPage(
                    pageIndex = currentPageIndex,
                    maxWidthPx = 1000,
                    visiblePageHint = currentPageIndex
                )

                val extractedText = runCatching {
                    DocumentTextExtractor.extractCleanText(
                        context = context,
                        uri = pdfFile.localUri,
                        displayName = pdfFile.displayName,
                        mimeType = "application/pdf"
                    ).take(12_000)
                }.getOrDefault("")

                var visionFailureMessage: String? = null
                if (currentPageBitmap != null && !currentPageBitmap.isRecycled) {
                    val visionSystem = NoteDetailViewModel.ISOLATED_NOTE_SYSTEM_INSTRUCTION.trim()
                    val visionPrompt = buildString {
                        if (extractedText.length >= 60) {
                            append("[BELGE METNİ (Yardımcı Bağlam)]:\n")
                            append(extractedText.take(6000))
                            append("\n\n")
                        }
                        append("[AKTİF SAYFA: ${currentPageIndex + 1} / $totalPages]\n")
                        append("[KULLANICI İSTEĞİ]: $effectivePrompt")
                    }

                    val visionResult = GeminiAiFactory.streamContentWithFailover(
                        context = context,
                        systemInstruction = visionSystem,
                        userPrompt = visionPrompt,
                        bitmaps = listOf(currentPageBitmap),
                        responseJson = false,
                        maxOutputTokens = 6144,
                        timeoutMs = 75_000L,
                        onPartialText = { partial ->
                            if (partial.isNotEmpty()) {
                                mainHandler.post {
                                    aiResult = partial
                                    isAiLoading = false
                                }
                            }
                        }
                    )

                    if (visionResult.isSuccess) {
                        val finalVisionText = visionResult.getOrThrow().text.trim()
                        if (finalVisionText.isNotBlank()) {
                            withContext(Dispatchers.Main) {
                                aiResult = finalVisionText
                                aiError = null
                                isAiLoading = false
                            }
                            return@launch
                        }
                    } else {
                        val err = visionResult.exceptionOrNull()
                        if (err is CancellationException) throw err
                        visionFailureMessage = err?.localizedMessage
                        Log.w("PdfReaderAI", "Vision analysis fallback: $visionFailureMessage")
                    }
                }

                // 2. Fallback to text analysis via DeepSeek/Kimi (if selected & configured) or Gemini 3.6 Flash
                if (extractedText.isBlank()) {
                    withContext(Dispatchers.Main) {
                        aiError = visionFailureMessage
                            ?: "PDF sayfası analiz edilemedi. Lütfen internet bağlantınızı ve API anahtarınızı kontrol edin."
                        isAiLoading = false
                    }
                    return@launch
                }

                val contextualPrompt = """
                    [PDF BAŞLIĞI]: ${pdfFile.title} (Sayfa ${currentPageIndex + 1} / $totalPages)
                    [PDF METNİ]:
                    $extractedText

                    [KULLANICI SORUSU / İSTEĞİ]:
                    $effectivePrompt
                """.trimIndent()

                val prefs = UserAiPreferences.getOrNull(context)
                val targetExternalProvider: AiProviderChoice? = when (selectedProvider) {
                    AiProviderChoice.GEMINI -> null
                    AiProviderChoice.CLAUDE ->
                        AiProviderChoice.CLAUDE.takeIf { MultiProviderAiClient.getClaudeApiKey(context).isNotBlank() }
                    AiProviderChoice.OPENAI ->
                        AiProviderChoice.OPENAI.takeIf { MultiProviderAiClient.getOpenAiApiKey(context).isNotBlank() }
                    AiProviderChoice.DEEPSEEK ->
                        AiProviderChoice.DEEPSEEK.takeIf { DeepSeekClient.getDeepSeekApiKey(context).isNotBlank() }
                    AiProviderChoice.KIMI ->
                        AiProviderChoice.KIMI.takeIf { DeepSeekClient.getKimiApiKey(context).isNotBlank() }
                    AiProviderChoice.AUTO -> {
                        val activeAuto = prefs?.getActiveProviderAndModel()?.first ?: AiProviderChoice.GEMINI
                        if (activeAuto != AiProviderChoice.GEMINI && MultiProviderAiClient.hasAnyExternalProviderKey(context)) {
                            activeAuto
                        } else {
                            null
                        }
                    }
                }

                if (targetExternalProvider != null) {
                    try {
                        val streamed = MultiProviderAiClient.streamChatCompletion(
                            provider = targetExternalProvider,
                            systemPrompt = NoteDetailViewModel.ISOLATED_NOTE_SYSTEM_INSTRUCTION.trim(),
                            userPrompt = contextualPrompt,
                            temperature = 0.2f,
                            maxTokens = 4096,
                            context = context,
                            onAccumulatedChunk = { partial ->
                                if (partial.isNotEmpty()) {
                                    mainHandler.post {
                                        aiResult = partial
                                        isAiLoading = false
                                    }
                                }
                            }
                        )
                        if (streamed.isNotBlank()) {
                            withContext(Dispatchers.Main) {
                                aiResult = streamed
                                aiError = null
                                isAiLoading = false
                            }
                            return@launch
                        }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (e: Exception) {
                        Log.w("PdfReaderAI", "${targetExternalProvider.shortLabel} fallback to Gemini: ${e.message}")
                    }
                }

                val geminiTextResult = GeminiAiFactory.streamContentWithFailover(
                    context = context,
                    systemInstruction = NoteDetailViewModel.ISOLATED_NOTE_SYSTEM_INSTRUCTION.trim(),
                    userPrompt = contextualPrompt,
                    responseJson = false,
                    maxOutputTokens = 6144,
                    timeoutMs = 75_000L,
                    onPartialText = { partial ->
                        if (partial.isNotEmpty()) {
                            mainHandler.post {
                                aiResult = partial
                                isAiLoading = false
                            }
                        }
                    }
                )

                if (geminiTextResult.isSuccess) {
                    val finalText = geminiTextResult.getOrThrow().text.trim()
                    withContext(Dispatchers.Main) {
                        if (finalText.isNotBlank()) {
                            aiResult = finalText
                            aiError = null
                        } else {
                            aiError = "AI boş yanıt döndürdü. Lütfen tekrar deneyin."
                        }
                        isAiLoading = false
                    }
                } else {
                    val err = geminiTextResult.exceptionOrNull()
                    if (err is CancellationException) throw err
                    withContext(Dispatchers.Main) {
                        aiError = err?.localizedMessage ?: visionFailureMessage ?: "AI analizi sırasında hata oluştu."
                        isAiLoading = false
                    }
                }
            } catch (e: Throwable) {
                if (e !is CancellationException) {
                    withContext(Dispatchers.Main) {
                        aiError = e.localizedMessage ?: "AI analizi sırasında hata oluştu."
                    }
                }
            } finally {
                withContext(Dispatchers.Main) {
                    isAiLoading = false
                }
            }
        }
    }

    fun saveAiResultToNotes(summaryText: String) {
        if (summaryText.isBlank()) return
        coroutineScope.launch(Dispatchers.IO) {
            runCatching {
                val db = SharkMentorDatabase.getDatabase(context.applicationContext)
                val repo = NoteRepositoryImpl(db.noteDao(), context.applicationContext)
                val note = NoteEntity(
                    id = UUID.randomUUID().toString(),
                    title = "[PDF Notu] ${pdfFile.title} (S.${currentPageIndex + 1})",
                    content = summaryText.trim(),
                    colorHex = "#F8FAFC",
                    isPinned = false,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
                repo.insertOrUpdateNote(note)
                withContext(Dispatchers.Main) {
                    aiSavedAsNote = true
                    Toast.makeText(context, "Notlara kaydedildi", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    BackHandler {
        if (activePageController.activeTool != DrawingTool.NONE) {
            activePageController.selectTool(DrawingTool.NONE)
            showStudioToolbar = false
        } else if (zoomScale > 1.05f) {
            zoomScale = 1f
            panOffset = Offset.Zero
        } else {
            onBackClick()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1E293B))
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Top App Bar
        TopAppBar(
            title = {
                Column {
                    Text(
                        text = pdfFile.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = onSurfaceColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "S.${currentPageIndex + 1}/$totalPages" +
                            if (zoomScale > 1.02f) " • %${(zoomScale * 100).roundToInt()}" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = primaryColor,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = onBackClick) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Geri",
                        tint = onSurfaceColor
                    )
                }
            },
            actions = {
                // Prominent Save PDF to Notes pill button
                Surface(
                    onClick = { savePdfDocumentToNotes() },
                    enabled = !isSavingToNotes,
                    shape = RoundedCornerShape(16.dp),
                    color = if (isSavedToNotes) primaryColor.copy(alpha = 0.16f) else primaryColor,
                    border = if (isSavedToNotes) BorderStroke(1.dp, primaryColor.copy(alpha = 0.5f)) else null,
                    modifier = Modifier.padding(end = 2.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (isSavingToNotes) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(13.dp),
                                strokeWidth = 1.8.dp,
                                color = if (isSavedToNotes) primaryColor else colorScheme.onPrimary
                            )
                        } else {
                            Icon(
                                imageVector = if (isSavedToNotes) Icons.Default.CheckCircle else Icons.Default.NoteAdd,
                                contentDescription = "Notlara Kaydet",
                                tint = if (isSavedToNotes) primaryColor else colorScheme.onPrimary,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                        Text(
                            text = if (isSavedToNotes) "Kaydedildi" else "Notlara Kaydet",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isSavedToNotes) primaryColor else colorScheme.onPrimary,
                            maxLines = 1
                        )
                    }
                }

                // Quick Highlighter toggle
                IconButton(
                    onClick = {
                        showStudioToolbar = true
                        if (activePageController.activeTool == DrawingTool.HIGHLIGHTER) {
                            activePageController.selectTool(DrawingTool.NONE)
                        } else {
                            activePageController.selectTool(DrawingTool.HIGHLIGHTER)
                        }
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.BorderColor,
                        contentDescription = "Fosforlu Vurgu",
                        tint = if (activePageController.activeTool == DrawingTool.HIGHLIGHTER) {
                            Color(0xFFFBC02D)
                        } else {
                            onSurfaceColor
                        },
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Quick Underline toggle
                IconButton(
                    onClick = {
                        showStudioToolbar = true
                        if (activePageController.activeTool == DrawingTool.UNDERLINE_MARKER) {
                            activePageController.selectTool(DrawingTool.NONE)
                        } else {
                            activePageController.selectTool(DrawingTool.UNDERLINE_MARKER)
                        }
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.FormatUnderlined,
                        contentDescription = "Alt Çizgi",
                        tint = if (activePageController.activeTool == DrawingTool.UNDERLINE_MARKER) {
                            primaryColor
                        } else {
                            onSurfaceColor
                        },
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Full Drawing & Pen Studio toggle
                IconButton(
                    onClick = {
                        showStudioToolbar = !showStudioToolbar
                        if (showStudioToolbar && activePageController.activeTool == DrawingTool.NONE) {
                            activePageController.selectTool(DrawingTool.FOUNTAIN_PEN)
                        } else if (!showStudioToolbar) {
                            activePageController.selectTool(DrawingTool.NONE)
                        }
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Kalem ve Çizim Stüdyosu",
                        tint = if (showStudioToolbar || activePageController.isDrawingActive) {
                            primaryColor
                        } else {
                            onSurfaceColor
                        },
                        modifier = Modifier.size(18.dp)
                    )
                }

                // AI Note Assistant button
                IconButton(
                    onClick = { showAiSheet = true },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = "PDF AI Asistanı",
                        tint = primaryColor,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // More Menu
                Box {
                    IconButton(
                        onClick = { showMoreMenu = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Diğer Seçenekler",
                            tint = onSurfaceColor,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = showMoreMenu,
                        onDismissRequest = { showMoreMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(if (isSavedToNotes) "Notlara Kaydedildi" else "Notlara Kaydet") },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (isSavedToNotes) Icons.Default.CheckCircle else Icons.Default.NoteAdd,
                                    contentDescription = null,
                                    tint = primaryColor,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            onClick = {
                                showMoreMenu = false
                                savePdfDocumentToNotes()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Not Olarak Aç (Düzenlenebilir)") },
                            leadingIcon = {
                                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(20.dp))
                            },
                            onClick = {
                                showMoreMenu = false
                                onSwitchToEditableNote()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(if (zoomScale > 1.05f) "Yakınlaştırmayı Sıfırla (%100)" else "Yakınlaştır (%200)") },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (zoomScale > 1.05f) Icons.Default.Remove else Icons.Default.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            onClick = {
                                showMoreMenu = false
                                if (zoomScale > 1.05f) {
                                    zoomScale = 1f
                                    panOffset = Offset.Zero
                                } else {
                                    zoomScale = 2f
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("PDF Dosyasını Paylaş") },
                            leadingIcon = {
                                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(20.dp))
                            },
                            onClick = {
                                showMoreMenu = false
                                runCatching {
                                    val shareUri = FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        pdfFile.localFile
                                    )
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "application/pdf"
                                        putExtra(Intent.EXTRA_STREAM, shareUri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(shareIntent, "PDF Paylaş"))
                                }
                            }
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = surfaceColor)
        )

        // Expandable Drawing Studio Toolbar
        AnimatedVisibility(visible = showStudioToolbar) {
            DrawingStudioBar(
                controller = activePageController,
                isCompactMode = true,
                onCloseStudio = {
                    showStudioToolbar = false
                    activePageController.selectTool(DrawingTool.NONE)
                },
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }

        // Main PDF Viewport with Pinch-to-Zoom & Pan + Lazy On-Demand Page Rendering
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF0F172A))
        ) {
            val viewportWidthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
            val viewportHeightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)

            val zoomGestureModifier = if (activePageController.activeTool == DrawingTool.NONE) {
                Modifier
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = {
                                if (zoomScale > 1.15f) {
                                    zoomScale = 1f
                                    panOffset = Offset.Zero
                                } else {
                                    zoomScale = 2.2f
                                }
                            },
                            onTap = {
                                showPageScrubber = !showPageScrubber
                            }
                        )
                    }
                    .pointerInput(zoomScale > 1.01f) {
                        if (zoomScale > 1.01f) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val newScale = (zoomScale * zoom).coerceIn(1f, 4f)
                                zoomScale = newScale
                                if (newScale > 1.01f) {
                                    val maxX = (viewportWidthPx * (newScale - 1f)) * 0.5f
                                    val maxY = (viewportHeightPx * (newScale - 1f)) * 0.5f
                                    panOffset = Offset(
                                        x = (panOffset.x + pan.x).coerceIn(-maxX, maxX),
                                        y = (panOffset.y + pan.y).coerceIn(-maxY, maxY)
                                    )
                                } else {
                                    panOffset = Offset.Zero
                                }
                            }
                        }
                    }
            } else {
                Modifier
            }

            LazyColumn(
                state = listState,
                userScrollEnabled = activePageController.activeTool == DrawingTool.NONE && zoomScale <= 1.05f,
                contentPadding = PaddingValues(
                    horizontal = 10.dp,
                    vertical = 12.dp
                ),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxSize()
                    .then(zoomGestureModifier)
                    .graphicsLayer {
                        scaleX = zoomScale
                        scaleY = zoomScale
                        translationX = panOffset.x
                        translationY = panOffset.y
                    }
            ) {
                items(
                    count = totalPages,
                    key = { pageIdx -> "pdf_page_$pageIdx" }
                ) { pageIdx ->
                    val pageController = getOrCreatePageController(pageIdx)
                    val isNearVisible = kotlin.math.abs(pageIdx - currentPageIndex) <= 1

                    LazyPdfPageCard(
                        pageIndex = pageIdx,
                        totalPages = totalPages,
                        isNearVisible = isNearVisible,
                        currentVisiblePage = currentPageIndex,
                        renderer = lazyRenderer,
                        drawingController = pageController,
                        onTwoFingerScroll = if (zoomScale <= 1.05f) {
                            { dy, _ -> listState.dispatchRawDelta(-dy) }
                        } else {
                            null
                        }
                    )
                }
            }

            // Floating Zoom Controls (Top-Right)
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
                shape = RoundedCornerShape(20.dp),
                color = surfaceColor.copy(alpha = 0.90f),
                tonalElevation = 4.dp,
                shadowElevation = 6.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    IconButton(
                        onClick = {
                            val next = (zoomScale - 0.25f).coerceAtLeast(1f)
                            zoomScale = next
                            if (next <= 1.01f) panOffset = Offset.Zero
                        },
                        enabled = zoomScale > 1.01f,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Remove,
                            contentDescription = "Uzaklaştır",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Text(
                        text = "%${(zoomScale * 100).roundToInt()}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = onSurfaceColor,
                        modifier = Modifier
                            .clickable {
                                zoomScale = if (zoomScale > 1.05f) 1f else 2f
                                panOffset = Offset.Zero
                            }
                            .padding(horizontal = 4.dp)
                    )
                    IconButton(
                        onClick = {
                            zoomScale = (zoomScale + 0.25f).coerceAtMost(4f)
                        },
                        enabled = zoomScale < 3.99f,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Yakınlaştır",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // Floating Bottom Page Indicator & Scrubber Bar
            androidx.compose.animation.AnimatedVisibility(
                visible = showPageScrubber && totalPages > 1,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = surfaceColor.copy(alpha = 0.94f),
                    tonalElevation = 6.dp,
                    shadowElevation = 8.dp,
                    border = BorderStroke(1.dp, onSurfaceColor.copy(alpha = 0.12f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 560.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        TextButton(
                            onClick = {
                                if (currentPageIndex > 0) {
                                    coroutineScope.launch {
                                        listState.scrollToItem(currentPageIndex - 1)
                                    }
                                }
                            },
                            enabled = currentPageIndex > 0,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("Önceki", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }

                        var sliderPage by remember(currentPageIndex) {
                            mutableFloatStateOf(currentPageIndex.toFloat())
                        }

                        Slider(
                            value = sliderPage,
                            onValueChange = { sliderPage = it },
                            onValueChangeFinished = {
                                val target = sliderPage.roundToInt().coerceIn(0, totalPages - 1)
                                coroutineScope.launch {
                                    listState.scrollToItem(target)
                                }
                            },
                            valueRange = 0f..(totalPages - 1).coerceAtLeast(1).toFloat(),
                            modifier = Modifier
                                .weight(1f)
                                .height(26.dp)
                        )

                        Text(
                            text = "${currentPageIndex + 1} / $totalPages",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = primaryColor
                        )

                        TextButton(
                            onClick = {
                                if (currentPageIndex < totalPages - 1) {
                                    coroutineScope.launch {
                                        listState.scrollToItem(currentPageIndex + 1)
                                    }
                                }
                            },
                            enabled = currentPageIndex < totalPages - 1,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("Sonraki", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }

    // AI Note Assistant ModalBottomSheet
    if (showAiSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAiSheet = false },
            containerColor = surfaceColor,
            contentColor = onSurfaceColor
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = primaryColor
                    )
                    Text(
                        text = "PDF Asistanı • S.${currentPageIndex + 1}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = onSurfaceColor
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        "Sayfayı özetle",
                        "Kavramları açıkla",
                        "Soru-cevap",
                        "Önemli noktalar"
                    ).forEach { quickPrompt ->
                        SuggestionChip(
                            onClick = {
                                aiQueryText = quickPrompt
                                runPdfAiAnalysis(quickPrompt)
                            },
                            label = {
                                Text(
                                    text = quickPrompt,
                                    color = onSurfaceColor,
                                    fontSize = 12.sp
                                )
                            }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = aiQueryText,
                        onValueChange = { aiQueryText = it },
                        placeholder = {
                            Text("Soru sor...", color = textMutedColor)
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = primaryColor,
                            unfocusedBorderColor = onSurfaceColor.copy(alpha = 0.3f),
                            focusedTextColor = onSurfaceColor,
                            unfocusedTextColor = onSurfaceColor,
                            cursorColor = primaryColor
                        )
                    )
                    IconButton(
                        onClick = { runPdfAiAnalysis(aiQueryText) },
                        enabled = !isAiLoading
                    ) {
                        if (isAiLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                color = primaryColor,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Analiz Et",
                                tint = primaryColor
                            )
                        }
                    }
                }

                if (aiError != null) {
                    Text(
                        text = aiError ?: "",
                        color = colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                if (!aiResult.isNullOrBlank()) {
                    if (aiSavedAsNote) {
                        Surface(
                            color = primaryColor.copy(alpha = 0.10f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = primaryColor,
                                    modifier = Modifier.size(15.dp)
                                )
                                Text(
                                    text = "Notlara kaydedildi",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Medium,
                                    color = primaryColor
                                )
                            }
                        }
                    }

                    Surface(
                        color = colorScheme.background,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(14.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            SelectionContainer {
                                Text(
                                    text = aiResult ?: "",
                                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                                    color = onSurfaceColor
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                Button(
                                    onClick = { saveAiResultToNotes(aiResult ?: "") },
                                    enabled = !aiSavedAsNote,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = primaryColor,
                                        contentColor = colorScheme.onPrimary
                                    ),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = if (aiSavedAsNote) "Kaydedildi" else "Notlara Kaydet",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LazyPdfPageCard(
    pageIndex: Int,
    totalPages: Int,
    isNearVisible: Boolean,
    currentVisiblePage: Int,
    renderer: LazyPdfPageRenderer,
    drawingController: DrawingController,
    onTwoFingerScroll: ((Float, Float) -> Unit)? = null
) {
    var pageBitmap by remember(pageIndex) {
        mutableStateOf(renderer.getCachedPage(pageIndex))
    }
    var pageAspectRatio by remember(pageIndex) {
        mutableFloatStateOf(0.707f) // Standard A4 portrait aspect ratio (W / H)
    }

    LaunchedEffect(pageIndex, isNearVisible, currentVisiblePage) {
        if (isNearVisible) {
            val bmp = renderer.renderPage(
                pageIndex = pageIndex,
                maxWidthPx = 1200,
                visiblePageHint = currentVisiblePage
            )
            if (bmp != null && !bmp.isRecycled) {
                pageBitmap = bmp
                if (bmp.height > 0) {
                    pageAspectRatio = (bmp.width.toFloat() / bmp.height.toFloat()).coerceIn(0.35f, 2.5f)
                }
            }
        } else {
            // Release reference when page scrolls far away so LruCache can reclaim memory
            pageBitmap = null
        }
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color.White,
        shadowElevation = 6.dp,
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 860.dp)
            .aspectRatio(pageAspectRatio)
            .clip(RoundedCornerShape(8.dp))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            val currentBmp = pageBitmap
            if (currentBmp != null && !currentBmp.isRecycled) {
                Image(
                    bitmap = currentBmp.asImageBitmap(),
                    contentDescription = "PDF Sayfa ${pageIndex + 1}",
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.White),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 2.5.dp
                    )
                }
            }

            // Per-page interactive Drawing & Annotation Canvas Overlay
            DrawingCanvasOverlay(
                controller = drawingController,
                modifier = Modifier.fillMaxSize(),
                onTwoFingerScroll = onTwoFingerScroll
            )

            // Subtle page number corner badge
            Surface(
                shape = RoundedCornerShape(bottomStart = 8.dp),
                color = Color(0xFF0F172A).copy(alpha = 0.65f),
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                Text(
                    text = "${pageIndex + 1} / $totalPages",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
    }
}
