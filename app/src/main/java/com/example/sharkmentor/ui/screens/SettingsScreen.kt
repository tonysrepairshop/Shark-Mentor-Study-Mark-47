package com.example.sharkmentor.ui.screens

import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.sharkmentor.core.ai.AiProviderChoice
import com.example.sharkmentor.core.ai.ApiKeyRotator
import com.example.sharkmentor.core.ai.GeminiAiFactory
import com.example.sharkmentor.core.ai.GeminiConnectionTestResult
import com.example.sharkmentor.core.ai.GeminiRestClient
import com.example.sharkmentor.core.ai.PreferredPowerMode
import com.example.sharkmentor.core.ai.UserAiPreferences
import com.example.sharkmentor.core.data.AppThemeMode
import com.example.sharkmentor.core.data.ThemePalette
import com.example.sharkmentor.core.data.ThemePreferences
import com.example.sharkmentor.core.util.AppIconManager
import com.example.sharkmentor.core.util.GoogleServicesHelper
import com.example.sharkmentor.core.util.SharkAppIcon
import com.example.sharkmentor.data.local.SharkMentorDatabase
import com.example.sharkmentor.ui.components.ReflectionTimeDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBackClick: () -> Unit,
    onOpenAppBlocker: () -> Unit,
    onOpenAnalysis: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenZenRoom: () -> Unit = {},
    onOpenCountdownSettings: () -> Unit = {},
    onAccountDeleted: () -> Unit = {}
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showTimeDialog by remember { mutableStateOf(false) }

    val themePrefs = remember { ThemePreferences.instance }
    val themeMode by themePrefs.themeMode.collectAsStateWithLifecycle()
    val currentPalette by themePrefs.palette.collectAsStateWithLifecycle()

    if (showTimeDialog) {
        ReflectionTimeDialog(
            onDismiss = { showTimeDialog = false },
            onTimeSaved = { _, _ -> }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Ayarlar") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Geri")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ThemeSelectorSection(
                themeMode = themeMode,
                onThemeModeToggle = { themePrefs.setThemeMode(it) },
                currentPalette = currentPalette,
                onSelectPalette = { themePrefs.setPalette(it) }
            )

            HorizontalDivider()

            AiSettingsSection(snackbarHostState = snackbarHostState)

            HorizontalDivider()

            AppIconSelectorSection(snackbarHostState = snackbarHostState)

            HorizontalDivider()

            Text("Bildirim & Hedefler", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)

            SettingRow(
                icon = Icons.Default.AccessTime,
                label = "Gün Değerlendirmesi Saati",
                onClick = { showTimeDialog = true }
            )

            SettingRow(
                icon = Icons.Default.CalendarMonth,
                label = "Hedef Sınav & Geri Sayım",
                onClick = onOpenCountdownSettings
            )

            HorizontalDivider()

            Text("Odak Modülleri", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)

            SettingRow(
                icon = Icons.Default.SelfImprovement,
                label = "Zen Odası",
                onClick = onOpenZenRoom
            )

            SettingRow(
                icon = Icons.Default.Block,
                label = "Uygulama Engelleyici",
                onClick = onOpenAppBlocker
            )

            HorizontalDivider()

            Text("Analiz & Geçmiş", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)

            SettingRow(
                icon = Icons.Default.Analytics,
                label = "Detaylı Analiz",
                onClick = onOpenAnalysis
            )

            SettingRow(
                icon = Icons.Default.History,
                label = "Çalışma Geçmişi",
                onClick = onOpenHistory
            )

            HorizontalDivider()

            Text("Hesap", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)

            Button(
                onClick = { executePermanentAccountDeletion(context, onAccountDeleted) },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.DeleteForever, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Hesabı Kalıcı Sil", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AiSettingsSection(snackbarHostState: SnackbarHostState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val userAiPrefs = remember { UserAiPreferences.initialize(context) }

    val savedGemini by userAiPrefs.userGeminiKeys.collectAsStateWithLifecycle()
    val savedDeepSeek by userAiPrefs.userDeepSeekKey.collectAsStateWithLifecycle()
    val savedKimi by userAiPrefs.userKimiKey.collectAsStateWithLifecycle()
    val savedProvider by userAiPrefs.selectedAiProvider.collectAsStateWithLifecycle()
    val savedPowerMode by userAiPrefs.preferredPowerMode.collectAsStateWithLifecycle()

    var geminiKeysInput by remember(savedGemini) { mutableStateOf(savedGemini) }
    var deepSeekInput by remember(savedDeepSeek) { mutableStateOf(savedDeepSeek) }
    var kimiInput by remember(savedKimi) { mutableStateOf(savedKimi) }
    var selectedProvider by remember(savedProvider) { mutableStateOf(savedProvider) }
    var selectedPowerMode by remember(savedPowerMode) { mutableStateOf(savedPowerMode) }

    var showGeminiKey by remember { mutableStateOf(false) }
    var showDeepSeekKey by remember { mutableStateOf(false) }
    var showKimiKey by remember { mutableStateOf(false) }
    var isTestingConnection by remember { mutableStateOf(false) }
    var connectionTestResult by remember { mutableStateOf<GeminiConnectionTestResult?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Yapay Zeka & API",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = GeminiAiFactory.SOLE_GEMINI_MODEL,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        if (geminiKeysInput.isBlank() || deepSeekInput.isBlank() || kimiInput.isBlank()) {
            Text(
                text = "Boş bırakılırsa standart uygulama anahtarları kullanılır.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        ApiKeyInputField(
            value = geminiKeysInput,
            onValueChange = {
                geminiKeysInput = it
                connectionTestResult = null
            },
            label = "Gemini API Key",
            placeholder = "key1, key2",
            isVisible = showGeminiKey,
            onToggleVisibility = { showGeminiKey = !showGeminiKey },
            onClear = {
                geminiKeysInput = ""
                connectionTestResult = null
            }
        )

        ApiKeyInputField(
            value = deepSeekInput,
            onValueChange = { deepSeekInput = it },
            label = "DeepSeek API Key",
            placeholder = "",
            isVisible = showDeepSeekKey,
            onToggleVisibility = { showDeepSeekKey = !showDeepSeekKey },
            onClear = { deepSeekInput = "" }
        )

        ApiKeyInputField(
            value = kimiInput,
            onValueChange = { kimiInput = it },
            label = "Kimi API Key",
            placeholder = "",
            isVisible = showKimiKey,
            onToggleVisibility = { showKimiKey = !showKimiKey },
            onClear = { kimiInput = "" }
        )

        Text(
            text = "Sağlayıcı",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            AiProviderChoice.entries.forEach { provider ->
                val isSelected = selectedProvider == provider
                FilterChip(
                    selected = isSelected,
                    onClick = { selectedProvider = provider },
                    label = {
                        Text(
                            text = provider.label,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1
                        )
                    },
                    shape = RoundedCornerShape(10.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }
        }

        Text(
            text = "Model Modu",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            PreferredPowerMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = selectedPowerMode == mode,
                    onClick = { selectedPowerMode = mode },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = PreferredPowerMode.entries.size
                    )
                ) {
                    Text(mode.label, fontSize = 12.sp)
                }
            }
        }

        Button(
            onClick = {
                userAiPrefs.saveSettings(
                    geminiKeys = geminiKeysInput,
                    deepSeekKey = deepSeekInput,
                    kimiKey = kimiInput,
                    provider = selectedProvider,
                    powerMode = selectedPowerMode
                )
                ApiKeyRotator.invalidateAndReset()
                scope.launch {
                    snackbarHostState.showSnackbar("AI ayarları kaydedildi")
                }
            },
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Kaydet", fontWeight = FontWeight.Bold)
        }

        OutlinedButton(
            onClick = {
                if (isTestingConnection) return@OutlinedButton
                isTestingConnection = true
                connectionTestResult = null
                scope.launch {
                    val result = GeminiRestClient.testApiConnection(
                        context = context,
                        candidateUserKeysRaw = geminiKeysInput
                    )
                    connectionTestResult = result
                    isTestingConnection = false
                }
            },
            enabled = !isTestingConnection,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isTestingConnection) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Bağlantı Test Ediliyor (${GeminiRestClient.MODEL_ID})...")
            } else {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Bağlantıyı Test Et", fontWeight = FontWeight.SemiBold)
            }
        }

        connectionTestResult?.let { result ->
            val containerColor = if (result.success) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
            } else {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f)
            }
            val contentColor = if (result.success) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onErrorContainer
            }
            Surface(
                color = containerColor,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = result.summary,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = contentColor
                    )
                    result.details.forEach { detailLine ->
                        Text(
                            text = "• $detailLine",
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor.copy(alpha = 0.9f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ApiKeyInputField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    isVisible: Boolean,
    onToggleVisibility: () -> Unit,
    onClear: () -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        placeholder = if (placeholder.isNotBlank()) {
            { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        } else null,
        singleLine = true,
        visualTransformation = if (isVisible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onToggleVisibility,
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = if (isVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (isVisible) "Gizle" else "Göster",
                        modifier = Modifier.size(18.dp)
                    )
                }
                if (value.isNotEmpty()) {
                    IconButton(
                        onClick = onClear,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Temizle",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        },
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
fun ThemeSelectorSection(
    themeMode: AppThemeMode,
    onThemeModeToggle: (AppThemeMode) -> Unit,
    currentPalette: ThemePalette,
    onSelectPalette: (ThemePalette) -> Unit
) {
    Text("Görünüm & Tema", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    Spacer(modifier = Modifier.height(4.dp))

    Text("Tema Modu", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    Spacer(modifier = Modifier.height(6.dp))

    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = themeMode == AppThemeMode.SYSTEM,
            onClick = { onThemeModeToggle(AppThemeMode.SYSTEM) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3)
        ) {
            Text("Sistem", fontSize = 12.sp)
        }
        SegmentedButton(
            selected = themeMode == AppThemeMode.DARK,
            onClick = { onThemeModeToggle(AppThemeMode.DARK) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3)
        ) {
            Text("Koyu", fontSize = 12.sp)
        }
        SegmentedButton(
            selected = themeMode == AppThemeMode.LIGHT,
            onClick = { onThemeModeToggle(AppThemeMode.LIGHT) },
            shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3)
        ) {
            Text("Açık", fontSize = 12.sp)
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    Text("Renk Paleti", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    Spacer(modifier = Modifier.height(6.dp))

    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        items(ThemePalette.entries.toTypedArray()) { palette ->
            val isSelected = currentPalette == palette
            Card(
                modifier = Modifier
                    .width(120.dp)
                    .clickable { onSelectPalette(palette) },
                colors = CardDefaults.cardColors(containerColor = Color(palette.bgHex)),
                border = BorderStroke(2.dp, if (isSelected) Color(palette.primaryHex) else Color.Transparent),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Box(modifier = Modifier.size(18.dp).background(Color(palette.primaryHex), CircleShape))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(palette.title, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                    Text(palette.desc, color = Color.Gray, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun SettingRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(16.dp))
            Text(label, style = MaterialTheme.typography.bodyLarge)
        }
        Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
    }
}

fun executePermanentAccountDeletion(context: Context, onComplete: () -> Unit) {
    val auth = GoogleServicesHelper.getFirebaseAuth(context)
    val user = GoogleServicesHelper.getCurrentUser(auth, context) ?: return
    val uid = user.uid
    val db = GoogleServicesHelper.getFirebaseFirestore(context)

    AlertDialog.Builder(context)
        .setTitle("Hesabı Kalıcı Sil")
        .setMessage("Tüm verileriniz kalıcı olarak silinecektir.")
        .setPositiveButton("Kalıcı Sil") { _, _ ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    SharkMentorDatabase.getDatabase(context).clearAllTables()
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                try {
                    db?.collection("students")?.document(uid)?.delete()?.await()
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                withContext(Dispatchers.Main) {
                    user.delete()
                        .addOnSuccessListener {
                            auth?.signOut()
                            Toast.makeText(context, "Hesap silindi", Toast.LENGTH_SHORT).show()
                            onComplete()
                        }
                        .addOnFailureListener { e ->
                            Toast.makeText(
                                context,
                                "Tekrar giriş yapın: ${e.localizedMessage}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                }
            }
        }
        .setNegativeButton("İptal", null)
        .show()
}

@Composable
fun AppIconItem(
    iconRes: Int,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val context = LocalContext.current

    // Safely extract drawable to Bitmap or load Vector
    val drawable = remember(iconRes) {
        try {
            ContextCompat.getDrawable(context, iconRes)
        } catch (e: Exception) {
            null
        }
    }

    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(12.dp),
        border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            if (drawable != null) {
                // Render via AndroidView or convert to BitmapPainter to avoid Compose PainterResources crash
                Image(
                    bitmap = remember(drawable) {
                        val bitmap = Bitmap.createBitmap(
                            drawable.intrinsicWidth.coerceAtLeast(1),
                            drawable.intrinsicHeight.coerceAtLeast(1),
                            Bitmap.Config.ARGB_8888
                        )
                        val canvas = Canvas(bitmap)
                        drawable.setBounds(0, 0, canvas.width, canvas.height)
                        drawable.draw(canvas)
                        bitmap.asImageBitmap()
                    },
                    contentDescription = "App Icon",
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(Icons.Default.Apps, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
fun AppIconSelectorSection(snackbarHostState: SnackbarHostState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedIcon by remember {
        mutableStateOf(AppIconManager.getActiveIcon(context))
    }

    Text(
        "Uygulama Simgesi",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary
    )
    Spacer(modifier = Modifier.height(10.dp))

    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        items(SharkAppIcon.entries.toTypedArray()) { item ->
            val isSelected = selectedIcon == item

            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AppIconItem(
                    iconRes = item.iconRes,
                    isSelected = isSelected,
                    onClick = {
                        AppIconManager.setActiveIcon(context, item)
                        selectedIcon = item
                        scope.launch {
                            snackbarHostState.showSnackbar("Simge güncellendi")
                        }
                    }
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
            }
        }
    }
}
