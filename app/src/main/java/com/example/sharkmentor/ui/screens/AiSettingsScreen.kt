package com.example.sharkmentor.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.sharkmentor.core.ai.AiProviderChoice
import com.example.sharkmentor.core.ai.ApiKeyRotator
import com.example.sharkmentor.core.ai.GeminiConnectionTestResult
import com.example.sharkmentor.core.ai.GeminiRestClient
import com.example.sharkmentor.core.ai.PreferredPowerMode
import com.example.sharkmentor.core.ai.UserAiPreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AiSettingsScreen(
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val userAiPrefs = remember { UserAiPreferences.initialize(context) }

    val savedGemini by userAiPrefs.userGeminiKeys.collectAsStateWithLifecycle()
    val savedSelectedModel by userAiPrefs.selectedUserGeminiModel.collectAsStateWithLifecycle()
    val savedDiscoveredModels by userAiPrefs.discoveredGeminiModels.collectAsStateWithLifecycle()
    val savedDeepSeek by userAiPrefs.userDeepSeekKey.collectAsStateWithLifecycle()
    val savedKimi by userAiPrefs.userKimiKey.collectAsStateWithLifecycle()
    val savedProvider by userAiPrefs.selectedAiProvider.collectAsStateWithLifecycle()
    val savedPowerMode by userAiPrefs.preferredPowerMode.collectAsStateWithLifecycle()

    var geminiKeysInput by remember(savedGemini) { mutableStateOf(savedGemini) }
    var selectedGeminiModel by remember(savedSelectedModel) { mutableStateOf(savedSelectedModel) }
    var discoveredModels by remember(savedDiscoveredModels) { mutableStateOf(savedDiscoveredModels) }
    var isDiscoveringModels by remember { mutableStateOf(false) }
    var modelDiscoveryError by remember { mutableStateOf<String?>(null) }
    var modelSearchQuery by remember { mutableStateOf("") }

    var deepSeekInput by remember(savedDeepSeek) { mutableStateOf(savedDeepSeek) }
    var kimiInput by remember(savedKimi) { mutableStateOf(savedKimi) }
    var selectedProvider by remember(savedProvider) { mutableStateOf(savedProvider) }
    var selectedPowerMode by remember(savedPowerMode) { mutableStateOf(savedPowerMode) }

    var showGeminiKey by remember { mutableStateOf(false) }
    var showDeepSeekKey by remember { mutableStateOf(false) }
    var showKimiKey by remember { mutableStateOf(false) }
    var isTestingConnection by remember { mutableStateOf(false) }
    var connectionTestResult by remember { mutableStateOf<GeminiConnectionTestResult?>(null) }

    val hasCustomGeminiInput = remember(geminiKeysInput) {
        UserAiPreferences.parseGeminiKeyList(geminiKeysInput).isNotEmpty()
    }

    LaunchedEffect(geminiKeysInput) {
        val parsed = UserAiPreferences.parseGeminiKeyList(geminiKeysInput)
        if (parsed.isEmpty()) {
            selectedGeminiModel = UserAiPreferences.STANDARD_GEMINI_MODEL
            modelDiscoveryError = null
            isDiscoveringModels = false
            modelSearchQuery = ""
            return@LaunchedEffect
        }
        val firstKey = parsed.firstOrNull().orEmpty()
        if (firstKey.length < 16) return@LaunchedEffect

        delay(750L)
        isDiscoveringModels = true
        modelDiscoveryError = null
        val discovery = GeminiRestClient.fetchSupportedModels(geminiKeysInput)
        isDiscoveringModels = false
        if (discovery.success && discovery.models.isNotEmpty()) {
            discoveredModels = discovery.models
            if (discovery.models.none { it.id == selectedGeminiModel }) {
                selectedGeminiModel = discovery.models
                    .firstOrNull { it.id == UserAiPreferences.STANDARD_GEMINI_MODEL }
                    ?.id ?: discovery.models.first().id
            }
        } else if (discoveredModels.isEmpty()) {
            modelDiscoveryError = discovery.summary
        }
    }

    val filteredModels = remember(discoveredModels, modelSearchQuery) {
        val q = modelSearchQuery.trim()
        if (q.isBlank()) discoveredModels
        else discoveredModels.filter {
            it.id.contains(q, ignoreCase = true) ||
                it.displayName.contains(q, ignoreCase = true) ||
                it.description.contains(q, ignoreCase = true)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Yapay Zeka & API",
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Geri")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 760.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                // Active Status Banner
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                text = if (hasCustomGeminiInput) {
                                    "Aktif Model: $selectedGeminiModel"
                                } else {
                                    "Standart Mod: ${UserAiPreferences.STANDARD_GEMINI_MODEL}"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (hasCustomGeminiInput) {
                                    "Kendi Gemini API anahtarın ve seçtiğin model kullanılıyor."
                                } else {
                                    "Özel anahtar girilmediğinde varsayılan 3 anahtar (${UserAiPreferences.STANDARD_GEMINI_MODEL}) kullanılır."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // API Keys Card
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Key,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "API Anahtarları",
                                style = MaterialTheme.typography.titleMedium,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        AiKeyField(
                            value = geminiKeysInput,
                            onValueChange = {
                                geminiKeysInput = it
                                connectionTestResult = null
                            },
                            label = "Gemini API Key (AI Studio)",
                            placeholder = "Birden fazla için virgülle ayır: key1, key2",
                            isVisible = showGeminiKey,
                            onToggleVisibility = { showGeminiKey = !showGeminiKey },
                            onClear = {
                                geminiKeysInput = ""
                                selectedGeminiModel = UserAiPreferences.STANDARD_GEMINI_MODEL
                                discoveredModels = emptyList()
                                modelDiscoveryError = null
                                connectionTestResult = null
                            }
                        )

                        AiKeyField(
                            value = deepSeekInput,
                            onValueChange = { deepSeekInput = it },
                            label = "DeepSeek API Key (İsteğe bağlı)",
                            placeholder = "sk-...",
                            isVisible = showDeepSeekKey,
                            onToggleVisibility = { showDeepSeekKey = !showDeepSeekKey },
                            onClear = { deepSeekInput = "" }
                        )

                        AiKeyField(
                            value = kimiInput,
                            onValueChange = { kimiInput = it },
                            label = "Kimi API Key (İsteğe bağlı)",
                            placeholder = "sk-...",
                            isVisible = showKimiKey,
                            onToggleVisibility = { showKimiKey = !showKimiKey },
                            onClear = { kimiInput = "" }
                        )
                    }
                }

                // Supported Models Card (large & tablet-friendly)
                if (hasCustomGeminiInput) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                        modifier = Modifier.fillMaxWidth()
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
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Anahtarın Desteklediği Modeller",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    if (discoveredModels.isNotEmpty()) {
                                        Text(
                                            text = "${discoveredModels.size} model bulundu • Seçili: $selectedGeminiModel",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontSize = 13.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                TextButton(
                                    onClick = {
                                        if (isDiscoveringModels) return@TextButton
                                        scope.launch {
                                            isDiscoveringModels = true
                                            modelDiscoveryError = null
                                            val discovery = GeminiRestClient.fetchSupportedModels(geminiKeysInput)
                                            isDiscoveringModels = false
                                            if (discovery.success && discovery.models.isNotEmpty()) {
                                                discoveredModels = discovery.models
                                                if (discovery.models.none { it.id == selectedGeminiModel }) {
                                                    selectedGeminiModel = discovery.models
                                                        .firstOrNull { it.id == UserAiPreferences.STANDARD_GEMINI_MODEL }
                                                        ?.id ?: discovery.models.first().id
                                                }
                                            } else {
                                                modelDiscoveryError = discovery.summary
                                            }
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    if (isDiscoveringModels) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Taranıyor...", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                    } else {
                                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Modelleri Getir", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }

                            modelDiscoveryError?.let { errText ->
                                Surface(
                                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = errText,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontSize = 14.sp,
                                        lineHeight = 20.sp,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.padding(12.dp)
                                    )
                                }
                            }

                            if (discoveredModels.size > 6) {
                                OutlinedTextField(
                                    value = modelSearchQuery,
                                    onValueChange = { modelSearchQuery = it },
                                    placeholder = { Text("Model ara (örn. flash, pro, 2.5, 3.8)...", fontSize = 14.sp) },
                                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                    trailingIcon = {
                                        if (modelSearchQuery.isNotEmpty()) {
                                            IconButton(onClick = { modelSearchQuery = "" }) {
                                                Icon(Icons.Default.Close, contentDescription = "Temizle")
                                            }
                                        }
                                    },
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            if (filteredModels.isNotEmpty()) {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    filteredModels.forEach { modelOpt ->
                                        val isSelected = selectedGeminiModel == modelOpt.id
                                        Surface(
                                            shape = RoundedCornerShape(14.dp),
                                            color = if (isSelected) {
                                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                                            } else {
                                                MaterialTheme.colorScheme.surface
                                            },
                                            border = BorderStroke(
                                                width = if (isSelected) 2.dp else 1.dp,
                                                color = if (isSelected) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.outlineVariant
                                                }
                                            ),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    selectedGeminiModel = modelOpt.id
                                                    connectionTestResult = null
                                                }
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                                            ) {
                                                RadioButton(
                                                    selected = isSelected,
                                                    onClick = {
                                                        selectedGeminiModel = modelOpt.id
                                                        connectionTestResult = null
                                                    }
                                                )
                                                Column(
                                                    modifier = Modifier.weight(1f),
                                                    verticalArrangement = Arrangement.spacedBy(3.dp)
                                                ) {
                                                    Text(
                                                        text = modelOpt.displayName.ifBlank { modelOpt.id },
                                                        style = MaterialTheme.typography.titleSmall,
                                                        fontSize = 15.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.onSurface
                                                    )
                                                    Text(
                                                        text = modelOpt.id,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        fontSize = 13.sp,
                                                        fontWeight = FontWeight.Medium,
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                    if (modelOpt.description.isNotBlank()) {
                                                        Text(
                                                            text = modelOpt.description,
                                                            style = MaterialTheme.typography.bodySmall,
                                                            fontSize = 13.sp,
                                                            lineHeight = 18.sp,
                                                            maxLines = 2,
                                                            overflow = TextOverflow.Ellipsis,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                }
                                                if (isSelected) {
                                                    Icon(
                                                        imageVector = Icons.Default.CheckCircle,
                                                        contentDescription = "Seçili",
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(22.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Provider & Power Mode Card
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Sağlayıcı Tercihi",
                            style = MaterialTheme.typography.titleSmall,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AiProviderChoice.entries.forEach { provider ->
                                val isSelected = selectedProvider == provider
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { selectedProvider = provider },
                                    label = {
                                        Text(
                                            text = provider.label,
                                            fontSize = 14.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            modifier = Modifier.padding(vertical = 4.dp)
                                        )
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                )
                            }
                        }

                        Text(
                            text = "Yanıt Hızı & Derinlik Modu",
                            style = MaterialTheme.typography.titleSmall,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
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
                                    Text(mode.label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }

                // Save & Test Actions
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            userAiPrefs.saveSettings(
                                geminiKeys = geminiKeysInput,
                                deepSeekKey = deepSeekInput,
                                kimiKey = kimiInput,
                                provider = selectedProvider,
                                powerMode = selectedPowerMode,
                                selectedGeminiModel = if (hasCustomGeminiInput) {
                                    selectedGeminiModel
                                } else {
                                    UserAiPreferences.STANDARD_GEMINI_MODEL
                                },
                                discoveredModels = if (hasCustomGeminiInput) discoveredModels else emptyList()
                            )
                            ApiKeyRotator.invalidateAndReset()
                            scope.launch {
                                snackbarHostState.showSnackbar("AI ve API ayarları kaydedildi")
                            }
                        },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Ayarları Kaydet", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = {
                            if (isTestingConnection) return@OutlinedButton
                            isTestingConnection = true
                            connectionTestResult = null
                            scope.launch {
                                val result = GeminiRestClient.testApiConnection(
                                    context = context,
                                    candidateUserKeysRaw = geminiKeysInput,
                                    candidateModelId = if (hasCustomGeminiInput) selectedGeminiModel else null
                                )
                                connectionTestResult = result
                                isTestingConnection = false
                            }
                        },
                        enabled = !isTestingConnection,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 50.dp)
                    ) {
                        if (isTestingConnection) {
                            val testLabel = if (hasCustomGeminiInput) selectedGeminiModel else UserAiPreferences.STANDARD_GEMINI_MODEL
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("Bağlantı Test Ediliyor ($testLabel)...", fontSize = 15.sp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Bağlantıyı Test Et", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                connectionTestResult?.let { result ->
                    val containerColor = if (result.success) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                    } else {
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
                    }
                    val contentColor = if (result.success) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onErrorContainer
                    }
                    Surface(
                        color = containerColor,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = result.summary,
                                style = MaterialTheme.typography.titleSmall,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = contentColor
                            )
                            result.details.forEach { detailLine ->
                                Text(
                                    text = "• $detailLine",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontSize = 13.sp,
                                    lineHeight = 19.sp,
                                    color = contentColor.copy(alpha = 0.92f)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun AiKeyField(
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
        label = { Text(label, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        placeholder = if (placeholder.isNotBlank()) {
            { Text(placeholder, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        } else null,
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
        singleLine = true,
        visualTransformation = if (isVisible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onToggleVisibility) {
                    Icon(
                        imageVector = if (isVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (isVisible) "Gizle" else "Göster",
                        modifier = Modifier.size(20.dp)
                    )
                }
                if (value.isNotEmpty()) {
                    IconButton(onClick = onClear) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Temizle",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        },
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    )
}
