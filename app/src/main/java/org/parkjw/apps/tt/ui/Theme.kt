package org.parkjw.apps.tt

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Role-based palette: solid surfaces, restrained accent, calm dividers.
 * Published role values + neutral grays chosen as an explicit product decision.
 */
@Immutable
data class TtColors(
    val background: Color,
    val onBackground: Color,
    val subText: Color,
    val divider: Color,
    val accent: Color,
    val onAccent: Color,
    val activated: Color,
    val grayButton: Color,
    val onGrayButton: Color,
)

private val LightColors = TtColors(
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF000000),
    subText = Color(0xFF707070),
    divider = Color(0x14000000),
    accent = Color(0xFF0072DE),
    onAccent = Color(0xFFFAFAFA),
    activated = Color(0xFF3E91FF),
    grayButton = Color(0xFFEDEDED),
    onGrayButton = Color(0xFF000000),
)

private val DarkColors = TtColors(
    background = Color(0xFF080808),
    onBackground = Color(0xFFFAFAFA),
    subText = Color(0xFFA6A6A6),
    divider = Color(0x1AFAFAFA),
    accent = Color(0xFF3E91FF),
    onAccent = Color(0xFF080808),
    activated = Color(0xFF3E91FF),
    grayButton = Color(0xFF1E1E1E),
    onGrayButton = Color(0xFFFAFAFA),
)

private val LocalTtColors = staticCompositionLocalOf { LightColors }

@Composable
fun ttColors(): TtColors = LocalTtColors.current

@Composable
fun TTTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) DarkColors else LightColors
    // Keep the surrounding M3 components in agreement with the role palette.
    val scheme = if (dark) {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            background = colors.background,
            onBackground = colors.onBackground,
            surface = colors.background,
            onSurface = colors.onBackground,
            surfaceVariant = colors.grayButton,
            onSurfaceVariant = colors.subText,
            error = Color(0xFFFF6B5E),
        )
    } else {
        lightColorScheme(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            background = colors.background,
            onBackground = colors.onBackground,
            surface = colors.background,
            onSurface = colors.onBackground,
            surfaceVariant = colors.grayButton,
            onSurfaceVariant = colors.subText,
            error = Color(0xFFD93025),
        )
    }
    CompositionLocalProvider(LocalTtColors provides colors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
