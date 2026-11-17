package com.example.sharkmentor

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.sharkmentor.core.data.ThemePreferences
import com.example.sharkmentor.core.theme.ThemeManager
import com.example.sharkmentor.core.util.GoogleServicesHelper
import com.example.sharkmentor.core.util.PdfBitmapConverter
import com.example.sharkmentor.ui.MainScreen
import com.example.sharkmentor.ui.auth.AuthScreen
import com.example.sharkmentor.ui.theme.SharkMentorTheme
import com.google.firebase.firestore.SetOptions
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.util.zip.Inflater
import java.util.zip.ZipInputStream

data class ImportedNotePayload(
    val title: String,
    val content: String,
    val sourceUri: Uri? = null,
    val mimeType: String? = null,
    val pdfPreviewBitmap: Bitmap? = null,
    val pdfPageCount: Int = 0,
    val pdfPageImagePaths: List<String> = emptyList()
)

/**
 * Single-activity entry point for SharkMentor.
 */
class MainActivity : ComponentActivity() {

    private var importedNoteState by mutableStateOf<ImportedNotePayload?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        splashScreen.setKeepOnScreenCondition { false }
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        val openReflection = intent.getBooleanExtra("EXTRA_OPEN_REFLECTION", false)

        handleIncomingIntent(intent)

        setContent {
            val themePrefs = remember { ThemePreferences.initialize(this) }
            val themeMode by themePrefs.themeMode.collectAsStateWithLifecycle()
            val currentPalette by themePrefs.palette.collectAsStateWithLifecycle()
            val isZenModePref by themePrefs.isZenMode.collectAsStateWithLifecycle()
            val isZenMinimalist by ThemeManager.isZenMinimalist.collectAsStateWithLifecycle()
            val isZenMode = isZenModePref || isZenMinimalist

            val userPrefs = remember { com.example.sharkmentor.data.local.preferences.UserPreferences.getInstance(this) }
            val auth = remember { GoogleServicesHelper.getFirebaseAuth(this) }
            val currentUser = GoogleServicesHelper.getCurrentUser(auth, this)
            var isLoggedIn by remember { mutableStateOf(currentUser != null || userPrefs.isOnboarded) }

            SharkMentorTheme(
                themeMode = themeMode,
                palette = currentPalette,
                isZenMode = isZenMode
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (isLoggedIn) {
                        MainScreen(
                            openReflectionDialog = openReflection,
                            importedNote = importedNoteState,
                            onImportedNoteConsumed = { importedNoteState = null }
                        )
                    } else {
                        AuthScreen(
                            onAuthSuccess = {
                                registerFcmToken()
                                isLoggedIn = true
                            },
                            onGuestContinue = {
                                userPrefs.completeOnboardingAsIndividual("Misafir Öğrenci")
                                isLoggedIn = true
                            }
                        )
                    }
                }
            }
        }

        try {
            if (GoogleServicesHelper.getCurrentUser(null, this) != null) {
                registerFcmToken()
            }
        } catch (e: Throwable) {
            Log.w("MainActivity", "Error checking current user: ${e.message}")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return

        when (intent.action) {
            Intent.ACTION_VIEW -> {
                val uri = intent.data ?: return
                lifecycleScope.launch(Dispatchers.IO) {
                    val payload = readUriToNotePayload(uri, intent.type)
                    withContext(Dispatchers.Main) {
                        importedNoteState = payload
                    }
                }
            }
            Intent.ACTION_SEND -> {
                val streamUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }

                if (streamUri != null) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val payload = readUriToNotePayload(streamUri, intent.type)
                        withContext(Dispatchers.Main) {
                            importedNoteState = payload
                        }
                    }
                } else {
                    val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                    if (!sharedText.isNullOrBlank()) {
                        val sharedTitle = intent.getStringExtra(Intent.EXTRA_SUBJECT)
                            ?: intent.getStringExtra(Intent.EXTRA_TITLE)
                            ?: "Paylaşılan Not"
                        val safeText = if (sharedText.length > 15_000) {
                            sharedText.take(15_000) + "\n\n[Not: İçerik 15.000 karakterle sınırlandırıldı.]"
                        } else sharedText
                        importedNoteState = ImportedNotePayload(
                            title = sharedTitle,
                            content = safeText,
                            sourceUri = null,
                            mimeType = "text/plain"
                        )
                    }
                }
            }
        }
    }

    private fun readUriToNotePayload(uri: Uri, fallbackMimeType: String?): ImportedNotePayload {
        val resolver = contentResolver
        val mimeType = resolver.getType(uri) ?: fallbackMimeType
        val displayName = resolveDisplayName(resolver, uri)
        val noteTitle = displayName.substringBeforeLast('.', displayName).ifBlank { "İçe Aktarılan Not" }
        val isPdf = mimeType == "application/pdf" || displayName.endsWith(".pdf", ignoreCase = true)

        var pdfPreviewBitmap: Bitmap? = null
        var pdfPageCount = 0
        var pdfPageImagePaths: List<String> = emptyList()

        if (isPdf) {
            runCatching {
                pdfPageImagePaths = PdfBitmapConverter.convertPdfUriToPagePaths(this, uri, maxPages = 25)
                pdfPageCount = pdfPageImagePaths.size
                if (pdfPageCount == 0) {
                    resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        PdfRenderer(pfd).use { renderer ->
                            pdfPageCount = renderer.pageCount
                        }
                    }
                }
                pdfPreviewBitmap = pdfPageImagePaths.firstOrNull()?.let { firstPath ->
                    android.graphics.BitmapFactory.decodeFile(firstPath)
                } ?: PdfBitmapConverter.convertPdfUriToBitmaps(this, uri, maxPages = 1).firstOrNull()
            }.onFailure { e ->
                Log.w("MainActivity", "Failed to render PDF pages for $uri: ${e.message}")
            }
        }

        val extractedContent = runCatching {
            com.example.sharkmentor.core.util.DocumentTextExtractor.extractCleanText(
                context = this,
                uri = uri,
                displayName = displayName,
                mimeType = mimeType
            )
        }.getOrElse { e ->
            Log.w("MainActivity", "Failed to read incoming URI $uri: ${e.message}")
            ""
        }

        val safeContent = if (extractedContent.length > 30_000) {
            extractedContent.take(30_000)
        } else {
            extractedContent
        }

        return ImportedNotePayload(
            title = noteTitle,
            content = safeContent,
            sourceUri = uri,
            mimeType = if (isPdf) "application/pdf" else mimeType,
            pdfPreviewBitmap = pdfPreviewBitmap,
            pdfPageCount = pdfPageCount,
            pdfPageImagePaths = pdfPageImagePaths
        )
    }

    private fun extractPdfPlainText(pdfBytes: ByteArray): String {
        return PdfBitmapConverter.extractPdfPlainTextFromBytes(pdfBytes)
    }

    private fun extractDocxText(inputStream: java.io.InputStream, displayName: String): String {
        return runCatching {
            ZipInputStream(inputStream).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == "word/document.xml") {
                        val xml = BufferedReader(InputStreamReader(zip, Charsets.UTF_8)).readText()
                        return@use xml
                            .replace("</w:p>", "\n")
                            .replace("<w:tab/>", "\t")
                            .replace(Regex("<[^>]+>"), "")
                            .trim()
                    }
                    entry = zip.nextEntry
                }
                "[DOCX Dosyası: $displayName]"
            }
        }.getOrDefault("[DOCX Dosyası: $displayName]")
    }

    private fun resolveDisplayName(resolver: ContentResolver, uri: Uri): String {
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1 && cursor.moveToFirst()) {
                        val name = cursor.getString(nameIndex)
                        if (!name.isNullOrBlank()) return name
                    }
                }
            }
        }
        return uri.lastPathSegment ?: "İçe Aktarılan Not"
    }

    private fun registerFcmToken() {
        if (!GoogleServicesHelper.isGooglePlayServicesAvailable(this)) {
            Log.w("FCM", "Google Play Services unavailable. Skipping FCM token registration.")
            return
        }
        try {
            val messaging = GoogleServicesHelper.getFirebaseMessaging(this) ?: return
            messaging.token
                .addOnSuccessListener { token ->
                    val currentUserId = GoogleServicesHelper.getCurrentUser(null, this)?.uid ?: return@addOnSuccessListener
                    GoogleServicesHelper.getFirebaseFirestore(this)?.collection("students")?.document(currentUserId)
                        ?.set(hashMapOf("fcmToken" to token), SetOptions.merge())
                        ?.addOnSuccessListener {
                            Log.d("FCM", "Token Firestore'a mühürlendi: $token")
                        }
                        ?.addOnFailureListener { e ->
                            Log.w("FCM", "Failed to save FCM token to Firestore: ${e.message}")
                        }
                }
                .addOnFailureListener { e ->
                    Log.w("FCM", "Failed to retrieve FCM token: ${e.message}")
                }
        } catch (e: Throwable) {
            Log.w("FCM", "Exception during FCM token retrieval: ${e.message}")
        }
    }
}
