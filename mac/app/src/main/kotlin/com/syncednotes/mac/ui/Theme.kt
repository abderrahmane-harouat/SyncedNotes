package com.syncednotes.mac.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The window's colours, light and dark, taken from the Mac's own: the SwiftUI app used the
 * system's, and this keeps that look without asking AppKit for them.
 */
data class MacColors(
    val dark: Boolean,
    val window: Color,
    val sidebar: Color,
    val separator: Color,
    val text: Color,
    val secondaryText: Color,
    val tertiaryText: Color,
    val accent: Color,
    /** Behind a tool that is on: the accent, faint. */
    val accentSoft: Color,
    /**
     * A tool's icon: darker than secondary text, which made every tool look switched off, and
     * lighter than the text, so the note stays what the eye goes to.
     */
    val icon: Color,
    val selection: Color,
    val selectionInactive: Color,
    val sidebarSelection: Color,
    val field: Color,
    val fieldBorder: Color,
    val control: Color,
    val controlPressed: Color,
    val danger: Color,
    val scrim: Color,
    val card: Color,
    val banner: Color,
)

val LightColors = MacColors(
    dark = false,
    window = Color(0xFFFFFFFF),
    sidebar = Color(0xFFEFEFF1),
    separator = Color(0x1A000000),
    text = Color(0xFF1D1D1F),
    secondaryText = Color(0xFF86868B),
    tertiaryText = Color(0xFFB0B0B5),
    accent = Color(0xFF007AFF),
    accentSoft = Color(0x24007AFF),
    icon = Color(0xFF4A4A4F),
    selection = Color(0xFF007AFF),
    selectionInactive = Color(0xFFDCDCE0),
    sidebarSelection = Color(0x14000000),
    field = Color(0xFFFFFFFF),
    fieldBorder = Color(0x26000000),
    control = Color(0x0D000000),
    controlPressed = Color(0x1F000000),
    danger = Color(0xFFFF3B30),
    scrim = Color(0x33000000),
    card = Color(0xFFF5F5F7),
    banner = Color(0xFFF2F2F4),
)

val DarkColors = MacColors(
    dark = true,
    window = Color(0xFF1E1E1E),
    sidebar = Color(0xFF2A2A2C),
    separator = Color(0x33FFFFFF),
    text = Color(0xFFF5F5F7),
    secondaryText = Color(0xFF98989D),
    tertiaryText = Color(0xFF636366),
    accent = Color(0xFF0A84FF),
    accentSoft = Color(0x330A84FF),
    icon = Color(0xFFC7C7CC),
    selection = Color(0xFF0A84FF),
    selectionInactive = Color(0xFF3A3A3C),
    sidebarSelection = Color(0x1FFFFFFF),
    field = Color(0xFF2C2C2E),
    fieldBorder = Color(0x33FFFFFF),
    control = Color(0x14FFFFFF),
    controlPressed = Color(0x29FFFFFF),
    danger = Color(0xFFFF453A),
    scrim = Color(0x66000000),
    card = Color(0xFF2C2C2E),
    banner = Color(0xFF2A2A2C),
)

/** Text sizes of the Mac's own controls: 13 for most, 11 for small print. */
object MacType {
    val body = TextStyle(fontSize = 13.sp)
    val bodyStrong = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    val small = TextStyle(fontSize = 11.sp)
    val section = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    val title = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold)
    val headline = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
}

/**
 * The height of the row of buttons at the top of each column. The window's content runs under
 * its transparent title bar, whose top 28 points belong to the window: two quick clicks there
 * zoom it, whatever is drawn. So the buttons sit in a row below that strip.
 */
val HEADER_HEIGHT = 64.dp
val TITLE_BAR = 28.dp

val LocalMacColors = staticCompositionLocalOf { LightColors }

/** The current colours. */
val colors: MacColors @Composable get() = LocalMacColors.current

@Composable
fun MacTheme(dark: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalMacColors provides if (dark) DarkColors else LightColors, content = content)
}
