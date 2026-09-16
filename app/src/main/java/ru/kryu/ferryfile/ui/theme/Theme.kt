package ru.kryu.ferryfile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

val LocalBroadsheetColors = staticCompositionLocalOf { LightBroadsheetColors }

object BroadsheetTheme {
    val colors: BroadsheetColors
        @Composable get() = LocalBroadsheetColors.current
}

@Composable
fun FerryFileTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val broadsheetColors = if (darkTheme) DarkBroadsheetColors else LightBroadsheetColors

    // Broadsheet's tokens don't map onto M3's container roles, so this scheme only backs
    // the handful of stock Material3 components still in use (OutlinedButton, BasicTextField
    // cursor default, etc). Screens read BroadsheetTheme.colors directly for everything else.
    val colorScheme = if (darkTheme) {
        darkColorScheme(
            primary = broadsheetColors.accent,
            onPrimary = broadsheetColors.bg,
            background = broadsheetColors.bg,
            onBackground = broadsheetColors.text,
            surface = broadsheetColors.surface,
            onSurface = broadsheetColors.text,
            error = broadsheetColors.accent2700,
            outline = broadsheetColors.divider
        )
    } else {
        lightColorScheme(
            primary = broadsheetColors.accent,
            onPrimary = broadsheetColors.bg,
            background = broadsheetColors.bg,
            onBackground = broadsheetColors.text,
            surface = broadsheetColors.surface,
            onSurface = broadsheetColors.text,
            error = broadsheetColors.accent2700,
            outline = broadsheetColors.divider
        )
    }

    CompositionLocalProvider(LocalBroadsheetColors provides broadsheetColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
