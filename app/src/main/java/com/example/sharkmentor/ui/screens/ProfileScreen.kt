package com.example.sharkmentor.ui.screens

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Grade
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sharkmentor.core.util.AppIconManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.sharkmentor.core.util.GoogleServicesHelper
import com.example.sharkmentor.core.util.SessionManager
import com.example.sharkmentor.core.util.SharkVisualKit
import com.example.sharkmentor.data.repository.CoachCodeRepository
import com.example.sharkmentor.ui.screens.profile.ProfileViewModel
import java.text.SimpleDateFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel = viewModel(),
    onBackClick: () -> Unit,
    onLogoutClick: () -> Unit,
    onLoginClick: () -> Unit = onLogoutClick
) {
    val context = LocalContext.current
    val user = GoogleServicesHelper.getCurrentUser(context = context)
    val uid = user?.uid ?: "ID Alınamadı"
    val isGuest = user == null || user.isAnonymous

    var studentName by remember { mutableStateOf(user?.displayName ?: "") }
    var studentEmail by remember { mutableStateOf(user?.email ?: "") }
    var studentGrade by remember { mutableStateOf("12. Sınıf") }
    var studentTargetExam by remember { mutableStateOf("YKS 2027") }
    var coachName by remember { mutableStateOf("") }
    var institutionName by remember { mutableStateOf("") }
    var hasCoach by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }

    var inputCode by remember { mutableStateOf("") }
    var inputStudentName by remember { mutableStateOf("") }
    var isSubmitting by remember { mutableStateOf(false) }
    var linkError by remember { mutableStateOf("") }
    var showUnlinkConfirm by remember { mutableStateOf(false) }
    var showBookingSheet by remember { mutableStateOf(false) }

    val activeMeetings by viewModel.studentMeetings.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.bookingMessage.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    LaunchedEffect(uid) {
        if (user != null && !isGuest) {
            GoogleServicesHelper.getFirebaseFirestore(context)?.collection("students")?.document(uid)?.get()
                ?.addOnSuccessListener { snapshot ->
                    val nameInDb = snapshot.getString("name")
                    if (!nameInDb.isNullOrBlank()) {
                        studentName = nameInDb
                    }

                    val emailInDb = snapshot.getString("email")
                    if (!emailInDb.isNullOrBlank()) studentEmail = emailInDb

                    val gradeInDb = snapshot.getString("grade")
                    if (!gradeInDb.isNullOrBlank()) studentGrade = gradeInDb

                    val examInDb = snapshot.getString("targetExam")
                    if (!examInDb.isNullOrBlank()) studentTargetExam = examInDb

                    val instInDb = snapshot.getString("institutionName")
                    val coachCodeInDb = snapshot.getString("coachCode") ?: snapshot.getString("institutionCode")
                    
                    if (!instInDb.isNullOrBlank() || !coachCodeInDb.isNullOrBlank()) {
                        institutionName = instInDb ?: "Bağlı Kurum"
                        hasCoach = true
                        SessionManager.setLinkedToInstitution(context, true)
                                          SessionManager.setUserMode(context, "campus")
                    }

                    val coachId = snapshot.getString("coachId")
                    if (!coachId.isNullOrBlank()) {
                        GoogleServicesHelper.getFirebaseFirestore(context)?.collection("coaches")?.document(coachId)?.get()
                            ?.addOnSuccessListener { cDoc ->
                                coachName = cDoc.getString("coachName") ?: "Koç"
                                if (institutionName.isBlank() || institutionName == "Bağlı Kurum") {
                                    institutionName = cDoc.getString("institutionName") ?: "Kurum"
                                }
                                hasCoach = true
                                SessionManager.setLinkedToInstitution(context, true)
                                          SessionManager.setUserMode(context, "campus")
                                isLoading = false
                            }
                            ?.addOnFailureListener { isLoading = false }
                    } else {
                        isLoading = false
                    }
                }
                ?.addOnFailureListener { isLoading = false }
        } else {
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Profil ve Hesap") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Geri")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        val displayEmail = if (isGuest) {
            "Misafir Oturumu (E-posta Bağlı Değil)"
        } else {
            studentEmail.ifBlank { "E-posta Belirtilmemiş" }
        }

        val activeShark = remember(context) { AppIconManager.getActiveIcon(context) }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // User Info Card
            item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                painter = painterResource(id = SharkVisualKit.getBreedDrawable(activeShark.key)),
                                contentDescription = activeShark.title,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                        Column {
                            Text(
                                text = if (isGuest) "Misafir Öğrenci" else studentName.ifBlank { "Kayıtlı Öğrenci" },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (isGuest) "Geçici Oturum" else "Doğrulanmış Öğrenci Hesabı",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // Email Row
                    ProfileDetailRow(
                        icon = Icons.Default.Email,
                        label = "E-Posta Adresi",
                        value = displayEmail
                    )

                    // Grade Row
                    ProfileDetailRow(
                        icon = Icons.Default.Grade,
                        label = "Sınıf Seviyesi",
                        value = studentGrade
                    )

                    // Target Exam Row
                    ProfileDetailRow(
                        icon = Icons.Default.School,
                        label = "Hedef Sınav",
                        value = studentTargetExam
                    )

                    // Institution / Coach Status Row
                    ProfileDetailRow(
                        icon = Icons.Default.Business,
                        label = "Kurum & Koç Bağlantı Durumu",
                        value = if (hasCoach) "$institutionName ${if (coachName.isNotBlank()) "(Koç: $coachName)" else ""}" else "Bireysel Öğrenci (Study Modu)"
                    )

                    // UID Row
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Fingerprint,
                            contentDescription = "Kullanıcı ID",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Column {
                            Text("Kullanıcı ID (UID):", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            SelectionContainer {
                                Text(
                                    text = uid,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }
            } // end User Info Card item

            // ── Kurum & Koç Bağlantısı Form Section ──────────────────────────────────
            if (!isGuest) {
                if (!hasCoach) {
                    item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "Kurum & Koç Bağlantısı",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Bir kurum veya eğitim koçuyla mı çalışıyorsun? Kodunu girerek bağlantını aktifleştir.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            OutlinedTextField(
                                value = inputCode,
                                onValueChange = { inputCode = it },
                                label = { Text("Kurum / Koç Kodu") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                shape = RoundedCornerShape(10.dp)
                            )

                            OutlinedTextField(
                                value = inputStudentName,
                                onValueChange = { inputStudentName = it },
                                label = { Text("Öğrenci Adı Soyadı") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                shape = RoundedCornerShape(10.dp)
                            )

                            if (linkError.isNotBlank()) {
                                Text(linkError, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                            }

                            Button(
                                onClick = {
                                    if (inputCode.isBlank()) {
                                        linkError = "Lütfen geçerli bir kurum/koç kodu girin."
                                        return@Button
                                    }
                                    isSubmitting = true
                                    linkError = ""
                                    val cleanCode = inputCode.trim()
                                    CoachCodeRepository(context).submitCoachCode(
                                        inputCode = cleanCode,
                                        onSuccess = {
                                            isSubmitting = false
                                            hasCoach = true
                                            institutionName = "Bağlı Kurum ($cleanCode)"
                                            Toast.makeText(context, "Koç ve kurum bağlantısı başarıyla sağlandı!", Toast.LENGTH_SHORT).show()
                                        },
                                        onError = { err ->
                                            isSubmitting = false
                                            linkError = err
                                        }
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(46.dp),
                                shape = RoundedCornerShape(10.dp),
                                enabled = !isSubmitting,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                if (isSubmitting) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Text("Bağlan", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                    } // end hasCoach form item
                } else {
                    item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Mevcut Bağlantı",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = "Aktif",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }

                            Text(
                                text = "$institutionName ${if (coachName.isNotBlank()) "\nKoç: $coachName" else ""}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )

                            Button(
                                onClick = { showUnlinkConfirm = true },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(42.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text(
                                    "Koçla Bağlantıyı Kes",
                                    color = MaterialTheme.colorScheme.error,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                    } // end hasCoach card item
                }
            }

            item {
            CloudBackupSection(
                onBackupClick = { /* TODO */ },
                onRestoreClick = { /* TODO */ },
                isSyncing = false,
                lastBackupDate = null
            )
            }

            item { Spacer(modifier = Modifier.height(8.dp)) }

            item {
            if (isGuest) {
                Button(
                    onClick = onLoginClick,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("E-posta ile Giriş Yap / Kayıt Ol", fontWeight = FontWeight.Bold)
                }
            } else {
                Button(
                    onClick = onLogoutClick,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Çıkış Yap", color = MaterialTheme.colorScheme.onError, fontWeight = FontWeight.Bold)
                }
            }
            } // end Login/Logout item
        }
    }


    if (showUnlinkConfirm) {
        AlertDialog(
            onDismissRequest = { showUnlinkConfirm = false },
            title = { Text("Bağlantıyı Sonlandır") },
            text = { Text("Koçunuzla olan bağlantınızı kesmek istediğinize emin misiniz? Campus modundan çıkarak bireysel Study moduna döneceksiniz.") },
            confirmButton = {
                Button(
                    onClick = {
                        showUnlinkConfirm = false
                        viewModel.unlinkCoach { _, _ -> }
                          hasCoach = false
                          institutionName = ""
                          coachName = ""
                        Toast.makeText(context, "Bağlantı kesildi.", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Sonlandır", color = MaterialTheme.colorScheme.onError)
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnlinkConfirm = false }) {
                    Text("İptal")
                }
            }
        )
    }

}

@Composable
private fun ProfileDetailRow(
    icon: ImageVector,
    label: String,
    value: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Column {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text = value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun CloudBackupSection(
    onBackupClick: () -> Unit,
    onRestoreClick: () -> Unit,
    isSyncing: Boolean,
    lastBackupDate: String?
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.CloudSync,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Bulut & Drive Yedekleme",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = lastBackupDate?.let { "Son Yedek: $it" } ?: "Henüz yedek alınmadı",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onBackupClick,
                    enabled = !isSyncing,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Drive'a Yedekle", maxLines = 1, fontSize = 12.sp)
                }

                OutlinedButton(
                    onClick = onRestoreClick,
                    enabled = !isSyncing,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Geri Yükle", maxLines = 1, fontSize = 12.sp)
                }
            }
        }
    }
}


