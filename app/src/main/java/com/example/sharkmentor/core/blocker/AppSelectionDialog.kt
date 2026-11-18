package com.example.sharkmentor.core.blocker

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AppBlocking
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Lock
import com.example.sharkmentor.core.service.FocusTimerService
import com.example.sharkmentor.core.timer.TimerPhase

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.res.stringResource
import com.example.sharkmentor.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Material 3 dialog that lets the user select which installed apps to blacklist
 * during focus sessions.
 *
 * **Flow:**
 * 1. If [UsageStatsHelper.hasUsageStatsPermission] is false → shows a permission-request UI.
 * 2. Otherwise → loads the user-installed app list (off the main thread) and shows it
 *    with checkboxes pre-checked for already-blacklisted packages.
 * 3. "Kaydet" persists the current selection via [BlockerPreferences], then calls [onDismiss].
 * 4. "İptal" discards changes and calls [onDismiss].
 *
 * @param onDismiss Called when the dialog should close (save, cancel, or permission redirect).
 */
@Composable
fun AppSelectionDialog(onDismiss: () -> Unit) {
    val context  = LocalContext.current
    val hasUsagePerm   = remember { UsageStatsHelper.hasUsageStatsPermission(context) }
    val hasOverlayPerm = remember { UsageStatsHelper.hasOverlayPermission(context) }

    if (!hasUsagePerm) {
        PermissionRequestContent(
            title = "Kullanım Erişimi Gerekli",
            description = "SharkMentor'ın odak sırasında seçilen uygulamaları kısıtlayabilmesi için Kullanım Erişimi iznini açmanız gerekmektedir.",
            subDescription = "Ayarlar → Uygulamalar → Özel uygulama erişimi → Kullanım erişimi",
            onOpenSettings = { UsageStatsHelper.openUsageAccessSettings(context) },
            onDismiss = onDismiss
        )
    } else if (!hasOverlayPerm) {
        PermissionRequestContent(
            title = "Üstte Gösterim İzni Gerekli",
            description = "Android 10+ cihazlarda kısıtlanmış uygulamaları engellemek için 'Diğer uygulamaların üzerinde göster' izni gereklidir.",
            subDescription = "SharkMentor > Diğer uygulamaların üzerinde göster > İzin ver",
            onOpenSettings = { UsageStatsHelper.openOverlaySettings(context) },
            onDismiss = onDismiss
        )
    } else {
        AppListContent(context = context, onDismiss = onDismiss)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Permission prompt
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PermissionRequestContent(
    title: String,
    description: String,
    subDescription: String,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest  = onDismiss,
        containerColor    = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        icon = {
            Icon(
                imageVector         = Icons.Default.AppBlocking,
                contentDescription  = null,
                tint                = MaterialTheme.colorScheme.primary,
                modifier            = Modifier.size(32.dp),
            )
        },
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text  = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    description,
                    color    = MaterialTheme.colorScheme.onSurface,
                    fontSize = 13.sp,
                )
                Text(
                    subDescription,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onOpenSettings()
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
                shape  = RoundedCornerShape(10.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.btn_open_settings), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_not_now), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// App list content
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun AppListContent(context: Context, onDismiss: () -> Unit) {
    val blockerPrefs    = remember { BlockerPreferences.instance }
    val savedBlacklist  by blockerPrefs.blacklist.collectAsState()

    var appList  by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    // Map: packageName → isChecked (mutable, local to this dialog invocation)
    val checkedMap = remember(appList, savedBlacklist) {
        appList.associate { it.packageName to (it.packageName in savedBlacklist) }
            .entries.map { it.key to mutableStateOf(it.value) }
            .toMutableStateMap()
    }

    val timerState by FocusTimerService.timerStateFlow.collectAsState()
    val isFocusActive = timerState.timerPhase == TimerPhase.FOCUSING
    var showLockedWarningDialog by remember { mutableStateOf(false) }

    // Load apps off the main thread
    LaunchedEffect(Unit) {
        val apps = withContext(Dispatchers.IO) {
            UsageStatsHelper.getInstalledUserApps(context)
        }
        appList   = apps
        isLoading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        modifier  = Modifier.fillMaxWidth(),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AppBlocking, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.blocker_title), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        },
        text = {
            Column {
                Text(
                    "Odak süresinde engellenecek uygulamaları seçin:",
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(10.dp))

                when {
                    isLoading -> {
                        Text("Uygulamalar yükleniyor…", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                    appList.isEmpty() -> {
                        Text("Kullanıcı uygulaması bulunamadı.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(340.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)),
                        ) {
                            items(appList, key = { it.packageName }) { app ->
                                val isChecked = checkedMap[app.packageName]?.value == true
                                AppListRow(
                                    appInfo   = app,
                                    checked   = isChecked,
                                    onToggle  = {
                                        if (isFocusActive) {
                                            showLockedWarningDialog = true
                                        } else {
                                            checkedMap[app.packageName]?.value = !isChecked
                                        }
                                    },
                                    context   = context
                                )
                                HorizontalDivider(
                                    color     = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                                    thickness = 0.5.dp,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val newBlacklist = checkedMap
                        .filterValues { it.value }
                        .keys.toSet()
                    blockerPrefs.setBlacklist(newBlacklist)
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
                shape  = RoundedCornerShape(10.dp),
            ) {
                Text(stringResource(R.string.btn_save), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_cancel), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )

    if (showLockedWarningDialog) {
        AlertDialog(
            onDismissRequest = { showLockedWarningDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(8.dp))
                    Text("Odak Seansı Devrede", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Text("Odak seansı sırasında dikkat dağınıklığını önlemek ve çalışma disiplinini korumak için engelli uygulama listesi değiştirilemez. Değişiklik yapmak için seansı tamamlayın.")
            },
            confirmButton = {
                Button(onClick = { showLockedWarningDialog = false }) {
                    Text("Anladım")
                }
            }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Single app row
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun AppListRow(
    appInfo : AppInfo,
    checked : Boolean,
    onToggle: () -> Unit,
    context : Context,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        val icon = remember(appInfo.packageName) {
            appInfo.appInfo.loadIcon(context.packageManager).toBitmap().asImageBitmap()
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Image(
                bitmap = icon,
                contentDescription = appInfo.label,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text     = appInfo.label,
                color    = MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp,
            )
        }
        Checkbox(
            checked         = checked,
            onCheckedChange = { onToggle() },
            colors          = CheckboxDefaults.colors(
                checkedColor   = MaterialTheme.colorScheme.error,
                checkmarkColor = MaterialTheme.colorScheme.onSurface,
                uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )
    }
}
