package com.ada.messenger.desktop.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF6B9EFF),
    onPrimary = Color(0xFF082954),
    primaryContainer = Color(0xFF183A69),
    onPrimaryContainer = Color(0xFFD9E6FF),
    secondary = Color(0xFF8FB6D9),
    onSecondary = Color(0xFF0C2A42),
    secondaryContainer = Color(0xFF203548),
    onSecondaryContainer = Color(0xFFD7E7F7),
    tertiary = Color(0xFF7FC7B4),
    onTertiary = Color(0xFF0D342B),
    tertiaryContainer = Color(0xFF1B433A),
    onTertiaryContainer = Color(0xFFD6F2EA),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    background = Color(0xFF111417),
    onBackground = Color(0xFFE7EBEF),
    surface = Color(0xFF171B20),
    onSurface = Color(0xFFE7EBEF),
    surfaceVariant = Color(0xFF20262D),
    onSurfaceVariant = Color(0xFFABB5C0),
    outline = Color(0xFF6C7681),
    outlineVariant = Color(0xFF303740),
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF2D6CDF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E6FF),
    onPrimaryContainer = Color(0xFF0A2852),
    secondary = Color(0xFF386A93),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD9E7F5),
    onSecondaryContainer = Color(0xFF112B3F),
    tertiary = Color(0xFF25695B),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD3F0E7),
    onTertiaryContainer = Color(0xFF072C24),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    background = Color(0xFFF3F5F7),
    onBackground = Color(0xFF171C22),
    surface = Color.White,
    onSurface = Color(0xFF171C22),
    surfaceVariant = Color(0xFFE6EBF0),
    onSurfaceVariant = Color(0xFF56606B),
    outline = Color(0xFF717B86),
    outlineVariant = Color(0xFFD3DAE2),
)

private val AdaTypography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 48.sp, lineHeight = 54.sp),
    displayMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 38.sp, lineHeight = 44.sp),
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 30.sp, lineHeight = 36.sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp, lineHeight = 18.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Medium),
)

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

// ---- Desktop colour tokens (supplement to Material3 colour scheme) ----

data class DesktopColorTokens(
    val navRailBg: Color,
    val conversationListBg: Color,
    val chatAreaBg: Color,
    val myBubbleBg: Color,
    val myBubbleText: Color,
    val theirBubbleBg: Color,
    val theirBubbleText: Color,
    val onlineIndicator: Color,
    val avatarColors: List<Color>,
)

private val darkDesktopColors = DesktopColorTokens(
    navRailBg = Color(0xFF0D1014),
    conversationListBg = Color(0xFF111417),
    chatAreaBg = Color(0xFF171B20),
    myBubbleBg = Color(0xFF183A69),
    myBubbleText = Color(0xFFD9E6FF),
    theirBubbleBg = Color(0xFF20262D),
    theirBubbleText = Color(0xFFE7EBEF),
    onlineIndicator = Color(0xFF4CAF50),
    avatarColors = listOf(
        Color(0xFF1565C0),
        Color(0xFF6A1B9A),
        Color(0xFFC62828),
        Color(0xFF2E7D32),
        Color(0xFFE65100),
        Color(0xFF00695C),
        Color(0xFF283593),
        Color(0xFF558B2F),
    ),
)

private val lightDesktopColors = DesktopColorTokens(
    navRailBg = Color(0xFFE4E9EF),
    conversationListBg = Color(0xFFF3F5F7),
    chatAreaBg = Color(0xFFFFFFFF),
    myBubbleBg = Color(0xFFD9E6FF),
    myBubbleText = Color(0xFF0A2852),
    theirBubbleBg = Color(0xFFE6EBF0),
    theirBubbleText = Color(0xFF171C22),
    onlineIndicator = Color(0xFF2E7D32),
    avatarColors = listOf(
        Color(0xFF1565C0),
        Color(0xFF6A1B9A),
        Color(0xFFC62828),
        Color(0xFF2E7D32),
        Color(0xFFE65100),
        Color(0xFF00695C),
        Color(0xFF283593),
        Color(0xFF558B2F),
    ),
)

val LocalDesktopColors = staticCompositionLocalOf { darkDesktopColors }

@Composable
fun ADAMessengerDesktopTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val useDark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    CompositionLocalProvider(LocalDesktopColors provides if (useDark) darkDesktopColors else lightDesktopColors) {
        MaterialTheme(
            colorScheme = if (useDark) DarkColorScheme else LightColorScheme,
            typography = AdaTypography,
            content = content,
        )
    }
}
