package com.example.sharkmentor.ui.screens

import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material.icons.filled.Apps
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.sharkmentor.core.data.AppThemeMode
import com.example.sharkmentor.core.data.ThemePalette
import com.example.sharkmentor.core.data.ThemePreferences
import com.example.sharkmentor.core.util.AppIconManager
import com.example.sharkmentor.core.util.GoogleServicesHelper
import com.example.sharkmentor.core.util.SharkAppIcon
import com.example.sharkmentor.core.util.SharkVisualKit
import com.example.sharkmentor.data.local.SharkMentorDatabase
import com.example.sharkmentor.ui.components.ReflectionTimeDialog
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
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

            Text("Odaklanma & Zihin Modülleri", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)

            SettingRow(
                icon = Icons.Default.SelfImprovement,
                label = "Zen Odası & Nefes Egzersizi",
                onClick = onOpenZenRoom
            )
            
            SettingRow(
                icon = Icons.Default.Block,
                label = "Uygulama Engelleyici (App Blocker)",
                onClick = onOpenAppBlocker
            )

            HorizontalDivider()

            Text("Analiz & Geçmiş", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            
            SettingRow(
                icon = Icons.Default.Analytics,
                label = "Detaylı Analiz & Raporlar",
                onClick = onOpenAnalysis
            )
            
            SettingRow(
                icon = Icons.Default.History,
                label = "Çalışma Geçmişi",
                onClick = onOpenHistory
            )

            HorizontalDivider()

            Text("Hesap Yönetimi (KVKK / GDPR)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)

            Button(
                onClick = { executePermanentAccountDeletion(context, onAccountDeleted) },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.DeleteForever, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Hesabımı ve Tüm Verilerimi Kalıcı Olarak Sil", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun ThemeSelectorSection(
    themeMode: AppThemeMode,
    onThemeModeToggle: (AppThemeMode) -> Unit,
    currentPalette: ThemePalette,
    onSelectPalette: (ThemePalette) -> Unit
) {
    Text("Arayüz Teması & Görünüm", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
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
        .setTitle("⚠️ Hesabınızı Kalıcı Olarak Silmek İstiyor Musunuz?")
        .setMessage("Tüm çalışma oturumlarınız, deneme sınavı kayıtlarınız ve koç eşleşmeleriniz geri dönülemez şekilde silinecektir. Bu işlem geri alınamaz.")
        .setPositiveButton("Kalıcı Olarak Sil") { _, _ ->
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
                            Toast.makeText(context, "Hesabınız başarıyla silindi.", Toast.LENGTH_LONG).show()
                            onComplete()
                        }
                        .addOnFailureListener { e ->
                            Toast.makeText(
                                context,
                                "Güvenlik politikası gereği tekrar oturum açmanız gerekmektedir: ${e.localizedMessage}",
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
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        "Launcher simgenizi kişiselleştirin",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
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
                            snackbarHostState.showSnackbar("${item.title} simgesi aktif edildi.")
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
