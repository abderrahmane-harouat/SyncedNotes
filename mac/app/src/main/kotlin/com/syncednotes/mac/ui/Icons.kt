package com.syncednotes.mac.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The app's icons, drawn here as outlines on a 24-point grid in the manner of SF Symbols. SF
 * Symbols themselves come only through Apple's frameworks, and their licence keeps them there.
 */
object Icons {
    private fun icon(name: String, vararg strokes: String, filled: List<String> = emptyList()): ImageVector {
        val builder = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for (d in strokes) {
            builder.addPath(
                pathData = addPathNodes(d),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        for (d in filled) builder.addPath(pathData = addPathNodes(d), fill = SolidColor(Color.Black))
        return builder.build()
    }

    val lock = icon("lock", "M6.5 11h11a1.5 1.5 0 0 1 1.5 1.5v7a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 5 19.5v-7A1.5 1.5 0 0 1 6.5 11z", "M8 11V7.5a4 4 0 0 1 8 0V11")
    val lockFilled = icon("lock.fill", "M8 11V7.5a4 4 0 0 1 8 0V11", filled = listOf("M6.5 10.2h11a2.3 2.3 0 0 1 2.3 2.3v7a2.3 2.3 0 0 1-2.3 2.3h-11a2.3 2.3 0 0 1-2.3-2.3v-7a2.3 2.3 0 0 1 2.3-2.3z"))
    val key = icon("key", "M11.5 15.5a3.75 3.75 0 1 1-7.5 0a3.75 3.75 0 1 1 7.5 0z", "M10.4 12.9L19 4.3", "M15.8 7.5l2.2 2.2", "M13.6 9.7l1.6 1.6")
    val compose = icon("square.and.pencil", "M11 4H6.5A2.5 2.5 0 0 0 4 6.5v11A2.5 2.5 0 0 0 6.5 20h11a2.5 2.5 0 0 0 2.5-2.5V13", "M17.6 3.6a2 2 0 0 1 2.8 2.8L12 14.8l-3.6 0.8 0.8-3.6z")
    val folder = icon("folder", "M3.5 7.5A2 2 0 0 1 5.5 5.5h3.6l2 2h7.4a2 2 0 0 1 2 2v7.5a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2z")
    val folderPlus = icon("folder.badge.plus", "M3.5 7.5A2 2 0 0 1 5.5 5.5h3.6l2 2h7.4a2 2 0 0 1 2 2v7.5a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2z", "M12 10.5v5", "M9.5 13h5")
    val tray = icon("tray.full", "M3.5 13.5L6.2 5.5h11.6l2.7 8v4.5a1.5 1.5 0 0 1-1.5 1.5h-14a1.5 1.5 0 0 1-1.5-1.5z", "M3.5 13.5h4.8l1.2 2h5l1.2-2h4.8", "M8.5 9h7", "M8 11.2h8")
    val note = icon("note.text", "M6.5 3.5h11A1.5 1.5 0 0 1 19 5v14a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 5 19V5a1.5 1.5 0 0 1 1.5-1.5z", "M8.5 8.5h7", "M8.5 12h7", "M8.5 15.5h4.5")
    val trash = icon("trash", "M4.5 7h15", "M9 7V5a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2", "M6.5 7l0.9 12a2 2 0 0 0 2 1.8h5.2a2 2 0 0 0 2-1.8L17.5 7", "M10.2 10.5v6", "M13.8 10.5v6")
    val search = icon("magnifyingglass", "M17 10.5a6.5 6.5 0 1 1-13 0a6.5 6.5 0 1 1 13 0z", "M15.3 15.3L20 20")
    val eye = icon("eye", "M2.5 12s3.5-6.5 9.5-6.5S21.5 12 21.5 12s-3.5 6.5-9.5 6.5S2.5 12 2.5 12z", "M15 12a3 3 0 1 1-6 0a3 3 0 1 1 6 0z")
    val eyeSlash = icon("eye.slash", "M2.5 12s3.5-6.5 9.5-6.5S21.5 12 21.5 12s-3.5 6.5-9.5 6.5S2.5 12 2.5 12z", "M15 12a3 3 0 1 1-6 0a3 3 0 1 1 6 0z", "M4 4l16 16")
    val drive = icon("externaldrive", "M3.5 14h17v3.5a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2z", "M3.5 14l2.6-7.5h11.8L20.5 14", filled = listOf("M16.2 16.75a1 1 0 1 0 2 0a1 1 0 1 0-2 0z"))
    val chevronRight = icon("chevron.right", "M9.5 6l6 6-6 6")
    val chevronDown = icon("chevron.down", "M6 9.5l6 6 6-6")
    val chevronUp = icon("chevron.up", "M6 14.5l6-6 6 6")
    val restore = icon("arrow.uturn.backward", "M9 14L4.5 9.5 9 5", "M4.5 9.5h10a5 5 0 0 1 0 10H11")
    val stack = icon("square.stack", "M7 8.5h12a1 1 0 0 1 1 1V19a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V9.5a1 1 0 0 1 1-1z", "M8.5 5.5h9", "M10 3h6")
    val bullets = icon("list.bullet", "M9.5 6.5h10", "M9.5 12h10", "M9.5 17.5h10", filled = listOf(
        "M3.8 6.5a1.3 1.3 0 1 0 2.6 0a1.3 1.3 0 1 0-2.6 0z", "M3.8 12a1.3 1.3 0 1 0 2.6 0a1.3 1.3 0 1 0-2.6 0z", "M3.8 17.5a1.3 1.3 0 1 0 2.6 0a1.3 1.3 0 1 0-2.6 0z",
    ))
    val numbers = icon("list.number", "M10 6.5h9.5", "M10 12h9.5", "M10 17.5h9.5", "M4.5 5l1.5-1v5", "M4.2 11.2a1.4 1.4 0 0 1 2.6 0.6c0 1-2.6 2-2.6 3.2h2.8", "M4.3 16.6h2.4l-1.2 1.5a1.3 1.3 0 1 1-1.3 1.6")
    val checklist = icon("checklist", "M10.5 7h9", "M10.5 17h9", "M7.5 7a2.5 2.5 0 1 1-5 0a2.5 2.5 0 1 1 5 0z", "M3.6 16.8l1.3 1.3 2.6-2.8")
    val sidebar = icon("sidebar.left", "M5 5h14a1.5 1.5 0 0 1 1.5 1.5v11A1.5 1.5 0 0 1 19 19H5a1.5 1.5 0 0 1-1.5-1.5v-11A1.5 1.5 0 0 1 5 5z", "M9.5 5v14", "M5.8 8.5h1.6", "M5.8 11h1.6")
    /** A phone with the two arrows of syncing beside it: Pair a Phone, and how sync stands. */
    val phoneSync = icon(
        "iphone.radiowaves",
        "M9 3.5h6a1.5 1.5 0 0 1 1.5 1.5v14a1.5 1.5 0 0 1-1.5 1.5H9A1.5 1.5 0 0 1 7.5 19V5A1.5 1.5 0 0 1 9 3.5z",
        "M11 6h2",
        "M19.5 9a4.5 4.5 0 0 1 0 6",
        "M4.5 15a4.5 4.5 0 0 1 0-6",
    )
    val photo = icon(
        "photo",
        "M5 5h14a1.5 1.5 0 0 1 1.5 1.5v11A1.5 1.5 0 0 1 19 19H5a1.5 1.5 0 0 1-1.5-1.5v-11A1.5 1.5 0 0 1 5 5z",
        "M3.5 16l5-5 4 4 2.5-2.5 5.5 5",
        "M16.5 9a1.5 1.5 0 1 1-3 0a1.5 1.5 0 1 1 3 0z",
    )
    val undo = icon("arrow.uturn.backward", "M9 14L4.5 9.5 9 5", "M4.5 9.5h10a5 5 0 0 1 0 10H11")
    val redo = icon("arrow.uturn.forward", "M15 14l4.5-4.5L15 5", "M19.5 9.5h-10a5 5 0 0 0 0 10H13")
    val appearance = icon("circle.lefthalf.filled", "M20.5 12a8.5 8.5 0 1 1-17 0a8.5 8.5 0 1 1 17 0z", filled = listOf("M12 3.5a8.5 8.5 0 0 0 0 17z"))
    /** A rounded card with lines of text on it: a callout, text set apart on a panel. */
    val callout = icon(
        "rectangle.text",
        "M5.5 5h13A2.5 2.5 0 0 1 21 7.5v9a2.5 2.5 0 0 1-2.5 2.5h-13A2.5 2.5 0 0 1 3 16.5v-9A2.5 2.5 0 0 1 5.5 5z",
        "M7 9.5h10", "M7 13h6.5",
    )
    /** A table: a rounded frame, its header row ruled off, its columns apart. */
    val table = icon(
        "tablecells",
        "M5.5 4.5h13A2.5 2.5 0 0 1 21 7v10a2.5 2.5 0 0 1-2.5 2.5h-13A2.5 2.5 0 0 1 3 17V7a2.5 2.5 0 0 1 2.5-2.5z",
        "M3 9.5h18", "M3 14.5h18", "M9.5 9.5v10", "M15 9.5v10",
    )
    /** Two sparkles: an AI agent, as the folder shared with one is marked. */
    val agent = icon(
        "sparkles", filled = listOf(
            "M10 3.5c.7 4.3 2.2 5.8 6.5 6.5-4.3.7-5.8 2.2-6.5 6.5-.7-4.3-2.2-5.8-6.5-6.5 4.3-.7 5.8-2.2 6.5-6.5z",
            "M17.5 13.5c.35 2.1 1.1 2.85 3.2 3.2-2.1.35-2.85 1.1-3.2 3.2-.35-2.1-1.1-2.85-3.2-3.2 2.1-.35 2.85-1.1 3.2-3.2z",
        ),
    )
    /** Angle brackets and a slash: code, as code editors mark it. */
    val code = icon("chevron.left.forwardslash.chevron.right", "M8 7.5L3.5 12 8 16.5", "M16 7.5l4.5 4.5-4.5 4.5", "M13.6 5l-3.2 14")
    /** A marker with its chisel tip down, drawn above the colour bar the toolbar puts under it. */
    val marker = icon("highlighter", "M15.2 3.2l4.6 4.6-7.4 7.4-4.6-4.6z", "M7.8 10.6l-1.6 4.9 2.3 2.3 4.9-1.6")
    val highlighter = icon("highlighter", "M14.5 4.5l5 5-8 8-5-5z", "M6.5 12.5l-2 4.5 2.5 2.5 4.5-2", "M3.5 20.5h6")
}

/** An icon in [tint], [size] points square. */
@Composable
fun Icon(vector: ImageVector, tint: Color, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    Image(rememberVectorPainter(vector), contentDescription = null, modifier = modifier.size(size), colorFilter = ColorFilter.tint(tint))
}
