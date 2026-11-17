package com.example.sharkmentor.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.example.sharkmentor.core.data.AppThemeMode
import com.example.sharkmentor.core.data.ThemePalette

// ── Natural Palettes ─────────────────────────────────────────────────────────

// 1. SOOTY (Charcoal)
private val SootyDarkScheme = darkColorScheme(
    primary = Color(0xFF8E8EA0),
    onPrimary = Color(0xFF121214),
    secondary = Color(0xFFE1E1E6),
    background = Color(0xFF121214),
    surface = Color(0xFF1A1A1E),
    onBackground = Color(0xFFE1E1E6),
    onSurface = Color(0xFFE1E1E6),
    surfaceContainer = Color(0xFF1A1A1E),
    surfaceContainerHighest = Color(0xFF26262C),
    onSurfaceVariant = Color(0xFFA0A0B0)
)

// 2. COFFEE (Espresso)
private val CoffeeDarkScheme = darkColorScheme(
    primary = Color(0xFFD4A373),
    onPrimary = Color(0xFF1C1816),
    secondary = Color(0xFFFAEDCD),
    background = Color(0xFF1C1816),
    surface = Color(0xFF2B2320),
    onBackground = Color(0xFFFAEDCD),
    onSurface = Color(0xFFFAEDCD),
    surfaceContainer = Color(0xFF2B2320),
    surfaceContainerHighest = Color(0xFF382E2B),
    onSurfaceVariant = Color(0xFFC5A898)
)

// 3. OCEAN (Derin Deniz Turkuazı)
private val OceanDarkScheme = darkColorScheme(
    primary = Color(0xFF2EC4B6),
    onPrimary = Color(0xFF0B1B2B),
    secondary = Color(0xFFCBF3F0),
    background = Color(0xFF0B1B2B),
    surface = Color(0xFF132B40),
    onBackground = Color.White,
    onSurface = Color.White,
    surfaceContainer = Color(0xFF132B40),
    surfaceContainerHighest = Color(0xFF1A3850),
    onSurfaceVariant = Color(0xFF90E0EF)
)

// 4. RAINY (Fırtına Grisi)
private val RainyDarkScheme = darkColorScheme(
    primary = Color(0xFF6B8EAD),
    onPrimary = Color(0xFF181D26),
    secondary = Color(0xFFC5D1DE),
    background = Color(0xFF181D26),
    surface = Color(0xFF222936),
    onBackground = Color(0xFFF8FAFC),
    onSurface = Color(0xFFF8FAFC),
    surfaceContainer = Color(0xFF222936),
    surfaceContainerHighest = Color(0xFF2E3746),
    onSurfaceVariant = Color(0xFF94A3B8)
)

// 5. FOREST (Sakin Çam Ormanı)
private val ForestDarkScheme = darkColorScheme(
    primary = Color(0xFF387A56),
    onPrimary = Color(0xFF0D1912),
    secondary = Color(0xFFD3EED8),
    background = Color(0xFF0D1912),
    surface = Color(0xFF15261D),
    onBackground = Color(0xFFE8F5E9),
    onSurface = Color(0xFFE8F5E9),
    surfaceContainer = Color(0xFF15261D),
    surfaceContainerHighest = Color(0xFF20382B),
    onSurfaceVariant = Color(0xFFA3C9A8)
)

// 6. DREAMS (Dusty Rose)
private val DreamsDarkScheme = darkColorScheme(
    primary = Color(0xFFF472B6),
    onPrimary = Color(0xFF1A1318),
    secondary = Color(0xFFFCE7F3),
    background = Color(0xFF1A1318),
    surface = Color(0xFF281B24),
    onBackground = Color(0xFFFCE7F3),
    onSurface = Color(0xFFFCE7F3),
    surfaceContainer = Color(0xFF281B24),
    surfaceContainerHighest = Color(0xFF382633),
    onSurfaceVariant = Color(0xFFD8B4C8)
)

// 7. SAKURA (Cherry Blossom)
private val SakuraDarkScheme = darkColorScheme(
    primary = Color(0xFFFDA4AF),
    onPrimary = Color(0xFF1E1517),
    secondary = Color(0xFFFFE4E6),
    background = Color(0xFF1E1517),
    surface = Color(0xFF2D1E23),
    onBackground = Color(0xFFFFE4E6),
    onSurface = Color(0xFFFFE4E6),
    surfaceContainer = Color(0xFF2D1E23),
    surfaceContainerHighest = Color(0xFF3D2A30),
    onSurfaceVariant = Color(0xFFE2B2B8)
)

// 8. AUTUMN (Amber Orange)
private val AutumnDarkScheme = darkColorScheme(
    primary = Color(0xFFEA580C),
    onPrimary = Color(0xFF181310),
    secondary = Color(0xFFFDE68A),
    background = Color(0xFF181310),
    surface = Color(0xFF261C16),
    onBackground = Color(0xFFFDE68A),
    onSurface = Color(0xFFFDE68A),
    surfaceContainer = Color(0xFF261C16),
    surfaceContainerHighest = Color(0xFF382920),
    onSurfaceVariant = Color(0xFFD4A373)
)

@Composable
fun SharkMentorTheme(
    themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    palette: ThemePalette = ThemePalette.SOOTY,
    isZenMode: Boolean = false,
    content: @Composable () -> Unit
) {
    val isDark = when (themeMode) {
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
        AppThemeMode.DARK -> true
        AppThemeMode.LIGHT -> false
    }

    val darkScheme = when (palette) {
        ThemePalette.SOOTY -> SootyDarkScheme
        ThemePalette.COFFEE -> CoffeeDarkScheme
        ThemePalette.OCEAN -> OceanDarkScheme
        ThemePalette.RAINY -> RainyDarkScheme
        ThemePalette.FOREST -> ForestDarkScheme
        ThemePalette.DREAMS -> DreamsDarkScheme
        ThemePalette.SAKURA -> SakuraDarkScheme
        ThemePalette.AUTUMN -> AutumnDarkScheme
    }

    val finalColors = if (isZenMode) {
        darkScheme.copy(
            background = Color.Black,
            surface = Color(0xFF101010),
            primary = Color.White,
            onPrimary = Color.Black,
            secondary = Color(0xFFAAAAAA),
            onSecondary = Color.Black,
            onBackground = Color.White,
            onSurface = Color.White,
            surfaceContainer = Color(0xFF101010),
            surfaceContainerHighest = Color(0xFF222222),
            onSurfaceVariant = Color(0xFFAAAAAA)
        )
    } else if (!isDark) {
        lightColorScheme(
            background = Color(0xFFF8FAFC),
            surface = Color(0xFFFFFFFF),
            primary = darkScheme.primary,
            secondary = darkScheme.secondary,
            onBackground = Color(0xFF0F172A),
            onSurface = Color(0xFF0F172A),
            surfaceContainer = Color(0xFFF1F5F9),
            surfaceContainerHighest = Color(0xFFE2E8F0),
            onSurfaceVariant = Color(0xFF475569)
        )
    } else darkScheme

    val statusBarBg = finalColors.background
    val navBarBg    = finalColors.surface

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            @Suppress("DEPRECATION")
            window.statusBarColor = statusBarBg.toArgb()
            @Suppress("DEPRECATION")
            window.navigationBarColor = navBarBg.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars     = !isDark
                isAppearanceLightNavigationBars = !isDark
            }
        }
    }

    MaterialTheme(
        colorScheme = finalColors,
        typography = Typography,
        content = content
    )
}
