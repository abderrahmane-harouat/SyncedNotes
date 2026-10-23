package com.syncednotes.mac.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicSecureTextField
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.syncednotes.mac.SecureInput

/**
 * A push button: plain, or the window's default action in the accent colour. [large] for the
 * one button of a screen of its own, such as Unlock, where the Mac's small size looks lost.
 */
@Composable
fun MacButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    destructive: Boolean = false,
    enabled: Boolean = true,
    large: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val c = colors
    val background = when {
        primary && enabled -> if (pressed) c.accent.copy(alpha = 0.8f) else c.accent
        pressed -> c.controlPressed
        else -> c.control
    }
    val foreground = when {
        !enabled -> c.tertiaryText
        primary -> Color.White
        destructive -> c.danger
        else -> c.text
    }
    Box(
        modifier
            .height(if (large) 40.dp else 26.dp)
            .clip(RoundedCornerShape(if (large) 10.dp else 7.dp))
            .background(background)
            .clickable(interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        val style = if (large) MacType.body.copy(fontSize = 15.sp) else MacType.body
        BasicText(text, style = style.copy(color = foreground, fontWeight = if (primary) FontWeight.Medium else FontWeight.Normal))
    }
}

/** A toolbar button: an icon that shows a soft square when hovered, and when [selected]. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ToolbarButton(
    icon: ImageVector,
    help: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    selected: Boolean = false,
    tint: Color? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val c = colors
    Tooltip(help) {
        Box(
            Modifier
                .size(30.dp, 28.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(
                    when {
                        selected -> c.controlPressed
                        hovered && enabled -> c.control
                        else -> Color.Transparent
                    },
                )
                .hoverable(interaction)
                .clickable(interaction, indication = null, enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, if (enabled) tint ?: c.secondaryText else c.tertiaryText, size = 18.dp)
        }
    }
}

/** A tooltip after a moment's hover, as the Mac's help tags. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Tooltip(text: String, content: @Composable () -> Unit) {
    TooltipArea(
        tooltip = {
            Box(
                Modifier.shadow(4.dp, RoundedCornerShape(5.dp)).background(colors.card, RoundedCornerShape(5.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) { BasicText(text, style = MacType.small.copy(color = colors.text)) }
        },
        delayMillis = 700,
        tooltipPlacement = androidx.compose.foundation.TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 18.dp)),
        content = content,
    )
}

/**
 * The app's one-line text field, for folder names: Compose's own field, which offers no text
 * service of the system's (Privacy.md), drawn like the Mac's rounded field.
 */
@Composable
fun PlainTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onSubmit: () -> Unit = {},
    onCancel: () -> Unit = {},
    /** Called when the field had the keyboard and loses it: a click elsewhere. */
    onFocusLost: () -> Unit = {},
    focusRequester: FocusRequester = remember { FocusRequester() },
    leading: (@Composable () -> Unit)? = null,
    style: TextStyle = MacType.body,
) {
    val c = colors
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = style.copy(color = c.text),
        cursorBrush = SolidColor(c.accent),
        modifier = modifier
            .focusRequester(focusRequester)
            .onFocusChanged {
                if (focused && !it.isFocused) onFocusLost()
                focused = it.isFocused
            }
            .onPreviewKeyEvent { submitOrCancel(it, onSubmit, onCancel) },
        decorationBox = { inner ->
            Row(
                Modifier
                    .height(26.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(c.field)
                    .border(if (focused) 2.dp else 1.dp, if (focused) c.accent.copy(alpha = 0.6f) else c.fieldBorder, RoundedCornerShape(7.dp))
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                leading?.invoke()
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) BasicText(placeholder, style = style.copy(color = c.tertiaryText), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
            }
        },
    )
}

/** The search field above the list: the app's own field with a magnifying glass. */
@Composable
fun SearchField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    PlainTextField(
        value, onValueChange, placeholder, modifier,
        onCancel = { onValueChange("") },
        leading = { Icon(Icons.search, colors.secondaryText, size = 14.dp) },
    )
}

/**
 * A passphrase field with an eye button that shows what was typed, and hides it again. Compose's
 * secure field: it never copies its text, shown or hidden.
 */
@Composable
fun PassphraseField(
    state: TextFieldState,
    placeholder: String,
    modifier: Modifier = Modifier,
    onSubmit: () -> Unit = {},
    focusRequester: FocusRequester = remember { FocusRequester() },
    /** For the lock screen, where the field is the whole screen's business. */
    large: Boolean = false,
) {
    val c = colors
    var shown by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val corner = RoundedCornerShape(if (large) 10.dp else 7.dp)
    val text = if (large) MacType.body.copy(fontSize = 15.sp) else MacType.body
    Row(
        modifier
            .fillMaxWidth()
            .height(if (large) 40.dp else 28.dp)
            .clip(corner)
            .background(c.field)
            .border(if (focused) 2.dp else 1.dp, if (focused) c.accent.copy(alpha = 0.6f) else c.fieldBorder, corner)
            .padding(start = if (large) 12.dp else 8.dp, end = if (large) 6.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            if (state.text.isEmpty()) BasicText(placeholder, style = text.copy(color = c.tertiaryText), maxLines = 1)
            BasicSecureTextField(
                state = state,
                textStyle = text.copy(color = c.text),
                cursorBrush = SolidColor(c.accent),
                textObfuscationMode = if (shown) TextObfuscationMode.Visible else TextObfuscationMode.Hidden,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { focused = it.isFocused; SecureInput.hold(it.isFocused) }
                    .onPreviewKeyEvent { submitOrCancel(it, onSubmit, {}) },
            )
        }
        Tooltip(if (shown) "Hide Passphrase" else "Show Passphrase") {
            Box(
                Modifier.size(if (large) 30.dp else 24.dp).clip(RoundedCornerShape(5.dp)).clickable { shown = !shown }.pointerHoverIcon(PointerIcon.Default),
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (shown) Icons.eyeSlash else Icons.eye, c.secondaryText, size = if (large) 19.dp else 16.dp)
            }
        }
    }
}

private fun submitOrCancel(event: androidx.compose.ui.input.key.KeyEvent, onSubmit: () -> Unit, onCancel: () -> Unit): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    return when (event.key) {
        Key.Enter, Key.NumPadEnter -> { onSubmit(); true }
        Key.Escape -> { onCancel(); true }
        else -> false
    }
}

/**
 * A sheet over the window: the rest of the window dimmed, and a card in the middle. Clicks on
 * the dimmed part do nothing, as on the Mac, where a sheet must be answered.
 */
@Composable
fun Sheet(width: androidx.compose.ui.unit.Dp = 400.dp, content: @Composable () -> Unit) {
    val c = colors
    Box(
        Modifier
            .fillMaxSize()
            .background(c.scrim)
            .clickable(remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(width)
                .shadow(24.dp, RoundedCornerShape(12.dp))
                .background(c.window, RoundedCornerShape(12.dp))
                .padding(24.dp),
        ) { content() }
    }
}

/** One line of a [MenuPopup]: an action, or a separator when [title] is null. */
class MenuItem(val title: String?, val enabled: Boolean = true, val checked: Boolean = false, val destructive: Boolean = false, val action: () -> Unit = {}) {
    companion object {
        val SEPARATOR = MenuItem(null)
    }
}

/**
 * A menu that opens under what was clicked, drawn like the Mac's: rounded, with a soft
 * shadow, items highlighted in the accent colour as the pointer passes over them.
 */
@Composable
fun MenuPopup(expanded: Boolean, onDismiss: () -> Unit, items: List<MenuItem>, offset: androidx.compose.ui.unit.IntOffset = androidx.compose.ui.unit.IntOffset(0, 0)) {
    if (!expanded) return
    val c = colors
    androidx.compose.ui.window.Popup(
        offset = offset,
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.PopupProperties(focusable = true),
    ) {
        androidx.compose.foundation.layout.Column(
            Modifier
                .width(androidx.compose.foundation.layout.IntrinsicSize.Max)
                .shadow(12.dp, RoundedCornerShape(8.dp))
                .background(c.card, RoundedCornerShape(8.dp))
                .border(0.5.dp, c.separator, RoundedCornerShape(8.dp))
                .padding(5.dp),
        ) {
            for (item in items) {
                if (item.title == null) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 8.dp).height(1.dp).background(c.separator))
                    continue
                }
                val interaction = remember { MutableInteractionSource() }
                val hovered by interaction.collectIsHoveredAsState()
                val lit = hovered && item.enabled
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(24.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(if (lit) c.accent else Color.Transparent)
                        .hoverable(interaction)
                        .clickable(interaction, indication = null, enabled = item.enabled) {
                            onDismiss()
                            item.action()
                        }
                        .padding(start = 6.dp, end = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(16.dp)) {
                        if (item.checked) BasicText("✓", style = MacType.body.copy(color = if (lit) Color.White else c.text))
                    }
                    BasicText(
                        item.title,
                        style = MacType.body.copy(
                            color = when {
                                !item.enabled -> c.tertiaryText
                                lit -> Color.White
                                item.destructive -> c.danger
                                else -> c.text
                            },
                        ),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * One line of text, cut short with an ellipsis when it does not fit, and then shown whole in a
 * tooltip when the pointer rests on it.
 */
@Composable
fun TruncatedText(text: String, modifier: Modifier = Modifier, style: TextStyle) {
    var cut by remember(text) { mutableStateOf(false) }
    val line = @Composable {
        BasicText(text, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis, onTextLayout = { cut = it.hasVisualOverflow })
    }
    Box(modifier) { if (cut) Tooltip(text) { line() } else line() }
}
