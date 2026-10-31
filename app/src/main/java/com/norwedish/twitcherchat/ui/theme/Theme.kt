package com.norwedish.twitcherchat.ui.theme

/**
 * App theme definitions and top-level Theme composable used by the app to apply Material theming.
 *
 * The app ships a custom Twitch-inspired palette. Dynamic (wallpaper) colors are supported
 * but disabled by default so the brand identity stays consistent.
 */

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = TwitchPurple,
    onPrimary = Color.White,
    primaryContainer = TwitchPurpleDeep,
    onPrimaryContainer = TwitchPurpleSoft,
    secondary = TwitchPurpleSoft,
    onSecondary = InkBlack,
    secondaryContainer = SurfaceDarkHigh,
    onSecondaryContainer = OnDark,
    tertiary = AccentMint,
    onTertiary = InkBlack,
    tertiaryContainer = Color(0xFF0E3B2E),
    onTertiaryContainer = AccentMint,
    background = InkBlack,
    onBackground = OnDark,
    surface = SurfaceDark,
    onSurface = OnDark,
    surfaceVariant = SurfaceDarkVariant,
    onSurfaceVariant = OnDarkMuted,
    surfaceTint = TwitchPurple,
    outline = OutlineDark,
    outlineVariant = SurfaceDarkHigh,
    error = LiveRedSoft,
    onError = Color.White,
    errorContainer = Color(0xFF4A1420),
    onErrorContainer = Color(0xFFFFD9DC),
    inverseSurface = OnDark,
    inverseOnSurface = InkBlack,
    scrim = Color.Black,
)

private val LightColorScheme = lightColorScheme(
    primary = TwitchPurpleBright,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE9DDFF),
    onPrimaryContainer = Color(0xFF2A0A63),
    secondary = Color(0xFF625B71),
    onSecondary = Color.White,
    secondaryContainer = SurfaceLightVariant,
    onSecondaryContainer = OnLight,
    tertiary = Color(0xFF00796B),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFB2F5EA),
    onTertiaryContainer = Color(0xFF00382F),
    background = PaperWhite,
    onBackground = OnLight,
    surface = SurfaceLight,
    onSurface = OnLight,
    surfaceVariant = SurfaceLightVariant,
    onSurfaceVariant = OnLightMuted,
    surfaceTint = TwitchPurpleBright,
    outline = OutlineLight,
    outlineVariant = Color(0xFFE4E0EE),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    inverseSurface = Color(0xFF322F3B),
    inverseOnSurface = Color(0xFFF4EFF7),
    scrim = Color.Black,
)

@Composable
fun TwitcherChatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+, but off by default to keep the brand palette
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes,
        content = content
    )
}
