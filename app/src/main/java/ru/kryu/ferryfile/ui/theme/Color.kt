package ru.kryu.ferryfile.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Broadsheet design tokens — see docs/superpowers/specs/2026-09-16-broadsheet-restyle/README.md
 * for the light values (copied from the design system's styles.css) and the dark ink cut,
 * which the spec calls a proposal, not a shipped token set.
 */
data class BroadsheetColors(
    val bg: Color,
    val surface: Color,
    val text: Color,
    val accent: Color,
    val accent2: Color,
    val accent700: Color,
    val accent2700: Color,
    val neutral300: Color,
    val neutral600: Color,
    val neutral700: Color,
    val neutral800: Color,
    val divider: Color
)

val LightBroadsheetColors = BroadsheetColors(
    bg = Color(0xFFEAE9E9),
    surface = Color(0xFFEAE9E9),
    text = Color(0xFF201E1D),
    accent = Color(0xFF201E1D),
    accent2 = Color(0xFFD6006C),
    accent700 = Color(0xFF006786),
    accent2700 = Color(0xFFAA0B56),
    neutral300 = Color(0xFFD7D3D3),
    neutral600 = Color(0xFF7D7979),
    neutral700 = Color(0xFF605D5D),
    neutral800 = Color(0xFF444141),
    divider = Color(0xFF201E1D).copy(alpha = 0.16f)
)

// Dark ink cut: Broadsheet ships no dark surfaces. neutral300 and neutral600 are derived
// (collapsed onto the nearest tone the spec does give) rather than taken from the system.
val DarkBroadsheetColors = BroadsheetColors(
    bg = Color(0xFF201E1D),
    surface = Color(0xFF2D2B2B),
    text = Color(0xFFF3F2F2),
    accent = Color(0xFF62C5EE),
    accent2 = Color(0xFFFF90B1),
    accent700 = Color(0xFF99E0FF),
    accent2700 = Color(0xFFFF90B1),
    neutral300 = Color(0xFF2D2B2B),
    neutral600 = Color(0xFFBAB6B6),
    neutral700 = Color(0xFFBAB6B6),
    neutral800 = Color(0xFFD7D3D3),
    divider = Color(0xFFF3F2F2).copy(alpha = 0.22f)
)
