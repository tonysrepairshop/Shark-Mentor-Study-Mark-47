package com.example.sharkmentor.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.sharkmentor.core.audio.ZenAudioManager
import com.example.sharkmentor.core.audio.ZenSound
import com.example.sharkmentor.core.data.AppThemeMode
import com.example.sharkmentor.core.data.ThemePalette
import com.example.sharkmentor.core.theme.ThemeManager
import com.example.sharkmentor.core.util.GoogleServicesHelper
import com.example.sharkmentor.core.util.SessionManager
import com.example.sharkmentor.ui.navigation.Screen
import com.google.firebase.auth.FirebaseAuth
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.signin.GoogleSignIn
import kotlinx.coroutines.launch
import android.widget.Toast
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.CloudDownload
import com.example.sharkmentor.core.backup.GoogleDriveBackupManager
import com.example.sharkmentor.core.backup.TrainingBackupPayload
import com.example.sharkmentor.data.local.SharkMentorDatabase
import com.example.sharkmentor.data.local.entity.TrainingFolderEntity
import com.example.sharkmentor.data.local.entity.TrainingQuestionEntity
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

@Composable
fun ControlPanelContent(
    themeMode      : AppThemeMode,
    onThemeToggle  : (AppThemeMode) -> Unit,
    currentPalette : ThemePalette,
    onPaletteChange: (ThemePalette) -> Unit,
    onDismiss      : () -> Unit,
    currentRoute   : String?,
    onNavigate     : (String) -> Unit,
) {
    var showAboutDialog by remember { mutableStateOf(false) }
    val user = GoogleServicesHelper.getCurrentUser()
    val displayName = user?.displayName?.ifBlank { null } ?: "Öğrenci Profilim"
    
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val backupManager = remember { GoogleDriveBackupManager(context) }
    var isBackupFlow by remember { mutableStateOf(true) }

    val driveConsentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            Toast.makeText(context, "Drive izni verildi. Lütfen işlemi tekrar başlatın.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Drive izni reddedildi.", Toast.LENGTH_SHORT).show()
        }
    }

    val driveSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(com.google.android.gms.common.api.ApiException::class.java)
            if (account != null) {
                if (!GoogleSignIn.hasPermissions(account, backupManager.driveScope)) {
                    Toast.makeText(context, "Lütfen Google Drive erişim iznini onaylayın.", Toast.LENGTH_LONG).show()
                    
                    var activityContext: Context = context
                    while (activityContext is ContextWrapper) {
                        if (activityContext is Activity) break
                        activityContext = activityContext.baseContext
                    }
                    
                    if (activityContext is Activity) {
                        GoogleSignIn.requestPermissions(
                            activityContext,
                            1001,
                            account,
                            backupManager.driveScope
                        )
                    }
                    return@rememberLauncherForActivityResult
                }
                
                scope.launch {
                    val db = SharkMentorDatabase.getDatabase(context)
                    val folderDao = db.trainingFolderDao()
                    val questionDao = db.trainingQuestionDao()
                    val gson = Gson()
                    
                    if (isBackupFlow) {
                        Toast.makeText(context, "Yedekleme başlatıldı...", Toast.LENGTH_SHORT).show()
                        val folders = folderDao.getAll()
                        val questions = questionDao.getAllQuestions()
                        val payload = TrainingBackupPayload(
                            foldersJson = gson.toJson(folders),
                            questionsJson = gson.toJson(questions)
                        )
                        backupManager.uploadBackupToDrive(account, payload).onSuccess {
                            Toast.makeText(context, "Google Drive yedeği başarıyla alındı!", Toast.LENGTH_LONG).show()
                        }.onFailure { e ->
                            if (e is com.example.sharkmentor.core.backup.DriveConsentRequiredException) {
                                driveConsentLauncher.launch(e.consentIntent)
                            } else {
                                Toast.makeText(context, "Drive Hatası: ${e.localizedMessage ?: "Bilinmeyen hata"}", Toast.LENGTH_LONG).show()
                            }
                        }
                    } else {
                        Toast.makeText(context, "Geri yükleme başlatıldı...", Toast.LENGTH_SHORT).show()
                        backupManager.downloadBackupFromDrive(account).onSuccess { payload ->
                            val folderListType = object : TypeToken<List<TrainingFolderEntity>>() {}.type
                            val questionListType = object : TypeToken<List<TrainingQuestionEntity>>() {}.type
                            
                            val folders: List<TrainingFolderEntity> = gson.fromJson(payload.foldersJson, folderListType) ?: emptyList()
                            val questions: List<TrainingQuestionEntity> = gson.fromJson(payload.questionsJson, questionListType) ?: emptyList()
                            
                            folderDao.insertAll(folders)
                            questionDao.insertAll(questions)
                            
                            Toast.makeText(context, "Drive'dan geri yükleme başarılı!", Toast.LENGTH_SHORT).show()
                        }.onFailure { e ->
                            if (e is com.example.sharkmentor.core.backup.DriveConsentRequiredException) {
                                driveConsentLauncher.launch(e.consentIntent)
                            } else {
                                Toast.makeText(context, "Drive Hatası: ${e.localizedMessage ?: "Bilinmeyen hata"}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Google Drive auth hatası: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    if (showAboutDialog) {
        AboutAppDialog(onDismiss = { showAboutDialog = false })
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Menü", color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Kapat", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
        }

        Spacer(Modifier.height(12.dp))

        // Profile Mini Card Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onNavigate("profile") }
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(text = displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text(text = "Hesap Detayları >", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        
        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Spacer(Modifier.height(8.dp))

        DrawerActionRow(
            icon    = Icons.Default.Person,
            label   = "Profil & Hesap Detayları",
            onClick = { onNavigate("profile") },
        )

        val isLinked by SessionManager.isLinkedToInstitution.collectAsStateWithLifecycle()

        if (isLinked) {
            DrawerActionRow(
                icon    = Icons.Default.Business,
                label   = "Kurum Bilgisi & Koç Bağlantısı",
                onClick = { onNavigate(Screen.InstitutionInfo.route) },
            )
            DrawerActionRow(
                icon    = Icons.Default.Forum,
                label   = "Koçunla Mesajlaş",
                onClick = { onNavigate(Screen.CoachChat.route) },
            )
        }

        DrawerActionRow(
            icon    = Icons.Default.SelfImprovement,
            label   = "Zen Odası & Zihin Tazeleyici",
            onClick = { onNavigate("zen_room") },
        )
        
        DrawerActionRow(
            icon    = Icons.Default.Settings,
            label   = "Ayarlar",
            onClick = { onNavigate("settings") },
        )

        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Spacer(Modifier.height(8.dp))



        Spacer(Modifier.weight(1f))
        
        // Embedded Zen Controls in Navigation Drawer
        DrawerZenControlsSection()

        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Spacer(Modifier.height(8.dp))

        DrawerActionRow(
            icon    = Icons.Default.Logout,
            label   = "Çıkış Yap",
            onClick = { 
                GoogleServicesHelper.getFirebaseAuth()?.signOut()
                onNavigate(Screen.Login.route)
            },
        )

        Spacer(Modifier.height(8.dp))

        // Info Button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { showAboutDialog = true },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = "Uygulama Hakkında",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
fun DrawerZenControlsSection(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isZenMode by ThemeManager.isZenMode.collectAsStateWithLifecycle()
    val currentSound by ZenAudioManager.currentSound.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        // ZEN MODU TOGGLE ROW
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Zen Modu",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = if (isZenMode) Color.White else Color(0xFFE2E8F0),
                    letterSpacing = 0.5.sp
                )
                Text(
                    text = "Monokrom ve sıfır gecikme",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF718096),
                    fontSize = 11.sp
                )
            }

            Switch(
                checked = isZenMode,
                onCheckedChange = { ThemeManager.toggleZenMode(context) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.Black,
                    checkedTrackColor = Color.White,
                    uncheckedThumbColor = Color(0xFF4A5568),
                    uncheckedTrackColor = Color(0xFF1A202C),
                    uncheckedBorderColor = Color(0xFF2D3748),
                    checkedBorderColor = Color.White
                )
            )
        }

        Spacer(modifier = Modifier.height(10.dp))
        HorizontalDivider(color = Color(0xFF1E2638), thickness = 1.dp)
        Spacer(modifier = Modifier.height(10.dp))

        // AMBIENCE SOUND SELECTOR
        Text(
            text = "Ambiyans Sesleri",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF718096),
            letterSpacing = 1.sp,
            fontSize = 11.sp
        )

        Spacer(modifier = Modifier.height(10.dp))

        val sounds = ZenSound.entries.filter { it != ZenSound.NONE }

        // 2 Rows x 3 Columns Matrix
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            sounds.chunked(3).forEach { rowSounds ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    rowSounds.forEach { sound ->
                        val isSelected = currentSound == sound

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .border(
                                    width = 1.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .background(
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable { ZenAudioManager.toggleSound(context, sound) }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = sound.displayName,
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawerActionRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
    }
}
