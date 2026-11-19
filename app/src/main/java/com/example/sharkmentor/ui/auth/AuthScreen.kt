package com.example.sharkmentor.ui.auth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sharkmentor.core.util.GoogleServicesHelper
import com.example.sharkmentor.data.local.preferences.UserPreferences
import com.google.firebase.auth.userProfileChangeRequest
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions

@Composable
fun AuthScreen(
    onAuthSuccess: () -> Unit,
    onGuestContinue: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var isRegisterMode by remember { mutableStateOf(false) }
    var fullName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isPasswordVisible by remember { mutableStateOf(false) }
    var grade by remember { mutableStateOf("") }
    var targetExam by remember { mutableStateOf("") }

    var errorMessage by remember { mutableStateOf<String?>(null) }
    var submitted by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }

    val focusManager = LocalFocusManager.current
    val scrollState = rememberScrollState()

    val nameHasError = submitted && isRegisterMode && fullName.trim().isBlank()
    val emailHasError = submitted && email.trim().isBlank()
    val passwordHasError = submitted && password.trim().isBlank()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding()
            .verticalScroll(scrollState)
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        // ── Brand Header ─────────────────────────────────────────────
        Surface(
            modifier = Modifier.size(64.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.Bolt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "SharkMentor",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Text(
            text = if (isRegisterMode) "Yeni Hesap Oluştur" else "Odaklanmaya Başla",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
        )

        Spacer(modifier = Modifier.height(32.dp))

        // ── Form Inputs ──────────────────────────────────────────────
        AnimatedVisibility(
            visible = isRegisterMode,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column {
                OutlinedTextField(
                    value = fullName,
                    onValueChange = {
                        fullName = it
                        errorMessage = null
                    },
                    label = { Text("Ad Soyad") },
                    leadingIcon = {
                        Icon(Icons.Outlined.Person, contentDescription = null, modifier = Modifier.size(20.dp))
                    },
                    isError = nameHasError,
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
        }

        OutlinedTextField(
            value = email,
            onValueChange = {
                email = it
                errorMessage = null
            },
            label = { Text("E-posta") },
            leadingIcon = {
                Icon(Icons.Outlined.Email, contentDescription = null, modifier = Modifier.size(20.dp))
            },
            isError = emailHasError,
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = password,
            onValueChange = {
                password = it
                errorMessage = null
            },
            label = { Text("Şifre") },
            leadingIcon = {
                Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(20.dp))
            },
            trailingIcon = {
                IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                    Icon(
                        imageVector = if (isPasswordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = if (isPasswordVisible) "Şifreyi gizle" else "Şifreyi göster",
                        modifier = Modifier.size(20.dp)
                    )
                }
            },
            visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            isError = passwordHasError,
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = if (isRegisterMode) ImeAction.Next else ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = { focusManager.clearFocus() }
            ),
            modifier = Modifier.fillMaxWidth()
        )

        AnimatedVisibility(
            visible = isRegisterMode,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column {
                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = grade,
                    onValueChange = {
                        grade = it
                        errorMessage = null
                    },
                    label = { Text("Sınıf / Seviye (İsteğe Bağlı)") },
                    placeholder = { Text("Örn: 12. Sınıf, Mezun") },
                    leadingIcon = {
                        Icon(Icons.Outlined.School, contentDescription = null, modifier = Modifier.size(20.dp))
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = targetExam,
                    onValueChange = {
                        targetExam = it
                        errorMessage = null
                    },
                    label = { Text("Hedef Sınav (İsteğe Bağlı)") },
                    placeholder = { Text("Örn: YKS Sayısal, LGS") },
                    leadingIcon = {
                        Icon(Icons.Outlined.Flag, contentDescription = null, modifier = Modifier.size(20.dp))
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = { focusManager.clearFocus() }
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // ── Error Banner ─────────────────────────────────────────────
        AnimatedVisibility(
            visible = errorMessage != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            errorMessage?.let { msg ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Outlined.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = msg,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ── Primary Action Button ────────────────────────────────────
        Button(
            onClick = {
                submitted = true
                errorMessage = null
                val cleanEmail = email.trim()
                val cleanPass = password.trim()
                val cleanName = fullName.trim()

                if (cleanEmail.isBlank() || cleanPass.isBlank() || (isRegisterMode && cleanName.isBlank())) {
                    errorMessage = "Lütfen gerekli alanları doldurun."
                    return@Button
                }

                val authObj = GoogleServicesHelper.getFirebaseAuth()
                val dbObj = GoogleServicesHelper.getFirebaseFirestore()
                if (authObj == null || dbObj == null) {
                    errorMessage = "Google Play Servisleri / Veritabanı erişilemedi."
                    return@Button
                }

                isLoading = true
                try {
                    if (isRegisterMode) {
                        authObj.createUserWithEmailAndPassword(cleanEmail, cleanPass)
                            .addOnSuccessListener { res ->
                                val user = res.user
                                val uid = user?.uid ?: return@addOnSuccessListener
                                
                                user.updateProfile(userProfileChangeRequest { displayName = cleanName })

                                val cleanGrade = grade.trim()
                                val cleanExam = targetExam.trim()
                                UserPreferences.getInstance(context).updateProfileDetails(
                                    name = cleanName,
                                    grade = cleanGrade,
                                    targetExam = cleanExam
                                )
                                if (cleanExam.isNotBlank()) {
                                    context.getSharedPreferences("sharkmentor_prefs", android.content.Context.MODE_PRIVATE)
                                        .edit()
                                        .putString("target_exam_title", cleanExam)
                                        .apply()
                                }

                                val profile = hashMapOf<String, Any>(
                                    "name" to cleanName,
                                    "displayName" to cleanName,
                                    "email" to cleanEmail,
                                    "grade" to cleanGrade,
                                    "targetExam" to cleanExam,
                                    "createdAt" to FieldValue.serverTimestamp(),
                                    "isStudying" to false
                                )
                                if (cleanExam.isNotBlank()) {
                                    profile["targetExamTitle"] = cleanExam
                                }
                                dbObj.collection("users").document(uid).set(
                                    hashMapOf(
                                        "uid" to uid,
                                        "name" to cleanName,
                                        "email" to cleanEmail,
                                        "grade" to cleanGrade,
                                        "targetExam" to cleanExam,
                                        "role" to "STUDENT"
                                    ),
                                    SetOptions.merge()
                                )
                                dbObj.collection("students").document(uid).set(profile, SetOptions.merge())
                                    .addOnSuccessListener {
                                        isLoading = false
                                        onAuthSuccess()
                                    }
                                    .addOnFailureListener {
                                        isLoading = false
                                        onAuthSuccess()
                                    }
                            }
                            .addOnFailureListener { e ->
                                isLoading = false
                                errorMessage = e.localizedMessage ?: "Kayıt başarısız."
                            }
                    } else {
                        authObj.signInWithEmailAndPassword(cleanEmail, cleanPass)
                            .addOnSuccessListener {
                                isLoading = false
                                onAuthSuccess()
                            }
                            .addOnFailureListener { e ->
                                isLoading = false
                                errorMessage = e.localizedMessage ?: "Giriş yapılamadı."
                            }
                    }
                } catch (e: Throwable) {
                    isLoading = false
                    errorMessage = e.localizedMessage ?: "Giriş/Kayıt hatası."
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(16.dp),
            enabled = !isLoading
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
            } else {
                Text(
                    text = if (isRegisterMode) "Kayıt Ol ve Başla" else "Giriş Yap",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ── Mode Switch ──────────────────────────────────────────────
        TextButton(onClick = {
            isRegisterMode = !isRegisterMode
            errorMessage = null
            submitted = false
        }) {
            Text(
                if (isRegisterMode) "Zaten bir hesabın var mı? Giriş Yap"
                else "Hesabın yok mu? Yeni Hesap Oluştur",
                color = MaterialTheme.colorScheme.primary
            )
        }

        // ── Minimalist Guest Mode (Frictionless Onboarding) ──────────
        if (onGuestContinue != null) {
            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Text(
                    text = "veya",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(
                onClick = onGuestContinue,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            ) {
                Icon(
                    imageVector = Icons.Outlined.Bolt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Giriş Yapmadan Odaklan (Misafir Modu)",
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
