package com.example.sharkmentor.core.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.min

@Immutable
data class LocalAvatarState(
    val avatarBase64: String = "",
    val avatarPresetId: String = ""
)

@Immutable
data class AvatarPreset(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val startColor: Color,
    val endColor: Color,
    val iconTint: Color = Color.White
)

object AvatarHelper {

    private const val PREFS_NAME = "shark_avatar_prefs"
    private const val KEY_AVATAR_BASE64 = "avatar_base64"
    private const val KEY_AVATAR_PRESET_ID = "avatar_preset_id"

    private const val TARGET_AVATAR_PX = 160
    private const val WEBP_INITIAL_QUALITY = 78
    private const val MAX_AVATAR_BYTES = 12 * 1024 // ~12 KB raw WebP ceiling

    private val bitmapCache = object : LruCache<String, ImageBitmap>(72) {}

    @Volatile
    private var isStateLoaded = false

    private val _avatarState = MutableStateFlow(LocalAvatarState())
    val avatarState: StateFlow<LocalAvatarState> = _avatarState.asStateFlow()

    val PRESETS: List<AvatarPreset> = listOf(
        AvatarPreset(
            id = "preset_bolt",
            label = "Enerji",
            icon = Icons.Default.Bolt,
            startColor = Color(0xFF0284C7),
            endColor = Color(0xFF38BDF8)
        ),
        AvatarPreset(
            id = "preset_flame",
            label = "Su Serisi",
            icon = Icons.Rounded.WaterDrop,
            startColor = Color(0xFF0284C7),
            endColor = Color(0xFF22D3EE)
        ),
        AvatarPreset(
            id = "preset_spark",
            label = "Parıltı",
            icon = Icons.Default.AutoAwesome,
            startColor = Color(0xFF7C3AED),
            endColor = Color(0xFFA78BFA)
        ),
        AvatarPreset(
            id = "preset_focus",
            label = "Odak",
            icon = Icons.Default.Psychology,
            startColor = Color(0xFF0D9488),
            endColor = Color(0xFF2DD4BF)
        ),
        AvatarPreset(
            id = "preset_academy",
            label = "Akademi",
            icon = Icons.Default.School,
            startColor = Color(0xFF4338CA),
            endColor = Color(0xFF818CF8)
        ),
        AvatarPreset(
            id = "preset_star",
            label = "Yıldız",
            icon = Icons.Default.Star,
            startColor = Color(0xFFCA8A04),
            endColor = Color(0xFFFACC15)
        ),
        AvatarPreset(
            id = "preset_turbo",
            label = "Turbo",
            icon = Icons.Default.Speed,
            startColor = Color(0xFF0891B2),
            endColor = Color(0xFF22D3EE)
        ),
        AvatarPreset(
            id = "preset_zen",
            label = "Denge",
            icon = Icons.Default.SelfImprovement,
            startColor = Color(0xFF059669),
            endColor = Color(0xFF34D399)
        ),
        AvatarPreset(
            id = "preset_science",
            label = "Bilim",
            icon = Icons.Default.Science,
            startColor = Color(0xFFDB2777),
            endColor = Color(0xFFF472B6)
        ),
        AvatarPreset(
            id = "preset_target",
            label = "Hedef",
            icon = Icons.Default.TrackChanges,
            startColor = Color(0xFFDC2626),
            endColor = Color(0xFFF87171)
        )
    )

    private val FALLBACK_PALETTE: List<Pair<Color, Color>> = listOf(
        Color(0xFF1D4ED8) to Color(0xFF3B82F6),
        Color(0xFF0F766E) to Color(0xFF14B8A6),
        Color(0xFF6D28D9) to Color(0xFF8B5CF6),
        Color(0xFFC2410C) to Color(0xFFF97316),
        Color(0xFFBE185D) to Color(0xFFEC4899),
        Color(0xFF047857) to Color(0xFF10B981),
        Color(0xFF0369A1) to Color(0xFF0EA5E9),
        Color(0xFF4338CA) to Color(0xFF6366F1)
    )

    fun findPreset(presetId: String?): AvatarPreset? {
        if (presetId.isNullOrBlank()) return null
        return PRESETS.firstOrNull { it.id == presetId }
    }

    fun init(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val base64 = prefs.getString(KEY_AVATAR_BASE64, "") ?: ""
        val presetId = if (base64.isNotBlank()) "" else (prefs.getString(KEY_AVATAR_PRESET_ID, "") ?: "")
        _avatarState.value = LocalAvatarState(
            avatarBase64 = base64,
            avatarPresetId = presetId
        )
        isStateLoaded = true
    }

    fun ensureLoaded(context: Context) {
        if (!isStateLoaded) {
            init(context)
        }
    }

    fun getSavedAvatarBase64(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_AVATAR_BASE64, "") ?: ""
    }

    fun getSavedAvatarPresetId(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_AVATAR_PRESET_ID, "") ?: ""
    }

    fun saveLocalAvatar(context: Context, base64: String, presetId: String) {
        val cleanBase64 = base64.trim()
        val cleanPreset = if (cleanBase64.isNotBlank()) "" else presetId.trim()
        val next = LocalAvatarState(avatarBase64 = cleanBase64, avatarPresetId = cleanPreset)
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_AVATAR_BASE64, cleanBase64)
            .putString(KEY_AVATAR_PRESET_ID, cleanPreset)
            .apply()
        isStateLoaded = true
        if (_avatarState.value != next) {
            _avatarState.value = next
        }
    }

    fun clearLocalAvatar(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(KEY_AVATAR_BASE64)
            .remove(KEY_AVATAR_PRESET_ID)
            .apply()
        isStateLoaded = true
        _avatarState.value = LocalAvatarState()
    }

    /**
     * Loads the image at [uri], normalizes EXIF orientation, center-crops to a square,
     * downscales to 160x160px, and compresses to ~78% WebP Base64 (<= ~12 KB).
     */
    suspend fun encodeUriToAvatarBase64(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.applicationContext.contentResolver

            val boundsOpts = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, boundsOpts)
            }
            if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) {
                return@runCatching null
            }

            var sampleSize = 1
            while (
                (boundsOpts.outWidth / (sampleSize * 2)) >= TARGET_AVATAR_PX &&
                (boundsOpts.outHeight / (sampleSize * 2)) >= TARGET_AVATAR_PX
            ) {
                sampleSize *= 2
            }

            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOpts)
            } ?: return@runCatching null

            val rotationDegrees = readExifRotationDegrees(context, uri)
            val oriented = if (rotationDegrees != 0) {
                val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also {
                    if (it !== decoded) decoded.recycle()
                }
            } else {
                decoded
            }

            val side = min(oriented.width, oriented.height).coerceAtLeast(1)
            val cropX = ((oriented.width - side) / 2).coerceAtLeast(0)
            val cropY = ((oriented.height - side) / 2).coerceAtLeast(0)
            val square = Bitmap.createBitmap(oriented, cropX, cropY, side, side).also {
                if (it !== oriented) oriented.recycle()
            }

            val scaled = if (square.width != TARGET_AVATAR_PX || square.height != TARGET_AVATAR_PX) {
                Bitmap.createScaledBitmap(square, TARGET_AVATAR_PX, TARGET_AVATAR_PX, true).also {
                    if (it !== square) square.recycle()
                }
            } else {
                square
            }

            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }

            val baos = ByteArrayOutputStream()
            var quality = WEBP_INITIAL_QUALITY
            scaled.compress(format, quality, baos)
            while (baos.size() > MAX_AVATAR_BYTES && quality > 40) {
                baos.reset()
                quality -= 10
                scaled.compress(format, quality, baos)
            }

            val finalBytes = if (baos.size() > MAX_AVATAR_BYTES) {
                val compact = Bitmap.createScaledBitmap(scaled, 128, 128, true)
                baos.reset()
                compact.compress(format, 65, baos)
                if (compact !== scaled) compact.recycle()
                baos.toByteArray()
            } else {
                baos.toByteArray()
            }
            scaled.recycle()

            Base64.encodeToString(finalBytes, Base64.NO_WRAP)
        }.getOrNull()
    }

    private fun readExifRotationDegrees(context: Context, uri: Uri): Int {
        return runCatching {
            context.applicationContext.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        }.getOrDefault(0)
    }

    /**
     * Decodes a Base64 avatar string into an [ImageBitmap], backed by an in-memory [LruCache].
     */
    fun decodeAvatarBitmap(base64: String): ImageBitmap? {
        if (base64.isBlank()) return null
        val cacheKey = "${base64.length}_${base64.hashCode()}"
        synchronized(bitmapCache) {
            bitmapCache.get(cacheKey)
        }?.let { return it }

        return runCatching {
            val cleanBase64 = base64.substringAfter("base64,", base64).trim()
            val bytes = Base64.decode(cleanBase64, Base64.DEFAULT)
            if (bytes.isEmpty()) return null
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val imageBmp = bmp.asImageBitmap()
            synchronized(bitmapCache) {
                bitmapCache.put(cacheKey, imageBmp)
            }
            imageBmp
        }.getOrNull()
    }

    fun fallbackColorsForName(displayName: String): Pair<Color, Color> {
        val cleaned = cleanNameForInitial(displayName)
        if (cleaned.isBlank()) return FALLBACK_PALETTE.first()
        val idx = abs(cleaned.hashCode()) % FALLBACK_PALETTE.size
        return FALLBACK_PALETTE[idx]
    }

    fun extractInitial(displayName: String): String {
        val cleaned = cleanNameForInitial(displayName)
        val firstChar = cleaned.firstOrNull { it.isLetterOrDigit() } ?: 'Ö'
        return firstChar.uppercaseChar().toString()
    }

    private fun cleanNameForInitial(displayName: String): String {
        val trimmed = displayName.trim()
        return if (trimmed.startsWith("Sen (") && trimmed.endsWith(")") && trimmed.length > 6) {
            trimmed.substring(5, trimmed.length - 1).trim()
        } else {
            trimmed
        }
    }
}

suspend fun encodeUriToAvatarBase64(context: Context, uri: Uri): String? =
    AvatarHelper.encodeUriToAvatarBase64(context, uri)

fun decodeAvatarBitmap(base64: String): ImageBitmap? =
    AvatarHelper.decodeAvatarBitmap(base64)

@Composable
fun rememberDecodedAvatarBitmap(base64: String): ImageBitmap? {
    return remember(base64) {
        AvatarHelper.decodeAvatarBitmap(base64)
    }
}

/**
 * Unified circular avatar component:
 * 1) User uploaded photo ([avatarBase64]) if valid
 * 2) Built-in preset style ([avatarPresetId]) if selected
 * 3) Deterministic colorful initial-letter avatar derived from [displayName]
 */
@Composable
fun SharkAvatar(
    avatarBase64: String,
    avatarPresetId: String,
    displayName: String,
    size: Dp = 42.dp,
    modifier: Modifier = Modifier
) {
    val decodedBitmap = rememberDecodedAvatarBitmap(avatarBase64)
    val preset = remember(avatarPresetId) { AvatarHelper.findPreset(avatarPresetId) }
    val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .border(1.dp, borderColor, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        when {
            decodedBitmap != null -> {
                Image(
                    bitmap = decodedBitmap,
                    contentDescription = displayName.ifBlank { "Profil Fotoğrafı" },
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                )
            }
            preset != null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                colors = listOf(preset.startColor, preset.endColor)
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = preset.icon,
                        contentDescription = preset.label,
                        tint = preset.iconTint,
                        modifier = Modifier.size(size * 0.54f)
                    )
                }
            }
            else -> {
                val (startColor, endColor) = remember(displayName) {
                    AvatarHelper.fallbackColorsForName(displayName)
                }
                val initial = remember(displayName) {
                    AvatarHelper.extractInitial(displayName)
                }
                val fontSizeSp = (size.value * 0.42f).coerceIn(12f, 34f).sp
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                colors = listOf(startColor, endColor)
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = initial,
                        color = Color.White,
                        fontSize = fontSizeSp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
