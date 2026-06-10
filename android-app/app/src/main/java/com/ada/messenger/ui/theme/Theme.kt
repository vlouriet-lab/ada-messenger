package com.ada.messenger.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.sp
import com.ada.messenger.R

// ── Google Fonts — Inter ─────────────────────────────────────────────────────

private val fontProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage   = "com.google.android.gms",
    certificates      = R.array.com_google_android_gms_fonts_certs,
)

private val interFont = GoogleFont("Inter")

val InterFontFamily = FontFamily(
    Font(googleFont = interFont, fontProvider = fontProvider, weight = FontWeight.Normal),
    Font(googleFont = interFont, fontProvider = fontProvider, weight = FontWeight.Medium),
    Font(googleFont = interFont, fontProvider = fontProvider, weight = FontWeight.SemiBold),
    Font(googleFont = interFont, fontProvider = fontProvider, weight = FontWeight.Bold),
    Font(googleFont = interFont, fontProvider = fontProvider, weight = FontWeight.ExtraBold),
)

// ── Color palettes ───────────────────────────────────────────────────────────

private val DarkColorScheme = darkColorScheme(
    primary              = Color(0xFF8B80F8),   // приглушённый индиго — не режет глаз
    onPrimary            = Color(0xFF1A0D6E),
    primaryContainer     = Color(0xFF2D2466),
    onPrimaryContainer   = Color(0xFFE0DCFF),
    secondary            = Color(0xFF5AC8C4),   // бирюза
    onSecondary          = Color(0xFF003735),
    secondaryContainer   = Color(0xFF004F4D),
    onSecondaryContainer = Color(0xFFA8EFEC),
    tertiary             = Color(0xFFE8A87C),
    onTertiary           = Color(0xFF4A1800),
    tertiaryContainer    = Color(0xFF6B2D0D),
    onTertiaryContainer  = Color(0xFFFFDBCF),
    error                = Color(0xFFFF8A80),
    onError              = Color(0xFF550000),
    errorContainer       = Color(0xFF7D0000),
    background           = Color(0xFF0A0A14),   // глубокий почти-чёрный
    onBackground         = Color(0xFFE8E4F0),
    surface              = Color(0xFF13132A),   // заметный контраст с фоном
    onSurface            = Color(0xFFE8E4F0),
    surfaceVariant       = Color(0xFF1E1E3A),
    onSurfaceVariant     = Color(0xFFC5BFD4),
    outline              = Color(0xFF7F7A8E),
    outlineVariant       = Color(0xFF3A3550),
    scrim                = Color(0xFF000000),
    inverseSurface       = Color(0xFFE6E1F0),
    inverseOnSurface     = Color(0xFF1A1A2E),
    inversePrimary       = Color(0xFF4A3DB5),
)

private val LightColorScheme = lightColorScheme(
    primary              = Color(0xFF4A3DB5),
    onPrimary            = Color.White,
    primaryContainer     = Color(0xFFE0DCFF),
    onPrimaryContainer   = Color(0xFF0F0550),
    secondary            = Color(0xFF00796B),
    onSecondary          = Color.White,
    secondaryContainer   = Color(0xFFB2DFDB),
    onSecondaryContainer = Color(0xFF001C1A),
    tertiary             = Color(0xFFB5390A),
    onTertiary           = Color.White,
    tertiaryContainer    = Color(0xFFFFDBCF),
    onTertiaryContainer  = Color(0xFF3C0800),
    error                = Color(0xFFBA1A1A),
    onError              = Color.White,
    errorContainer       = Color(0xFFFFDAD6),
    background           = Color(0xFFF4F1FC),
    onBackground         = Color(0xFF1A1625),
    surface              = Color(0xFFFFFFFF),
    onSurface            = Color(0xFF1A1625),
    surfaceVariant       = Color(0xFFEAE5F5),
    onSurfaceVariant     = Color(0xFF46424F),
    outline              = Color(0xFF78737E),
    outlineVariant       = Color(0xFFC9C4D4),
    scrim                = Color(0xFF000000),
    inverseSurface       = Color(0xFF2F2B3A),
    inverseOnSurface     = Color(0xFFF4F1FC),
    inversePrimary       = Color(0xFF8B80F8),
)

// ── Typography — Inter ───────────────────────────────────────────────────────

// V-39: Branded text style for "ADA" logo in top bar and splash
val AdaBrandingStyle = TextStyle(
    fontFamily   = InterFontFamily,
    fontSize     = 22.sp,
    fontWeight   = FontWeight.ExtraBold,
    letterSpacing = 4.sp,
)

private val AdaTypography = Typography(
    displayLarge  = TextStyle(fontFamily = InterFontFamily, fontSize = 57.sp, lineHeight = 64.sp, fontWeight = FontWeight.Normal, letterSpacing = (-0.25).sp),
    displayMedium = TextStyle(fontFamily = InterFontFamily, fontSize = 45.sp, lineHeight = 52.sp, fontWeight = FontWeight.Normal),
    displaySmall  = TextStyle(fontFamily = InterFontFamily, fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.Normal),
    headlineLarge = TextStyle(fontFamily = InterFontFamily, fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium= TextStyle(fontFamily = InterFontFamily, fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontFamily = InterFontFamily, fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    titleLarge    = TextStyle(fontFamily = InterFontFamily, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium   = TextStyle(fontFamily = InterFontFamily, fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp),
    titleSmall    = TextStyle(fontFamily = InterFontFamily, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.05.sp),
    bodyLarge     = TextStyle(fontFamily = InterFontFamily, fontSize = 16.sp, lineHeight = 25.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.15.sp),
    bodyMedium    = TextStyle(fontFamily = InterFontFamily, fontSize = 14.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.1.sp),
    bodySmall     = TextStyle(fontFamily = InterFontFamily, fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.2.sp),
    labelLarge    = TextStyle(fontFamily = InterFontFamily, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.05.sp),
    labelMedium   = TextStyle(fontFamily = InterFontFamily, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp),
    labelSmall    = TextStyle(fontFamily = InterFontFamily, fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
)

// ── Theme modes ──────────────────────────────────────────────────────────────

/** Theme preference — stored via SharedPreferences by SettingsScreen. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Composable
fun ADAMessengerTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val useDark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT  -> false
        ThemeMode.DARK   -> true
    }

    val colorScheme = when {
        // Dynamic color on Android 12+ (Material You) — user can disable in Settings
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (useDark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        useDark -> DarkColorScheme
        else    -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography  = AdaTypography,
        content     = content,
    )
}
