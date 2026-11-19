package com.example.sharkmentor.ui.screens

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.example.sharkmentor.core.util.SessionManager
import com.example.sharkmentor.service.blocker.SharkBlockerAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class InstalledApp(
    val name: String,
    val packageName: String,
    val icon: Drawable
)

/**
 * Checks whether [SharkBlockerAccessibilityService] is listed in the system's
 * enabled accessibility services. Works on all API levels.
 */
fun isAccessibilityServiceEnabled(context: Context, serviceClass: Class<*>): Boolean {
    val expectedId = "${context.packageName}/${serviceClass.canonicalName}"
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false

    val colonSplitter = TextUtils.SimpleStringSplitter(':')
    colonSplitter.setString(enabledServices)
    while (colonSplitter.hasNext()) {
        val componentName = colonSplitter.next()
        if (componentName.equals(expectedId, ignoreCase = true)) return true
    }
    return false
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBlockerScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    val isTimerRunning by SessionManager.isTimerRunning.collectAsState()
    val blockedPackages by SessionManager.blockedPackages.collectAsState()

    // Re-evaluate each time the screen is shown so we pick up changes made
    // in Settings while the user navigated away.
    var hasAccessibility by remember { mutableStateOf(
        isAccessibilityServiceEnabled(context, SharkBlockerAccessibilityService::class.java)
    ) }
    var hasOverlay by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var installedApps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        hasAccessibility = isAccessibilityServiceEnabled(context, SharkBlockerAccessibilityService::class.java)
        hasOverlay = Settings.canDrawOverlays(context)

        // App list only needs overlay permission; accessibility service handles
        // the actual blocking with zero polling.
        if (hasOverlay) {
            installedApps = withContext(Dispatchers.IO) {
                loadDeviceApps(context)
            }
        }
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                expandedHeight = 50.dp,
                title = { Text("App Blocker (Odak Kalkanı)") },
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
        ) {
            // ── 1. Accessibility Service permission card ────────────────────
            if (!hasAccessibility) {
                PermissionAlertCard(
                    title = "Erişilebilirlik (Kalkan) İzni Gerekli",
                    desc = "Android 11+ cihazlarda yasaklı uygulamaları anında kilitleyebilmek için SharkMentor Erişilebilirlik servisini açmanız gereklidir.",
                    btnText = "Erişilebilirliği Aç",
                    onClick = {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(intent)
                    }
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            // ── 2. Overlay (SYSTEM_ALERT_WINDOW) permission card ───────────
            if (!hasOverlay) {
                PermissionAlertCard(
                    title = "Üstte Gösterme (Overlay) İzni Gerekli",
                    desc = "Yasaklı uygulama açıldığında engelleme ekranını gösterebilmek için gereklidir.",
                    btnText = "Üstte Gösterme İzni Ver",
                    onClick = {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        ).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(intent)
                    }
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            // ── 3. Session lock banner ─────────────────────────────────────
            if (isTimerRunning) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "ODAK SEANSI AKTİF: Masa başındayken engelli uygulama listesini değiştiremezsiniz. Ayarları değiştirmek için önce seansı tamamlayın veya durdurun.",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // ── 4. App list ────────────────────────────────────────────────
            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (hasOverlay) {
                Text(
                    text = "Engellenecek Uygulamalar (${blockedPackages.size} seçildi)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(installedApps, key = { it.packageName }) { app ->
                        val isBlocked = blockedPackages.contains(app.packageName)

                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .padding(12.dp)
                                    .fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Image(
                                        bitmap = app.icon.toBitmap(96, 96).asImageBitmap(),
                                        contentDescription = null,
                                        modifier = Modifier.size(40.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = app.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                }

                                Switch(
                                    checked = isBlocked,
                                    enabled = !isTimerRunning,
                                    onCheckedChange = { checked ->
                                        val newSet = blockedPackages.toMutableSet()
                                        if (checked) newSet.add(app.packageName)
                                        else newSet.remove(app.packageName)
                                        SessionManager.updateBlockedPackages(newSet)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PermissionAlertCard(title: String, desc: String, btnText: String, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(modifier = Modifier.height(4.dp))
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onClick,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text(btnText)
            }
        }
    }
}

// checkUsageStatsPermission removed — engine migrated to AccessibilityService.

private fun loadDeviceApps(context: Context): List<InstalledApp> {
    val pm = context.packageManager
    val installedApps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
    } else {
        @Suppress("DEPRECATION")
        pm.getInstalledApplications(0)
    }

    val criticalBlacklist = setOf(
        context.packageName, // SharkMentor itself
        "com.android.settings",
        "com.android.systemui",
        "com.android.phone",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller"
    )

    return installedApps.mapNotNull { appInfo ->
        val launchIntent = pm.getLaunchIntentForPackage(appInfo.packageName)
        val isLaunchable = launchIntent != null
        val isNotCritical = !criticalBlacklist.contains(appInfo.packageName)

        if (isLaunchable && isNotCritical) {
            InstalledApp(
                name = pm.getApplicationLabel(appInfo).toString(),
                packageName = appInfo.packageName,
                icon = pm.getApplicationIcon(appInfo)
            )
        } else null
    }.sortedBy { it.name.lowercase() }
}
