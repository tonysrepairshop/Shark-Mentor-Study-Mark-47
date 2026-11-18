package com.example.sharkmentor.ui.zen.dopamine

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.sharkmentor.domain.util.DopaminePermissionHelper
import kotlinx.coroutines.launch

/**
 * Self-contained "Dopamin Orucu" card that lives at the
 * bottom of [ZenRoomScreen]. It surfaces:
 *
 *  1. Toggle -> KVKK disclosure -> 6-digit PIN setup -> permission check -> activation
 *  2. Active state card with remaining-days counter
 *  3. 24-hour cooling-off banner with cancel option
 *  4. Evening Reflection bottom-sheet powered by Gemini 3.6 Flash
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DopamineProtocolSection(
    viewModel: DopamineProtocolViewModel = viewModel()
) {
    val context = LocalContext.current
    val state by viewModel.protocolState.collectAsStateWithLifecycle()

    // Dialog visibility flags
    var showTermsDialog            by remember { mutableStateOf(false) }
    var showPinSetupDialog          by remember { mutableStateOf(false) }
    var showUnlockDialog            by remember { mutableStateOf(false) }
    var showUsagePermissionDialog   by remember { mutableStateOf(false) }
    var showOverlayPermissionDialog by remember { mutableStateOf(false) }
    var pendingPin                  by remember { mutableStateOf("") }

    val primaryColor = MaterialTheme.colorScheme.primary
    val onPrimaryColor = MaterialTheme.colorScheme.onPrimary
    val surfaceColor = MaterialTheme.colorScheme.surface
    val surfaceVariantColor = MaterialTheme.colorScheme.surfaceVariant
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariantColor = MaterialTheme.colorScheme.onSurfaceVariant
    val errorColor = MaterialTheme.colorScheme.error
    val errorContainerColor = MaterialTheme.colorScheme.errorContainer
    val onErrorContainerColor = MaterialTheme.colorScheme.onErrorContainer

    // ── Main toggle card ─────────────────────────────────────────────────────
    Card(
        colors = CardDefaults.cardColors(containerColor = surfaceVariantColor),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Dopamin Orucu",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = primaryColor
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (state.isActive)
                            "Dopamin Orucu Aktif • ${state.remainingDays} gün kaldı"
                        else
                            "30 Günlük Nörolojik Odak ve Sıfırlama Modu",
                        fontSize = 12.sp,
                        color = if (state.isActive) MaterialTheme.colorScheme.tertiary else onSurfaceVariantColor
                    )
                }

                Switch(
                    checked = state.isActive,
                    onCheckedChange = { enabling ->
                        if (enabling) showTermsDialog = true else showUnlockDialog = true
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor  = onPrimaryColor,
                        checkedTrackColor  = primaryColor,
                        uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                        uncheckedTrackColor = surfaceVariantColor
                    )
                )
            }

            // ── Active extras ────────────────────────────────────────────────
            if (state.isActive) {
                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(12.dp))

                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Nöral Detoks & Dopamin Protokolü Aktif",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = primaryColor
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Dopamin orucu protokolü devrede. Odak disiplininizi koruyarak çalışmaya devam edin.",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = onSurfaceColor
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))

                // 24-h cooling-off banner
                if (state.is24hCoolingOff) {
                    Surface(
                        color = errorContainerColor,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Soğuma Sayacı: ${state.coolingOffHoursLeft} saat sonra açılacak.",
                                color = onErrorContainerColor,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { viewModel.cancelUnlockRequest() }) {
                                Text("İptal Et", color = primaryColor, fontSize = 12.sp)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }
            }
        }
    }

    // ── 1. KVKK & Terms dialog ───────────────────────────────────────────────
    if (showTermsDialog) {
        var kvkkAccepted by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showTermsDialog = false },
            containerColor = surfaceColor,
            title = {
                Text(
                    "Dopamin Orucu",
                    color = onSurfaceColor,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Bu protokol 30 gün boyunca prefrontal korteks odak kapasitenizi " +
                               "optimize etmek üzere tasarlanmıştır:\n\n" +
                               "• Masa kalkanı öncesi 3 dakikalık sıfır uyarım nefes kapısı zorunlu tutulur.\n" +
                               "• Masa oturumlarında mola süresi uzatılamaz.\n" +
                               "• Telefonunuzun günlük genel kullanımına müdahale edilmez.\n" +
                               "• Akşam refleksiyon verileriniz istemci tarafında şifrelenir; " +
                               "üçüncü şahıslar veya model eğitimiyle paylaşılmaz.",
                        color = onSurfaceVariantColor,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Checkbox(
                            checked = kvkkAccepted,
                            onCheckedChange = { kvkkAccepted = it },
                            colors = CheckboxDefaults.colors(checkedColor = primaryColor)
                        )
                        Text(
                            text = "Şartları ve KVKK aydınlatma metnini onaylıyorum.",
                            color = onSurfaceColor,
                            fontSize = 12.sp
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showTermsDialog = false
                        showPinSetupDialog = true
                    },
                    enabled = kvkkAccepted,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = primaryColor,
                        contentColor = onPrimaryColor
                    )
                ) {
                    Text("Devam Et", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showTermsDialog = false }) {
                    Text("Vazgeç", color = onSurfaceVariantColor)
                }
            }
        )
    }

    // ── 2. PIN setup dialog ──────────────────────────────────────────────────
    if (showPinSetupDialog) {
        var pin        by remember { mutableStateOf("") }
        var confirmPin by remember { mutableStateOf("") }
        var errorText  by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showPinSetupDialog = false },
            containerColor = surfaceColor,
            title = { Text("6 Haneli Güvenlik Kodu", color = onSurfaceColor) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Dopamin Orucunu yönetmek ve erken kilit açma talebinde bulunmak için " +
                               "6 haneli bir kod belirleyin.",
                        color = onSurfaceVariantColor,
                        fontSize = 12.sp
                    )
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { if (it.length <= 6) pin = it },
                        label = { Text("6 Haneli PIN") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor   = onSurfaceColor,
                            unfocusedTextColor = onSurfaceColor,
                            focusedBorderColor = primaryColor,
                            cursorColor = primaryColor
                        )
                    )
                    OutlinedTextField(
                        value = confirmPin,
                        onValueChange = { if (it.length <= 6) confirmPin = it },
                        label = { Text("PIN Tekrar") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor   = onSurfaceColor,
                            unfocusedTextColor = onSurfaceColor,
                            focusedBorderColor = primaryColor,
                            cursorColor = primaryColor
                        )
                    )
                    if (errorText.isNotEmpty()) {
                        Text(errorText, color = errorColor, fontSize = 11.sp)
                    }
                }
            },
            confirmButton = {
                val context = LocalContext.current
                Button(
                    onClick = {
                        when {
                            pin.length != 6   -> errorText = "PIN 6 haneli olmalıdır."
                            pin != confirmPin -> errorText = "PIN kodları eşleşmiyor."
                            else -> {
                                if (!DopaminePermissionHelper.hasUsageAccess(context)) {
                                    showUsagePermissionDialog = true
                                } else if (!DopaminePermissionHelper.hasOverlayPermission(context)) {
                                    showOverlayPermissionDialog = true
                                } else {
                                    viewModel.activateProtocol(pin)
                                    showPinSetupDialog = false
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = primaryColor,
                        contentColor = onPrimaryColor
                    )
                ) {
                    Text("Dopamin Orucunu Başlat", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPinSetupDialog = false }) {
                    Text("Vazgeç", color = onSurfaceVariantColor)
                }
            }
        )
    }


    // ── 5. Unlock / cooling-off request dialog ───────────────────────────────
    if (showUnlockDialog) {
        var inputPin      by remember { mutableStateOf("") }
        var errorMessage  by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showUnlockDialog = false },
            containerColor = surfaceColor,
            title = { Text("Dopamin Orucunu Sonlandır", color = onSurfaceColor) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Dopamin reseptörlerinin adaptasyonunu korumak amacıyla, " +
                               "PIN doğrulandıktan sonra 24 saatlik soğuma sayacı başlatılacaktır.",
                        color = onSurfaceVariantColor,
                        fontSize = 12.sp
                    )
                    OutlinedTextField(
                        value = inputPin,
                        onValueChange = { if (it.length <= 6) inputPin = it },
                        label = { Text("PIN Kodunuz") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor   = onSurfaceColor,
                            unfocusedTextColor = onSurfaceColor,
                            focusedBorderColor = primaryColor,
                            cursorColor = primaryColor
                        )
                    )
                    if (errorMessage.isNotEmpty()) {
                        Text(errorMessage, color = errorColor, fontSize = 11.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.requestUnlockWithPin(inputPin) { success, msg ->
                            if (success) showUnlockDialog = false else errorMessage = msg
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = errorColor,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text("Talebi Başlat", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnlockDialog = false }) {
                    Text("Vazgeç", color = onSurfaceVariantColor)
                }
            }
        )
    }

    if (showUsagePermissionDialog) {
        val context = LocalContext.current
        AlertDialog(
            onDismissRequest = { showUsagePermissionDialog = false },
            title = { Text("İzin Gerekli", color = onSurfaceColor) },
            text = { Text("Dopamin kalkanının çalışabilmesi için Kullanım Verilerine Erişim izni vermelisiniz.", color = onSurfaceVariantColor) },
            confirmButton = {
                Button(onClick = {
                    DopaminePermissionHelper.openUsageAccessSettings(context)
                    showUsagePermissionDialog = false
                }) {
                    Text("Ayarlara Git")
                }
            },
            dismissButton = {
                TextButton(onClick = { showUsagePermissionDialog = false }) {
                    Text("Vazgeç", color = onSurfaceVariantColor)
                }
            },
            containerColor = surfaceColor
        )
    }

    if (showOverlayPermissionDialog) {
        val context = LocalContext.current
        AlertDialog(
            onDismissRequest = { showOverlayPermissionDialog = false },
            title = { Text("İzin Gerekli", color = onSurfaceColor) },
            text = { Text("Kilit ekranının devreye girebilmesi için Diğer Uygulamaların Üzerinde Görüntüleme izni vermelisiniz.", color = onSurfaceVariantColor) },
            confirmButton = {
                Button(onClick = {
                    DopaminePermissionHelper.openOverlaySettings(context)
                    showOverlayPermissionDialog = false
                }) {
                    Text("Ayarlara Git")
                }
            },
            dismissButton = {
                TextButton(onClick = { showOverlayPermissionDialog = false }) {
                    Text("Vazgeç", color = onSurfaceVariantColor)
                }
            },
            containerColor = surfaceColor
        )
    }
}
