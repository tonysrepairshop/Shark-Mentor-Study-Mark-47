package com.example.sharkmentor.ui.screens.notes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.sharkmentor.BuildConfig
import com.example.sharkmentor.core.ai.ApiKeyRotator
import com.example.sharkmentor.core.ai.DeepSeekClient
import com.example.sharkmentor.core.ai.GeminiAiFactory
import com.example.sharkmentor.core.util.NoteStyleParser
import com.example.sharkmentor.core.util.PaperPattern
import com.example.sharkmentor.data.local.SharkMentorDatabase
import com.example.sharkmentor.data.local.entity.NoteEntity
import com.example.sharkmentor.data.repository.NoteRepository
import com.example.sharkmentor.data.repository.NoteRepositoryImpl
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.QuotaExceededException
import com.google.ai.client.generativeai.type.RequestOptions
import com.google.ai.client.generativeai.type.ResponseStoppedException
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@OptIn(FlowPreview::class)
class NoteDetailViewModel(
    private val noteRepository: NoteRepository
) : ViewModel() {

    companion object {
        const val ISOLATED_NOTE_SYSTEM_INSTRUCTION = """
Sen öğrencilere ders çalışma ve not analizi konusunda rehberlik eden uzman bir AI çalışma asistanısın. YALNIZCA verilen not içeriğini ve belgedeki bilgileri baz al. Asla belgede olmayan uydurma bilgi ekleme. Eğer sorulan bilgi notta yoksa açıkça 'Bu bilgi not içinde bulunamadı.' yaz.
KESİN DAVRANIŞ KURALLARI:
1. DOĞRUDAN VE ETKİLİ BAŞLANGIÇ: "Harika bir not!", "Merhaba!" gibi yapay girişler kullanma. Doğrudan analiz, özet veya soru cevabına gir.
2. DERİNLEMESİNE VE DETAYLI ANLATIM: Kullanıcının sorusuna veya özet isteğine göre kapsamlı, öğretici ve doyurucu açıklamalar sun. Önemli kavramları, formülleri, tanımları ve alt başlıkları detaylandır. Yüzeysel veya aşırı kısa cevaplar verme; öğrencinin konuyu eksiksiz ve derinlemesine anlamasını sağla.
3. DÜZENLİ FORMATLAMA: Bilgileri net başlıklar (Markdown), maddeler, formüller ve vurgulu anahtar kelimeler ile sun.
4. ÇALIŞMA KANCASI: Yanıtın en sonuna, öğrencinin konuyu pekiştirmesi için tek cümlelik doğal bir çalışma önerisi veya takip sorusu ekle.
"""

        private val MODEL_TIERS = listOf(
            "gemini-3.6-flash"
        )

        private val BASE64_OR_PATH_REGEX = Regex(
            pattern = "(\\[PDF_PAGE:[^\\]]+\\]|data:image/[^;]+;base64,[A-Za-z0-9+/=]+|file:///[^\\s]+|/data/user/[^\\s]+|[A-Za-z0-9+/=]{160,})"
        )

        private val PDF_PAGE_TAG_REGEX = Regex("\\[PDF_PAGE:([^\\]]+)\\]")

        fun extractPdfPagePaths(rawContent: String): List<String> {
            return PDF_PAGE_TAG_REGEX.findAll(rawContent)
                .map { it.groupValues[1] }
                .filter { it.isNotBlank() }
                .toList()
        }

        fun sanitizeNoteTextForAi(raw: String): String {
            return raw
                .replace(BASE64_OR_PATH_REGEX, "")
                .replace(Regex("<[^>]+>"), "")
                .replace(Regex("\\n{3,}"), "\n\n")
                .trim()
                .take(12_000)
        }
    }

    private val _noteId = MutableStateFlow(UUID.randomUUID().toString())
    val noteId: StateFlow<String> = _noteId.asStateFlow()

    private val _noteTitle = MutableStateFlow("")
    val noteTitle: StateFlow<String> = _noteTitle.asStateFlow()

    private val _noteContent = MutableStateFlow("")
    val noteContent: StateFlow<String> = _noteContent.asStateFlow()

    private val _colorHex = MutableStateFlow("#F8FAFC")
    val colorHex: StateFlow<String> = _colorHex.asStateFlow()

    private val _isPinned = MutableStateFlow(false)
    val isPinned: StateFlow<Boolean> = _isPinned.asStateFlow()

    private val _createdAt = MutableStateFlow(System.currentTimeMillis())

    private val _aiResult = MutableStateFlow<String?>(null)
    val aiResult: StateFlow<String?> = _aiResult.asStateFlow()

    private val _isAiLoading = MutableStateFlow(false)
    val isAiLoading: StateFlow<Boolean> = _isAiLoading.asStateFlow()

    private val _aiError = MutableStateFlow<String?>(null)
    val aiError: StateFlow<String?> = _aiError.asStateFlow()

    private val _aiSavedNewNoteId = MutableStateFlow<String?>(null)
    val aiSavedNewNoteId: StateFlow<String?> = _aiSavedNewNoteId.asStateFlow()

    fun resetAiSavedNewNoteStatus() {
        _aiSavedNewNoteId.value = null
    }

    init {
        observeAutoSaveDebounce()
    }

    private fun observeAutoSaveDebounce() {
        viewModelScope.launch(Dispatchers.IO) {
            combine(_noteTitle, _noteContent, _colorHex, _isPinned) { title, content, color, pinned ->
                NoteEntity(
                    id = _noteId.value,
                    title = title,
                    content = content,
                    colorHex = color,
                    isPinned = pinned,
                    createdAt = _createdAt.value,
                    updatedAt = System.currentTimeMillis()
                )
            }
                .drop(1)
                .debounce(600L)
                .distinctUntilChanged { old, new ->
                    old.title == new.title &&
                        old.content == new.content &&
                        old.colorHex == new.colorHex &&
                        old.isPinned == new.isPinned
                }
                .collectLatest { note ->
                    if (note.title.isNotBlank() || note.content.isNotBlank()) {
                        withContext(Dispatchers.IO) {
                            noteRepository.insertOrUpdateNote(note)
                        }
                    }
                }
        }
    }

    fun loadNoteById(id: String) {
        _noteId.value = id
        _aiResult.value = null
        _aiError.value = null
        viewModelScope.launch(Dispatchers.IO) {
            val note = noteRepository.getNoteById(id)
            if (note != null) {
                withContext(Dispatchers.Main) {
                    _noteTitle.value = note.title
                    _noteContent.value = note.content
                    _colorHex.value = note.colorHex
                    _isPinned.value = note.isPinned
                    _createdAt.value = note.createdAt
                }
            }
        }
    }

    fun initializeEmptyNote() {
        _noteId.value = UUID.randomUUID().toString()
        _noteTitle.value = ""
        _noteContent.value = ""
        _colorHex.value = "#F8FAFC"
        _isPinned.value = false
        _createdAt.value = System.currentTimeMillis()
        _aiResult.value = null
        _aiError.value = null
    }

    fun initializeNote(
        initialNote: NoteEntity?,
        fallbackTitle: String = "",
        fallbackContent: String = ""
    ) {
        _noteId.value = initialNote?.id ?: UUID.randomUUID().toString()
        _noteTitle.value = initialNote?.title ?: fallbackTitle
        _noteContent.value = initialNote?.content ?: fallbackContent
        _colorHex.value = initialNote?.colorHex ?: "#F8FAFC"
        _isPinned.value = initialNote?.isPinned ?: false
        _createdAt.value = initialNote?.createdAt ?: System.currentTimeMillis()
        _aiResult.value = null
        _aiError.value = null
    }

    fun onTitleChanged(newTitle: String) {
        _noteTitle.value = newTitle
    }

    fun onContentChanged(newContent: String) {
        _noteContent.value = newContent
    }

    fun onColorHexChanged(newColorHex: String) {
        _colorHex.value = newColorHex
    }

    fun onPinnedChanged(pinned: Boolean) {
        _isPinned.value = pinned
        saveImmediately()
    }

    fun setPaperPattern(pattern: PaperPattern) {
        val currentTint = NoteStyleParser.getTintHex(_colorHex.value)
        _colorHex.value = NoteStyleParser.encode(currentTint, pattern)
    }

    fun setPaperTint(tintHex: String) {
        val currentPattern = NoteStyleParser.getPattern(_colorHex.value)
        _colorHex.value = NoteStyleParser.encode(tintHex, currentPattern)
    }

    fun duplicateCurrentNote(onComplete: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val originalTitle = _noteTitle.value.trim().ifBlank { "Not" }
            val copyTitle = "$originalTitle (Kopya)"
            val newId = UUID.randomUUID().toString()
            val copyNote = NoteEntity(
                id = newId,
                title = copyTitle,
                content = _noteContent.value,
                colorHex = _colorHex.value,
                isPinned = false,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            noteRepository.saveNote(copyNote)
            withContext(Dispatchers.Main) {
                onComplete(newId)
            }
        }
    }

    fun deleteCurrentNote(onComplete: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            noteRepository.deleteNote(_noteId.value)
            withContext(Dispatchers.Main) {
                onComplete()
            }
        }
    }

    fun saveAiSummaryAsNewNote(summaryText: String, onComplete: ((String) -> Unit)? = null) {
        if (summaryText.isBlank()) return
        val currentTitle = _noteTitle.value.trim().ifBlank { "Ders Notu" }
        val newNoteTitle = "[Özet] $currentTitle"
        val newNoteId = UUID.randomUUID().toString()
        val newNote = NoteEntity(
            id = newNoteId,
            title = newNoteTitle,
            content = summaryText.trim(),
            colorHex = NoteStyleParser.encode("#FDF6E2", PaperPattern.RULED),
            isPinned = false,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        viewModelScope.launch(Dispatchers.IO) {
            noteRepository.saveNote(newNote)
            _aiSavedNewNoteId.value = newNoteId
            if (onComplete != null) {
                withContext(Dispatchers.Main) {
                    onComplete(newNoteId)
                }
            }
        }
    }

    fun saveImmediately() {
        viewModelScope.launch(Dispatchers.IO) {
            val currentTitle = _noteTitle.value
            val currentContent = _noteContent.value
            if (currentTitle.isNotBlank() || currentContent.isNotBlank() || _isPinned.value) {
                val entity = NoteEntity(
                    id = _noteId.value,
                    title = currentTitle,
                    content = currentContent,
                    colorHex = _colorHex.value,
                    isPinned = _isPinned.value,
                    createdAt = _createdAt.value,
                    updatedAt = System.currentTimeMillis()
                )
                noteRepository.insertOrUpdateNote(entity)
            }
        }
    }

    fun analyzeNoteWithAi(query: String) {
        val rawContent = _noteContent.value
        val currentTitle = _noteTitle.value.trim()
        val cleanNoteText = sanitizeNoteTextForAi(rawContent)
        val pdfPagePaths = extractPdfPagePaths(rawContent)

        _isAiLoading.value = true
        _aiError.value = null
        _aiResult.value = ""

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Check if the note consists primarily of PDF pages without substantial typed/extracted body text
                val textWithoutPdfHeader = cleanNoteText
                    .replace(Regex("^[^\n]+\\([0-9]+\\s*sayfa\\)", RegexOption.IGNORE_CASE), "")
                    .replace("Tüm PDF sayfaları yüklendi. Notlarınızı aşağıya ekleyebilirsiniz:", "")
                    .replace("Tüm PDF sayfaları yüklendi.", "")
                    .trim()

                val hasSubstantialText = textWithoutPdfHeader.length >= 80
                val shouldUseVisionForPdf = pdfPagePaths.isNotEmpty() && !hasSubstantialText

                if (shouldUseVisionForPdf) {
                    val pageBitmaps = pdfPagePaths.take(5).mapNotNull { path ->
                        runCatching {
                            val original = BitmapFactory.decodeFile(path) ?: return@runCatching null
                            if (original.width > 800) {
                                val ratio = 800f / original.width.toFloat()
                                val targetHeight = (original.height * ratio).toInt().coerceAtLeast(1)
                                val scaled = Bitmap.createScaledBitmap(original, 800, targetHeight, true)
                                if (scaled != original) original.recycle()
                                scaled
                            } else {
                                original
                            }
                        }.getOrNull()
                    }
                    if (pageBitmaps.isNotEmpty()) {
                        val accumulated = StringBuilder()
                        try {
                            val visionSystemInstruction = """
                                Sen bu belgedeki görselleri ve sayfaları analiz eden uzman bir çalışma asistanısın. YALNIZCA verilen sayfa görsellerindeki bilgileri baz al. Asla belgede olmayan bilgi ekleme. Eğer sorulan bilgi sayfalarda yoksa açıkça 'Bu bilgi not içinde bulunamadı.' yaz.
                                KESİN DAVRANIŞ KURALLARI:
                                1. DOĞRUDAN BAŞLANGIÇ: Yapay selamlamalar veya gereksiz girişler kullanma. Doğrudan analize gir.
                                2. DETAYLI VE KAPSAMLI ANALİZ: Belgedeki ana başlıkları, önemli kavramları, açıklamaları ve çalışma noktalarını zengin, anlaşılır ve eksiksiz bir şekilde açıkla. Yüzeysel geçme; öğrencinin çalışabileceği derinlikte özetle.
                                3. DÜZENLİ BİÇİMLENDİRME: Başlıklar, maddeler ve vurgulu terimler kullanarak okunabilirliği artır.
                                4. ÇALIŞMA KANCASI: En sona tek cümlelik motive edici bir takip sorusu ekle.
                            """.trimIndent()

                            val visionPrompt = query.trim().ifBlank {
                                "Bu PDF belgesindeki sayfaları detaylıca incele, ana konuları özetle ve önemli noktaları maddeler halinde çıkar."
                            }
                            val visionModel = GeminiAiFactory.createVisionModel(
                                systemPrompt = visionSystemInstruction
                            )
                            val inputContent = content {
                                pageBitmaps.forEach { image(it) }
                                text(visionPrompt)
                            }
                            val responseFlow = visionModel.generateContentStream(inputContent)
                            try {
                                responseFlow.collect { chunk ->
                                    val chunkText = chunk.text
                                    if (!chunkText.isNullOrEmpty()) {
                                        accumulated.append(chunkText)
                                        _aiResult.value = accumulated.toString()
                                        _isAiLoading.value = false
                                    }
                                }
                            } catch (rse: ResponseStoppedException) {
                                Log.w("NoteDetailVM", "Vision stopped by token limit: ${rse.message}")
                            }

                            if (accumulated.isNotBlank()) {
                                _aiResult.value = accumulated.toString()
                                _aiError.value = null
                                _isAiLoading.value = false
                                return@launch
                            }
                        } catch (e: Exception) {
                            Log.w("NoteDetailVM", "Vision summary fallback failed: ${e.message}", e)
                            if (accumulated.isNotBlank()) {
                                _aiResult.value = accumulated.toString()
                                _aiError.value = null
                                _isAiLoading.value = false
                                return@launch
                            }
                        } finally {
                            pageBitmaps.forEach { runCatching { it.recycle() } }
                        }
                    }

                    // If note has no actual written text and vision failed to analyze the pages, don't fallback to DeepSeek with empty placeholder text
                    if (!hasSubstantialText) {
                        _aiError.value = "PDF sayfaları görsel olarak çözümlenemedi. Lütfen internet bağlantınızı kontrol edip tekrar deneyin."
                        _aiResult.value = null
                        _isAiLoading.value = false
                        return@launch
                    }
                }

                if (cleanNoteText.isBlank() && currentTitle.isBlank()) {
                    _aiResult.value = "Bu bilgi not içinde bulunamadı."
                    _isAiLoading.value = false
                    return@launch
                }

                val effectiveQuery = query.trim().ifBlank {
                    "Bu notun içeriğini özetle ve yalnızca notta geçen temel noktaları listele."
                }

                val contextualPrompt = """
                    [NOT BAŞLIĞI]: ${currentTitle.ifBlank { "Başlıksız Not" }}
                    [NOT METNİ]:
                    $cleanNoteText

                    [KULLANICI SORUSU / İSTEĞİ]:
                    $effectiveQuery
                """.trimIndent()

                // Text-only note analysis: Use DeepSeek first with Kimi fallback
                val deepSeekKey = DeepSeekClient.getDeepSeekApiKey()
                if (deepSeekKey.isNotBlank()) {
                    try {
                        val streamedResult = DeepSeekClient.streamChatCompletion(
                            systemPrompt = ISOLATED_NOTE_SYSTEM_INSTRUCTION.trim(),
                            userPrompt = contextualPrompt,
                            temperature = 0.2f,
                            maxTokens = 4096,
                            onAccumulatedChunk = { partial ->
                                _aiResult.value = partial
                                _isAiLoading.value = false
                            }
                        )
                        if (streamedResult.isNotBlank()) {
                            _aiResult.value = streamedResult
                            _aiError.value = null
                            return@launch
                        }
                    } catch (ce: CancellationException) {
                        _isAiLoading.value = false
                        return@launch
                    } catch (e: Exception) {
                        Log.w("NoteDetailVM", "DeepSeek/Kimi note analysis failed (${e.message}), trying Gemini...", e)
                    }
                }

                val noteAiGenerationConfig = generationConfig {
                    temperature = 0.2f
                    maxOutputTokens = 4096
                    topP = 0.85f
                }

                var lastError: Throwable? = null
                var responseText: String? = null
                val keyCount = ApiKeyRotator.getKeyCount().coerceAtLeast(1)

                outer@ for (modelName in MODEL_TIERS) {
                    for (attempt in 0 until keyCount) {
                        val activeKey = ApiKeyRotator.getActiveKey()
                            .ifBlank { BuildConfig.GEMINI_API_KEY.trim() }
                            .trim()
                            .replace("\"", "")
                            .replace("'", "")

                        if (activeKey.isBlank() || activeKey == "null") {
                            ApiKeyRotator.rotateToNextKey()
                            continue
                        }

                        try {
                            val model = GenerativeModel(
                                modelName = modelName,
                                apiKey = activeKey,
                                generationConfig = noteAiGenerationConfig,
                                tools = emptyList(),
                                systemInstruction = content {
                                    text(ISOLATED_NOTE_SYSTEM_INSTRUCTION.trim())
                                },
                                requestOptions = RequestOptions(timeout = 30_000L)
                            )

                            val builder = StringBuilder()
                            try {
                                model.generateContentStream(contextualPrompt).collect { chunk ->
                                    val chunkText = runCatching { chunk.text }.getOrNull()
                                    if (!chunkText.isNullOrEmpty()) {
                                        builder.append(chunkText)
                                        _aiResult.value = builder.toString().trimStart()
                                        _isAiLoading.value = false
                                    }
                                }
                            } catch (rse: ResponseStoppedException) {
                                Log.w("NoteDetailVM", "Note generation stream stopped (${rse.message}), keeping accumulated")
                            }

                            val finalStreamed = builder.toString().trim()
                            if (finalStreamed.isNotBlank()) {
                                responseText = finalStreamed
                                break@outer
                            }
                        } catch (ce: CancellationException) {
                            _isAiLoading.value = false
                            return@launch
                        } catch (e: QuotaExceededException) {
                            Log.w("NoteDetailVM", "QuotaExceededException on $modelName, rotating key...", e)
                            lastError = e
                            ApiKeyRotator.rotateToNextKey()
                        } catch (e: Throwable) {
                            Log.w("NoteDetailVM", "AI call failed on $modelName: ${e.message}", e)
                            lastError = e
                            val msg = "${e.message} ${e.cause?.message}".lowercase()
                            if (msg.contains("429") || msg.contains("quota") || msg.contains("resource_exhausted") || ApiKeyRotator.shouldRotateKey(e as? Exception ?: Exception(e))) {
                                ApiKeyRotator.rotateToNextKey()
                            } else {
                                break
                            }
                        }
                    }
                }

                if (!responseText.isNullOrBlank()) {
                    _aiResult.value = responseText
                    _aiError.value = null
                    saveAiSummaryAsNewNote(responseText)
                } else {
                    _aiResult.value = null
                    _aiError.value = lastError?.localizedMessage ?: "Not analiz edilemedi."
                }
            } catch (e: Throwable) {
                if (e !is CancellationException) {
                    Log.e("NoteDetailVM", "Unhandled Throwable in analyzeNoteWithAi", e)
                    _aiError.value = e.localizedMessage ?: "Not AI sırasında beklenmeyen bir hata oluştu."
                }
            } finally {
                _isAiLoading.value = false
            }
        }
    }

    fun appendAiResultToNote(customResult: String? = null): String {
        val resultToAppend = (customResult ?: _aiResult.value)?.trim()
        if (resultToAppend.isNullOrBlank()) return _noteContent.value

        val existing = _noteContent.value
        val updatedContent = if (existing.isBlank()) {
            resultToAppend
        } else {
            "$existing\n\n--- Not AI Analizi ---\n$resultToAppend"
        }

        _noteContent.value = updatedContent
        saveImmediately()
        return updatedContent
    }

    fun clearAiResult() {
        _aiResult.value = null
        _aiError.value = null
    }

    fun deleteNote(onDeleted: () -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            noteRepository.deleteNote(_noteId.value)
            withContext(Dispatchers.Main) {
                onDeleted()
            }
        }
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val db = SharkMentorDatabase.getDatabase(context.applicationContext)
            val repo = NoteRepositoryImpl(db.noteDao(), context.applicationContext)
            return NoteDetailViewModel(repo) as T
        }
    }
}
