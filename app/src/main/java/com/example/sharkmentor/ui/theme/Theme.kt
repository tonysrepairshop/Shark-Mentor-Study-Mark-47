package com.example.sharkmentor.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
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
import com.example.sharkmentor.ui.components.SpecialMemberConfig

// ── Natural Dark Palettes (full M3 token coverage — zero default purple fallback) ──

// 1. SOOTY (Charcoal)
private val SootyDarkScheme = darkColorScheme(
    primary = Color(0xFF9E9EB2),
    onPrimary = Color(0xFF121214),
    primaryContainer = Color(0xFF2E2E38),
    onPrimaryContainer = Color(0xFFEEEEF4),
    secondary = Color(0xFFD0D0DC),
    onSecondary = Color(0xFF1A1A20),
    secondaryContainer = Color(0xFF282830),
    onSecondaryContainer = Color(0xFFE4E4EE),
    tertiary = Color(0xFFB4B4C7),
    onTertiary = Color(0xFF18181E),
    tertiaryContainer = Color(0xFF25252D),
    onTertiaryContainer = Color(0xFFE0E0EB),
    background = Color(0xFF121214),
    onBackground = Color(0xFFE6E6EC),
    surface = Color(0xFF1A1A1E),
    onSurface = Color(0xFFE6E6EC),
    surfaceVariant = Color(0xFF24242A),
    onSurfaceVariant = Color(0xFFA4A4B4),
    surfaceContainerLowest = Color(0xFF0E0E10),
    surfaceContainerLow = Color(0xFF16161A),
    surfaceContainer = Color(0xFF1C1C21),
    surfaceContainerHigh = Color(0xFF232329),
    surfaceContainerHighest = Color(0xFF2B2B33),
    outline = Color(0xFF4C4C5A),
    outlineVariant = Color(0xFF32323C),
    error = Color(0xFFF87171),
    onError = Color(0xFF2A0909),
    errorContainer = Color(0xFF451414),
    onErrorContainer = Color(0xFFFECACA)
)

// 2. COFFEE (Espresso)
private val CoffeeDarkScheme = darkColorScheme(
    primary = Color(0xFFD4A373),
    onPrimary = Color(0xFF1C1410),
    primaryContainer = Color(0xFF4A3424),
    onPrimaryContainer = Color(0xFFFDF0E2),
    secondary = Color(0xFFE6CCB2),
    onSecondary = Color(0xFF241913),
    secondaryContainer = Color(0xFF3D2E26),
    onSecondaryContainer = Color(0xFFFAEDCD),
    tertiary = Color(0xFFC89566),
    onTertiary = Color(0xFF1E140E),
    tertiaryContainer = Color(0xFF3B2B20),
    onTertiaryContainer = Color(0xFFF5E1CC),
    background = Color(0xFF1C1816),
    onBackground = Color(0xFFFAEDCD),
    surface = Color(0xFF261F1C),
    onSurface = Color(0xFFFAEDCD),
    surfaceVariant = Color(0xFF332924),
    onSurfaceVariant = Color(0xFFC9B0A0),
    surfaceContainerLowest = Color(0xFF161210),
    surfaceContainerLow = Color(0xFF211B18),
    surfaceContainer = Color(0xFF2B2320),
    surfaceContainerHigh = Color(0xFF342A26),
    surfaceContainerHighest = Color(0xFF3F342F),
    outline = Color(0xFF665347),
    outlineVariant = Color(0xFF443730),
    error = Color(0xFFF87171),
    onError = Color(0xFF2A0909),
    errorContainer = Color(0xFF471616),
    onErrorContainer = Color(0xFFFECACA)
)

// 3. OCEAN (Derin Deniz Turkuazı)
private val OceanDarkScheme = darkColorScheme(
    primary = Color(0xFF2EC4B6),
    onPrimary = Color(0xFF061E1D),
    primaryContainer = Color(0xFF12464B),
    onPrimaryContainer = Color(0xFFD2FAF6),
    secondary = Color(0xFF8BE5DD),
    onSecondary = Color(0xFF0A2229),
    secondaryContainer = Color(0xFF18394A),
    onSecondaryContainer = Color(0xFFCBF3F0),
    tertiary = Color(0xFF5BC0EB),
    onTertiary = Color(0xFF08202D),
    tertiaryContainer = Color(0xFF14384E),
    onTertiaryContainer = Color(0xFFD8F3FF),
    background = Color(0xFF0B1B2B),
    onBackground = Color(0xFFF1F8FC),
    surface = Color(0xFF12283C),
    onSurface = Color(0xFFF1F8FC),
    surfaceVariant = Color(0xFF19354D),
    onSurfaceVariant = Color(0xFF96D4E4),
    surfaceContainerLowest = Color(0xFF071320),
    surfaceContainerLow = Color(0xFF0F2234),
    surfaceContainer = Color(0xFF142D43),
    surfaceContainerHigh = Color(0xFF1B3852),
    surfaceContainerHighest = Color(0xFF224360),
    outline = Color(0xFF396580),
    outlineVariant = Color(0xFF224259),
    error = Color(0xFFF87171),
    onError = Color(0xFF2A0909),
    errorContainer = Color(0xFF451519),
    onErrorContainer = Color(0xFFFECACA)
)

// 4. RAINY (Fırtına Grisi)
private val RainyDarkScheme = darkColorScheme(
    primary = Color(0xFF7FA4C4),
    onPrimary = Color(0xFF101923),
    primaryContainer = Color(0xFF2B4158),
    onPrimaryContainer = Color(0xFFE3EFFA),
    secondary = Color(0xFFB5C7D9),
    onSecondary = Color(0xFF18222C),
    secondaryContainer = Color(0xFF293442),
    onSecondaryContainer = Color(0xFFDCE6F0),
    tertiary = Color(0xFF8CA8C7),
    onTertiary = Color(0xFF121C27),
    tertiaryContainer = Color(0xFF283546),
    onTertiaryContainer = Color(0xFFE0ECF7),
    background = Color(0xFF161B24),
    onBackground = Color(0xFFF1F5F9),
    surface = Color(0xFF1F2633),
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = Color(0xFF2A3343),
    onSurfaceVariant = Color(0xFF9AA8BC),
    surfaceContainerLowest = Color(0xFF11151D),
    surfaceContainerLow = Color(0xFF1B212D),
    surfaceContainer = Color(0xFF222936),
    surfaceContainerHigh = Color(0xFF2B3444),
    surfaceContainerHighest = Color(0xFF343F52),
    outline = Color(0xFF4E5E75),
    outlineVariant = Color(0xFF333E50),
    error = Color(0xFFF87171),
    onError = Color(0xFF2A0909),
    errorContainer = Color(0xFF451519),
    onErrorContainer = Color(0xFFFECACA)
)

// 5. FOREST (Sakin Çam Ormanı)
private val ForestDarkScheme = darkColorScheme(
    primary = Color(0xFF52B788),
    onPrimary = Color(0xFF081C12),
    primaryContainer = Color(0xFF1C4330),
    onPrimaryContainer = Color(0xFFD8F3E4),
    secondary = Color(0xFFA7DCB8),
    onSecondary = Color(0xFF0E2417),
    secondaryContainer = Color(0xFF1E3628),
    onSecondaryContainer = Color(0xFFD9F0E1),
    tertiary = Color(0xFF74C69D),
    onTertiary = Color(0xFF0B2115),
    tertiaryContainer = Color(0xFF1B3A2A),
    onTertiaryContainer = Color(0xFFDDF5E8),
    background = Color(0xFF0D1912),
    onBackground = Color(0xFFE8F5E9),
    surface = Color(0xFF14241B),
    onSurface = Color(0xFFE8F5E9),
    surfaceVariant = Color(0xFF1D3326),
    onSurfaceVariant = Color(0xFFA3C9A8),
    surfaceContainerLowest = Color(0xFF08120C),
    surfaceContainerLow = Color(0xFF111F17),
    surfaceContainer = Color(0xFF16281E),
    surfaceContainerHigh = Color(0xFF1E3528),
    surfaceContainerHighest = Color(0xFF264232),
    outline = Color(0xFF3F6650),
    outlineVariant = Color(0xFF274233),
    error = Color(0xFFF87171),
    onError = Color(0xFF2A0909),
    errorContainer = Color(0xFF451616),
    onErrorContainer = Color(0xFFFECACA)
)

// 6. DREAMS (Dusty Rose)
private val DreamsDarkScheme = darkColorScheme(
    primary = Color(0xFFF472B6),
    onPrimary = Color(0xFF240B19),
    primaryContainer = Color(0xFF592241),
    onPrimaryContainer = Color(0xFFFCE7F3),
    secondary = Color(0xFFF5B8D8),
    onSecondary = Color(0xFF27111E),
    secondaryContainer = Color(0xFF402536),
    onSecondaryContainer = Color(0xFFFCE7F3),
    tertiary = Color(0xFFD98CE0),
    onTertiary = Color(0xFF230F26),
    tertiaryContainer = Color(0xFF432347),
    onTertiaryContainer = Color(0xFFF5E1F7),
    background = Color(0xFF1A1318),
    onBackground = Color(0xFFFCE7F3),
    surface = Color(0xFF261A22),
    onSurface = Color(0xFFFCE7F3),
    surfaceVariant = Color(0xFF34232E),
    onSurfaceVariant = Color(0xFFD8B4C8),
    surfaceContainerLowest = Color(0xFF140E12),
    surfaceContainerLow = Color(0xFF20161D),
    surfaceContainer = Color(0xFF281B24),
    surfaceContainerHigh = Color(0xFF33232E),
    surfaceContainerHighest = Color(0xFF3F2C39),
    outline = Color(0xFF6B4A5F),
    outlineVariant = Color(0xFF47303F),
    error = Color(0xFFF87171),
    onError = Color(0xFF2A0909),
    errorContainer = Color(0xFF4A151B),
    onErrorContainer = Color(0xFFFECACA)
)

// 7. SAKURA (Cherry Blossom)
private val SakuraDarkScheme = darkColorScheme(
    primary = Color(0xFFFDA4AF),
    onPrimary = Color(0xFF290F14),
    primaryContainer = Color(0xFF5D2630),
    onPrimaryContainer = Color(0xFFFFE4E6),
    secondary = Color(0xFFFAC2CA),
    onSecondary = Color(0xFF2A1418),
    secondaryContainer = Color(0xFF45272D),
    onSecondaryContainer = Color(0xFFFFE4E6),
    tertiary = Color(0xFFF9A8D4),
    onTertiary = Color(0xFF2A111F),
    tertiaryContainer = Color(0xFF4B2438),
    onTertiaryContainer = Color(0xFFFFE4F2),
    background = Color(0xFF1E1517),
    onBackground = Color(0xFFFFE4E6),
    surface = Color(0xFF2A1D21),
    onSurface = Color(0xFFFFE4E6),
    surfaceVariant = Color(0xFF39272C),
    onSurfaceVariant = Color(0xFFE2B2B8),
    surfaceContainerLowest = Color(0xFF171012),
    surfaceContainerLow = Color(0xFF24181B),
    surfaceContainer = Color(0xFF2D1E23),
    surfaceContainerHigh = Color(0xFF38262C),
    surfaceContainerHighest = Color(0xFF442F36),
    outline = Color(0xFF734F57),
    outlineVariant = Color(0xFF4D343A),
    error = Color(0xFFF87171),
    onError = Color(0xFF2A0909),
    errorContainer = Color(0xFF4D161A),
    onErrorContainer = Color(0xFFFECACA)
)

// 8. AUTUMN (Amber Orange)
private val AutumnDarkScheme = darkColorScheme(
    primary = Color(0xFFFB923C),
    onPrimary = Color(0xFF261205),
    primaryContainer = Color(0xFF5C2B10),
    onPrimaryContainer = Color(0xFFFFEDD5),
    secondary = Color(0xFFFCD34D),
    onSecondary = Color(0xFF261B06),
    secondaryContainer = Color(0xFF463119),
    onSecondaryContainer = Color(0xFFFEF3C7),
    tertiary = Color(0xFFE58E4F),
    onTertiary = Color(0xFF241307),
    tertiaryContainer = Color(0xFF472B19),
    onTertiaryContainer = Color(0xFFFDE6D2),
    background = Color(0xFF181310),
    onBackground = Color(0xFFFDF2DC),
    surface = Color(0xFF241B15),
    onSurface = Color(0xFFFDF2DC),
    surfaceVariant = Color(0xFF33251D),
    onSurfaceVariant = Color(0xFFD6B090),
    surfaceContainerLowest = Color(0xFF120E0B),
    surfaceContainerLow = Color(0xFF1E1611),
    surfaceContainer = Color(0xFF261C16),
    surfaceContainerHigh = Color(0xFF32251D),
    surfaceContainerHighest = Color(0xFF3E2E24),
    outline = Color(0xFF6B503E),
    outlineVariant = Color(0xFF473529),
    error = Color(0xFFF87171),
    onError = Color(0xFF2A0909),
    errorContainer = Color(0xFF4A1514),
    onErrorContainer = Color(0xFFFECACA)
)

// 9. GALATASARAY (Sarı & Kırmızı Özel)
private val GalatasarayDarkScheme = darkColorScheme(
    primary = Color(0xFFFDB912),
onPrimary = Color(0xFF2B1900),
primaryContainer = Color(0xFFA90432),
onPrimaryContainer = Color(0xFFFFE5EA),
secondary = Color(0xFFD91E36),
onSecondary = Color(0xFFFFFFFF),
secondaryContainer = Color(0xFF5E0B1B),
onSecondaryContainer = Color(0xFFFFD9DF),
tertiary = Color(0xFFFFD56B),
onTertiary = Color(0xFF2B1900),
tertiaryContainer = Color(0xFF4D3600),
onTertiaryContainer = Color(0xFFFFE8A6),
background = Color(0xFF16080B),
onBackground = Color(0xFFFFF8F6),
surface = Color(0xFF220D12),
onSurface = Color(0xFFFFF8F6),
surfaceVariant = Color(0xFF33141B),
onSurfaceVariant = Color(0xFFE4C4C9),
surfaceContainerLowest = Color(0xFF100507),
surfaceContainerLow = Color(0xFF1C0A0E),
surfaceContainer = Color(0xFF270F15),
surfaceContainerHigh = Color(0xFF32141C),
surfaceContainerHighest = Color(0xFF3E1922),
outline = Color(0xFF7D535B),
outlineVariant = Color(0xFF4F2A32),
error = Color(0xFFF87171),
onError = Color(0xFF2A0909),
errorContainer = Color(0xFF4A1514),
onErrorContainer = Color(0xFFFECACA)
)

// ── Natural Light Palettes (dedicated high-contrast accents per palette) ────

private val SootyLightScheme = lightColorScheme(
    primary = Color(0xFF4A4A5C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6E6F0),
    onPrimaryContainer = Color(0xFF1B1B24),
    secondary = Color(0xFF626275),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFECECF4),
    onSecondaryContainer = Color(0xFF23232E),
    tertiary = Color(0xFF727287),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEAEAF2),
    onTertiaryContainer = Color(0xFF252530),
    background = Color(0xFFF7F7FA),
    onBackground = Color(0xFF141418),
    surface = Color.White,
    onSurface = Color(0xFF141418),
    surfaceVariant = Color(0xFFECECF2),
    onSurfaceVariant = Color(0xFF525260),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF4F4F8),
    surfaceContainer = Color(0xFFEEEEF4),
    surfaceContainerHigh = Color(0xFFE6E6EE),
    surfaceContainerHighest = Color(0xFFDDDDE8),
    outline = Color(0xFF9696A8),
    outlineVariant = Color(0xFFD5D5E0),
    error = Color(0xFFDC2626),
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D)
)

private val CoffeeLightScheme = lightColorScheme(
    primary = Color(0xFF8C5830),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF5E4D3),
    onPrimaryContainer = Color(0xFF331D0C),
    secondary = Color(0xFF6E4E3B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF2E6DC),
    onSecondaryContainer = Color(0xFF2D1D14),
    tertiary = Color(0xFFA66F42),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF6E7D8),
    onTertiaryContainer = Color(0xFF3A220F),
    background = Color(0xFFFAF7F4),
    onBackground = Color(0xFF1E1713),
    surface = Color.White,
    onSurface = Color(0xFF1E1713),
    surfaceVariant = Color(0xFFF0E8E1),
    onSurfaceVariant = Color(0xFF635247),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF7F2ED),
    surfaceContainer = Color(0xFFF2EAE3),
    surfaceContainerHigh = Color(0xFFEBE0D7),
    surfaceContainerHighest = Color(0xFFE2D4C8),
    outline = Color(0xFFAD9685),
    outlineVariant = Color(0xFFDFD0C4),
    error = Color(0xFFDC2626),
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D)
)

private val OceanLightScheme = lightColorScheme(
    primary = Color(0xFF0F8B80),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD2F4F0),
    onPrimaryContainer = Color(0xFF063330),
    secondary = Color(0xFF1E607A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD8EDF5),
    onSecondaryContainer = Color(0xFF0B2B38),
    tertiary = Color(0xFF0284C7),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD6EFFC),
    onTertiaryContainer = Color(0xFF082F49),
    background = Color(0xFFF4FAFA),
    onBackground = Color(0xFF0B1B2B),
    surface = Color.White,
    onSurface = Color(0xFF0B1B2B),
    surfaceVariant = Color(0xFFE4F1F4),
    onSurfaceVariant = Color(0xFF415A6B),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF0F7F8),
    surfaceContainer = Color(0xFFE8F2F4),
    surfaceContainerHigh = Color(0xFFDFECF0),
    surfaceContainerHighest = Color(0xFFD3E4EA),
    outline = Color(0xFF8AA6B5),
    outlineVariant = Color(0xFFCADEE6),
    error = Color(0xFFDC2626),
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D)
)

private val RainyLightScheme = lightColorScheme(
    primary = Color(0xFF3B6287),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE8F5),
    onPrimaryContainer = Color(0xFF132638),
    secondary = Color(0xFF526A80),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3ECF5),
    onSecondaryContainer = Color(0xFF1D2B38),
    tertiary = Color(0xFF4F7B9E),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE0ECF7),
    onTertiaryContainer = Color(0xFF182D40),
    background = Color(0xFFF5F8FB),
    onBackground = Color(0xFF141B24),
    surface = Color.White,
    onSurface = Color(0xFF141B24),
    surfaceVariant = Color(0xFFE6EDF5),
    onSurfaceVariant = Color(0xFF4B5A6B),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF1F5F9),
    surfaceContainer = Color(0xFFEAF0F6),
    surfaceContainerHigh = Color(0xFFE1E9F2),
    surfaceContainerHighest = Color(0xFFD6E0EC),
    outline = Color(0xFF8FA0B3),
    outlineVariant = Color(0xFFCFDCE8),
    error = Color(0xFFDC2626),
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D)
)

private val ForestLightScheme = lightColorScheme(
    primary = Color(0xFF266644),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD5EEDF),
    onPrimaryContainer = Color(0xFF0B2918),
    secondary = Color(0xFF3F7356),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDFEFE5),
    onSecondaryContainer = Color(0xFF153021),
    tertiary = Color(0xFF4E8A63),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE2F2E7),
    onTertiaryContainer = Color(0xFF163320),
    background = Color(0xFFF4F9F5),
    onBackground = Color(0xFF0F1F15),
    surface = Color.White,
    onSurface = Color(0xFF0F1F15),
    surfaceVariant = Color(0xFFE4EFE7),
    onSurfaceVariant = Color(0xFF455E4E),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF0F6F2),
    surfaceContainer = Color(0xFFE8F1EB),
    surfaceContainerHigh = Color(0xFFDFEAE3),
    surfaceContainerHighest = Color(0xFFD4E3D9),
    outline = Color(0xFF8AA493),
    outlineVariant = Color(0xFFCCDDD2),
    error = Color(0xFFDC2626),
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D)
)

private val DreamsLightScheme = lightColorScheme(
    primary = Color(0xFFB83276),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFCE2EF),
    onPrimaryContainer = Color(0xFF420C28),
    secondary = Color(0xFF8C4A6E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF6E4EE),
    onSecondaryContainer = Color(0xFF381629),
    tertiary = Color(0xFF9C4B8B),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF5E3F2),
    onTertiaryContainer = Color(0xFF3A1533),
    background = Color(0xFFFDF6FA),
    onBackground = Color(0xFF21131B),
    surface = Color.White,
    onSurface = Color(0xFF21131B),
    surfaceVariant = Color(0xFFF5E6EF),
    onSurfaceVariant = Color(0xFF6B4C5D),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAF1F6),
    surfaceContainer = Color(0xFFF5E9F0),
    surfaceContainerHigh = Color(0xFFEFDFEA),
    surfaceContainerHighest = Color(0xFFE7D2E0),
    outline = Color(0xFFB590A5),
    outlineVariant = Color(0xFFE5CDD9),
    error = Color(0xFFDC2626),
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D)
)

private val SakuraLightScheme = lightColorScheme(
    primary = Color(0xFFC74B62),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE3E8),
    onPrimaryContainer = Color(0xFF47101B),
    secondary = Color(0xFF9E5362),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFCE7EB),
    onSecondaryContainer = Color(0xFF3D171F),
    tertiary = Color(0xFFD66079),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE5EB),
    onTertiaryContainer = Color(0xFF471420),
    background = Color(0xFFFFF7F8),
    onBackground = Color(0xFF241418),
    surface = Color.White,
    onSurface = Color(0xFF241418),
    surfaceVariant = Color(0xFFF9E8EB),
    onSurfaceVariant = Color(0xFF6E4E55),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFCF2F4),
    surfaceContainer = Color(0xFFF8EAED),
    surfaceContainerHigh = Color(0xFFF3DFE3),
    surfaceContainerHighest = Color(0xFFECD2D8),
    outline = Color(0xFFBA929B),
    outlineVariant = Color(0xFFEBD0D6),
    error = Color(0xFFDC2626),
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D)
)

private val AutumnLightScheme = lightColorScheme(
    primary = Color(0xFFC2410C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFEDD5),
    onPrimaryContainer = Color(0xFF431407),
    secondary = Color(0xFF9A5518),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFAEBDC),
    onSecondaryContainer = Color(0xFF3B1F06),
    tertiary = Color(0xFFB45309),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFEF3C7),
    onTertiaryContainer = Color(0xFF451A03),
    background = Color(0xFFFDF8F3),
    onBackground = Color(0xFF21160E),
    surface = Color.White,
    onSurface = Color(0xFF21160E),
    surfaceVariant = Color(0xFFF5EAE0),
    onSurfaceVariant = Color(0xFF6B5342),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAF3EC),
    surfaceContainer = Color(0xFFF5ECE3),
    surfaceContainerHigh = Color(0xFFEEE2D7),
    surfaceContainerHighest = Color(0xFFE6D6C8),
    outline = Color(0xFFB59884),
    outlineVariant = Color(0xFFE6D4C5),
    error = Color(0xFFDC2626),
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D)
)

private val GalatasarayLightScheme = lightColorScheme(
    primary = Color(0xFFA90432),
onPrimary = Color(0xFFFFFFFF),
primaryContainer = Color(0xFFFFE6A1),
onPrimaryContainer = Color(0xFF3B2200),
secondary = Color(0xFFC98F00),
onSecondary = Color(0xFFFFFFFF),
secondaryContainer = Color(0xFFFFF0C2),
onSecondaryContainer = Color(0xFF3B2800),
tertiary = Color(0xFF8C142B),
onTertiary = Color(0xFFFFFFFF),
tertiaryContainer = Color(0xFFFFD9DF),
onTertiaryContainer = Color(0xFF470614),
background = Color(0xFFFFFBF7),
onBackground = Color(0xFF1F1215),
surface = Color(0xFFFFFFFF),
onSurface = Color(0xFF1F1215),
surfaceVariant = Color(0xFFFCEEEF),
onSurfaceVariant = Color(0xFF5E4348),
surfaceContainerLowest = Color(0xFFFFFFFF),
surfaceContainerLow = Color(0xFFFFF6F4),
surfaceContainer = Color(0xFFFDF0F0),
surfaceContainerHigh = Color(0xFFF9E6E7),
surfaceContainerHighest = Color(0xFFF4DCDF),
outline = Color(0xFF9C7B81),
outlineVariant = Color(0xFFE7CFD3),
error = Color(0xFFDC2626),
onError = Color.White,
errorContainer = Color(0xFFFEE2E2),
onErrorContainer = Color(0xFF7F1D1D)
)

// ── Zen Pure Focus Monochrome Scheme ────────────────────────────────────────

private val ZenMonochromeScheme = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF262626),
    onPrimaryContainer = Color.White,
    secondary = Color(0xFFBDBDBD),
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF1F1F1F),
    onSecondaryContainer = Color(0xFFE5E5E5),
    tertiary = Color(0xFF9E9E9E),
    onTertiary = Color.Black,
    tertiaryContainer = Color(0xFF1C1C1C),
    onTertiaryContainer = Color(0xFFD4D4D4),
    background = Color.Black,
    onBackground = Color.White,
    surface = Color(0xFF0E0E0E),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF1A1A1A),
    onSurfaceVariant = Color(0xFFA8A8A8),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0E0E0E),
    surfaceContainer = Color(0xFF141414),
    surfaceContainerHigh = Color(0xFF1D1D1D),
    surfaceContainerHighest = Color(0xFF262626),
    outline = Color(0xFF525252),
    outlineVariant = Color(0xFF2E2E2E),
    error = Color(0xFFEF4444),
    onError = Color.White,
    errorContainer = Color(0xFF3B1212),
    onErrorContainer = Color(0xFFFCA5A5)
)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun paletteSchemeFor(palette: ThemePalette, isDark: Boolean): ColorScheme {
    return if (isDark) {
        when (palette) {
            ThemePalette.SOOTY -> SootyDarkScheme
            ThemePalette.COFFEE -> CoffeeDarkScheme
            ThemePalette.OCEAN -> OceanDarkScheme
            ThemePalette.RAINY -> RainyDarkScheme
            ThemePalette.FOREST -> ForestDarkScheme
            ThemePalette.DREAMS -> DreamsDarkScheme
            ThemePalette.SAKURA -> SakuraDarkScheme
            ThemePalette.AUTUMN -> AutumnDarkScheme
ThemePalette.GALATASARAY -> GalatasarayDarkScheme
        }
    } else {
        when (palette) {
            ThemePalette.SOOTY -> SootyLightScheme
            ThemePalette.COFFEE -> CoffeeLightScheme
            ThemePalette.OCEAN -> OceanLightScheme
            ThemePalette.RAINY -> RainyLightScheme
            ThemePalette.FOREST -> ForestLightScheme
            ThemePalette.DREAMS -> DreamsLightScheme
            ThemePalette.SAKURA -> SakuraLightScheme
            ThemePalette.AUTUMN -> AutumnLightScheme
ThemePalette.GALATASARAY -> GalatasarayLightScheme
        }
    }
}

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

val effectivePalette = if (palette == ThemePalette.GALATASARAY && !SpecialMemberConfig.isEmreBey()) ThemePalette.SOOTY else palette
    val effectiveIsDark = isDark || isZenMode
    val finalColors = if (isZenMode) {
        ZenMonochromeScheme
    } else {
        paletteSchemeFor(palette = effectivePalette, isDark = isDark)
    }

    val statusBarBg = finalColors.background
    val navBarBg    = finalColors.surface

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = view.context.findActivity()?.window ?: return@SideEffect
            @Suppress("DEPRECATION")
            window.statusBarColor = statusBarBg.toArgb()
            @Suppress("DEPRECATION")
            window.navigationBarColor = navBarBg.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars     = !effectiveIsDark
                isAppearanceLightNavigationBars = !effectiveIsDark
            }
        }
    }

    MaterialTheme(
        colorScheme = finalColors,
        typography = Typography,
        content = content
    )
}
