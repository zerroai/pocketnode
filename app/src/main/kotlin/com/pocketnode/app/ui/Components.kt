package com.pocketnode.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog

/** The mark: a ring open at the upper right with a dot in the gap. Geometry matches brand/make_assets.py. */
@Composable
fun Logo(size: Dp, color: Color = MC.colors.text, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val k = this.size.width / 108f
        drawArc(
            color = color, startAngle = -15f, sweepAngle = 300f, useCenter = false,
            topLeft = Offset(24f * k, 24f * k), size = Size(60f * k, 60f * k),
            style = Stroke(width = 9f * k, cap = StrokeCap.Round),
        )
        drawCircle(color, radius = 8f * k, center = Offset(75.21f * k, 32.79f * k))
    }
}

/** A tappable surface with no ripple. [content] receives whether the surface is pressed. */
@Composable
fun Pressable(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, onLongClick: (() -> Unit)? = null, role: androidx.compose.ui.semantics.Role = androidx.compose.ui.semantics.Role.Button, description: String? = null, state: String? = null, content: @Composable (pressed: Boolean) -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val press = if (onLongClick == null) Modifier.clickable(interactionSource = source, indication = null, enabled = enabled, role = role, onClick = onClick)
    else Modifier.combinedClickable(interactionSource = source, indication = null, enabled = enabled, role = role, onClick = onClick, onLongClick = onLongClick)
    // The name and state go inside the click, so a screen reader finds them on the thing it can press.
    val named = if (description == null && state == null) Modifier else Modifier.semantics(mergeDescendants = true) { description?.let { contentDescription = it }; state?.let { stateDescription = it } }
    Box(modifier.then(press).then(named)) { content(pressed) }
}

/** Subtle press feedback: the surface shrinks a little while held. */
@Composable
fun Modifier.pressScale(pressed: Boolean): Modifier {
    val k by animateFloatAsState(if (pressed) 0.97f else 1f, tween(110), label = "press")
    return this.scale(k)
}

@Composable
fun Label(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier, align: TextAlign? = null, maxLines: Int = Int.MAX_VALUE) {
    BasicText(text, modifier, style.copy(color = color, textAlign = align ?: TextAlign.Unspecified), maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

/** Assistant text with the small amount of Markdown models use drawn as bold, italic, code and bullets. */
@Composable
fun RichLabel(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    val c = MC.colors
    val annotated = remember(text, color, c.fill) {
        androidx.compose.ui.text.buildAnnotatedString {
            for (seg in com.pocketnode.core.MarkdownLite.parse(text)) {
                val span = androidx.compose.ui.text.SpanStyle(
                    fontWeight = if (seg.bold) androidx.compose.ui.text.font.FontWeight.SemiBold else null,
                    fontStyle = if (seg.italic) androidx.compose.ui.text.font.FontStyle.Italic else null,
                    fontFamily = if (seg.code) androidx.compose.ui.text.font.FontFamily.Monospace else null,
                    background = if (seg.code) c.fill else Color.Unspecified,
                )
                withStyle(span) { append(seg.text) }
            }
        }
    }
    BasicText(annotated, modifier, style.copy(color = color))
}

@Composable
fun Spinner(color: Color, size: Dp = 20.dp) {
    val t = rememberInfiniteTransition(label = "spin")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "angle")
    Canvas(Modifier.size(size)) {
        val w = this.size.width * 0.11f
        rotate(angle) {
            drawArc(color, 0f, 270f, false, Offset(w / 2, w / 2), Size(this.size.width - w, this.size.height - w), style = Stroke(w, cap = StrokeCap.Round))
        }
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, loading: Boolean = false) {
    val c = MC.colors
    Pressable(onClick, modifier.fillMaxWidth(), enabled = enabled && !loading) { pressed ->
        Box(
            Modifier.fillMaxWidth().height(56.dp).pressScale(pressed).clip(RoundedCornerShape(Dim.radiusButton))
                .background(if (enabled || loading) c.ink.copy(alpha = if (pressed) 0.85f else 1f) else c.fill),
            contentAlignment = Alignment.Center,
        ) {
            if (loading) Spinner(c.onInk) else Label(text, Type.headline, if (enabled) c.onInk else c.textTertiary)
        }
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = MC.colors
    Pressable(onClick, modifier.fillMaxWidth(), enabled = enabled) { pressed ->
        Box(
            Modifier.fillMaxWidth().height(56.dp).pressScale(pressed).clip(RoundedCornerShape(Dim.radiusButton)).background(if (pressed) c.pressed else c.fill),
            contentAlignment = Alignment.Center,
        ) { Label(text, Type.headline, if (enabled) c.text else c.textTertiary) }
    }
}

@Composable
fun TextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = MC.colors.accent, enabled: Boolean = true) {
    Pressable(onClick, modifier.heightIn(min = Dim.touch), enabled = enabled) { pressed ->
        Box(Modifier.heightIn(min = Dim.touch).padding(horizontal = 8.dp).alpha(if (pressed) 0.55f else 1f), contentAlignment = Alignment.Center) {
            Label(text, Type.body, if (enabled) color else MC.colors.textTertiary)
        }
    }
}

@Composable
fun Field(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    secure: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onDone: (() -> Unit)? = null,
) {
    val c = MC.colors
    var shown by rememberSaveable { mutableStateOf(false) }
    val focus = remember { MutableInteractionSource() }
    val focused by focus.collectIsFocusedAsState()
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    Row(
        modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(Dim.radiusField)).background(c.fill)
            .border(1.5.dp, if (focused) c.accent else Color.Transparent, RoundedCornerShape(Dim.radiusField))
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Label(label, Type.footnote, c.textSecondary)
            Box {
                if (value.isEmpty() && placeholder.isNotEmpty()) Label(placeholder, Type.body, c.textTertiary, maxLines = 1)
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = Type.body.copy(color = c.text),
                    cursorBrush = SolidColor(c.accent),
                    visualTransformation = if (secure && !shown) PasswordVisualTransformation('•') else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = if (secure) KeyboardType.Password else keyboardType, imeAction = imeAction),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onDone = { onDone?.invoke(); focusManager.clearFocus() },
                        onNext = { focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down) },
                    ),
                    interactionSource = focus,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (secure && value.isNotEmpty()) TextAction(if (shown) "Hide" else "Show", { shown = !shown })
    }
}

/** A rounded container for list rows. */
@Composable
fun Group(modifier: Modifier = Modifier, color: Color = MC.colors.surface, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(Dim.radiusGroup)).background(color), content = content)
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Label(text.uppercase(), Type.footnote, MC.colors.textSecondary, modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp))
}

@Composable
fun SectionFooter(text: String, modifier: Modifier = Modifier) {
    Label(text, Type.footnote, MC.colors.textSecondary, modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
}

/** A list row. [dividerInset] is the leading inset of the separator; null hides it (use on the last row). */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    chevron: Boolean = false,
    titleColor: Color = MC.colors.text,
    onClick: (() -> Unit)? = null,
    dividerInset: Dp? = 16.dp,
) {
    val c = MC.colors
    val inner = @Composable { pressed: Boolean ->
        Row(
            Modifier.fillMaxWidth().heightIn(min = Dim.rowMin)
                .background(if (pressed) c.pressed else Color.Transparent)
                .drawBehind {
                    if (dividerInset != null) drawLine(c.separator, Offset(dividerInset.toPx(), size.height), Offset(size.width, size.height), strokeWidth = 1f)
                }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) { leading(); Spacer(Modifier.width(12.dp)) }
            // With large text a row that has both a description and a value gives the value its own line, so the description is not squeezed into a narrow column.
            val stacked = value != null && subtitle != null && androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.15f
            Column(Modifier.weight(1f)) {
                Label(title, Type.body, titleColor, maxLines = 2)
                if (stacked) Label(value!!, Type.footnote, c.textSecondary, maxLines = 1)
                if (subtitle != null) Label(subtitle, Type.subhead, c.textSecondary, maxLines = 6)
            }
            if (value != null && !stacked) { Spacer(Modifier.width(8.dp)); Label(value, Type.body, c.textSecondary, maxLines = 1) }
            if (trailing != null) { Spacer(Modifier.width(8.dp)); trailing() }
            if (chevron) { Spacer(Modifier.width(4.dp)); Icon(Glyph.Chevron, c.textTertiary, size = 20.dp) }
        }
    }
    if (onClick != null) Pressable(onClick, modifier.fillMaxWidth()) { inner(it) } else Box(modifier.fillMaxWidth()) { inner(false) }
}

@Composable
fun Avatar(glyph: Glyph, size: Dp = 44.dp) {
    val c = MC.colors
    IconTile(glyph, c.accent, c.accentSoft, size)
}

/** Selection row with a check on the trailing side. */
@Composable
fun ChoiceRow(title: String, selected: Boolean, onClick: () -> Unit, subtitle: String? = null, leading: (@Composable () -> Unit)? = null, dividerInset: Dp? = 16.dp) {
    ListRow(
        title = title, subtitle = subtitle, leading = leading, onClick = onClick, dividerInset = dividerInset,
        trailing = { if (selected) Icon(Glyph.Check, MC.colors.accent, size = 22.dp, weight = 2.25f) },
    )
}

enum class StatusKind { Info, Success, Error }

@Composable
fun InlineStatus(kind: StatusKind, text: String, modifier: Modifier = Modifier, working: Boolean = false) {
    val c = MC.colors
    val color = when (kind) { StatusKind.Info -> c.textSecondary; StatusKind.Success -> c.success; StatusKind.Error -> c.error }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            when {
                working -> Spinner(color, 16.dp)
                kind == StatusKind.Success -> Icon(Glyph.Check, color, size = 18.dp, weight = 2.25f)
                kind == StatusKind.Error -> Icon(Glyph.Alert, color, size = 18.dp)
                else -> {}
            }
        }
        Spacer(Modifier.width(8.dp))
        Label(text, Type.subhead, color, Modifier.weight(1f))
    }
}

@Composable
fun StepBar(step: Int, total: Int, modifier: Modifier = Modifier) {
    val c = MC.colors
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(total) { i ->
            Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(if (i < step) c.accent else c.separator))
        }
    }
}

/** Compact title bar for pushed screens. */
@Composable
fun TopBar(title: String, onBack: (() -> Unit)?, modifier: Modifier = Modifier, divider: Boolean = false, trailing: (@Composable () -> Unit)? = null) {
    val c = MC.colors
    Box(
        modifier.fillMaxWidth().height(52.dp).background(Color.Transparent)
            .drawBehind { if (divider) drawLine(c.separator, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1f) },
    ) {
        if (onBack != null) {
            Pressable(onBack, Modifier.align(Alignment.CenterStart).size(Dim.touch), description = "Back") { pressed ->
                Box(Modifier.size(Dim.touch).alpha(if (pressed) 0.5f else 1f), contentAlignment = Alignment.Center) { Icon(Glyph.Back, c.accent, size = 26.dp, weight = 2.1f) }
            }
        }
        Label(title, Type.headline, c.text, Modifier.align(Alignment.Center).padding(horizontal = 56.dp), align = TextAlign.Center, maxLines = 1)
        if (trailing != null) Box(Modifier.align(Alignment.CenterEnd).padding(end = 4.dp)) { trailing() }
    }
}

data class TabItem(val label: String, val glyph: Glyph)

/** A floating pill. The selected tab sits in a soft accent capsule. */
@Composable
fun TabBar(items: List<TabItem>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = MC.colors
    Box(modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 24.dp, vertical = 10.dp)) {
        Row(
            Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(32.dp)).background(c.surface)
                .border(1.dp, c.separator, RoundedCornerShape(32.dp)).padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items.forEachIndexed { i, item ->
                val on = i == selected
                val color = if (on) c.accent else c.textSecondary
                Pressable({ onSelect(i) }, Modifier.weight(1f).fillMaxHeight().tourTarget("tab_" + item.label.lowercase())) { pressed ->
                    Column(
                        Modifier.fillMaxSize().pressScale(pressed).clip(RoundedCornerShape(26.dp)).background(if (on) c.accentSoft else Color.Transparent),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(item.glyph, color, size = 22.dp, weight = if (on) 2f else 1.75f)
                        Spacer(Modifier.height(2.dp))
                        // The bar is a fixed height with five labels side by side, so its text stops growing past 1.3 times.
                        val fs = LocalDensity.current.fontScale
                        Label(item.label, if (fs > 1.3f) Type.tab.copy(fontSize = Type.tab.fontSize * (1.3f / fs)) else Type.tab, color, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
fun ConfirmDialog(title: String, message: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit, destructive: Boolean = false, cancelLabel: String = "Cancel") {
    val c = MC.colors
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.width(300.dp).clip(RoundedCornerShape(20.dp)).background(c.surface).padding(20.dp)) {
            Label(title, Type.headline, c.text)
            Spacer(Modifier.height(6.dp))
            Label(message, Type.subhead, c.textSecondary)
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(cancelLabel, onDismiss, Modifier.weight(1f))
                Pressable(onConfirm, Modifier.weight(1f)) { pressed ->
                    Box(
                        Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(Dim.radiusButton))
                            .background((if (destructive) c.error else c.ink).copy(alpha = if (pressed) 0.8f else 1f)),
                        contentAlignment = Alignment.Center,
                    ) { Label(confirmLabel, Type.headline, if (destructive) Color.White else c.onInk) }
                }
            }
        }
    }
}

/** Text to read, scrolled if long, with one Close button. */
@Composable
fun ReadDialog(title: String, text: String, onDismiss: () -> Unit) {
    val c = MC.colors
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.width(320.dp).clip(RoundedCornerShape(20.dp)).background(c.surface).padding(20.dp)) {
            Label(title, Type.headline, c.text)
            Spacer(Modifier.height(8.dp))
            Column(Modifier.weight(1f, fill = false).heightIn(max = 380.dp).verticalScroll(rememberScrollState())) { Label(text, Type.subhead, c.textSecondary) }
            Spacer(Modifier.height(16.dp))
            SecondaryButton("Close", onDismiss, Modifier.fillMaxWidth())
        }
    }
}

/** A short list of choices in a dialog. Each action is a label, whether it removes something, and what it does. */
class DialogAction(val label: String, val destructive: Boolean = false, val onClick: () -> Unit)

@Composable
fun ActionDialog(title: String, actions: List<DialogAction>, onDismiss: () -> Unit) {
    val c = MC.colors
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.width(300.dp).clip(RoundedCornerShape(20.dp)).background(c.surface).padding(vertical = 8.dp)) {
            Label(title, Type.headline, c.text, Modifier.padding(horizontal = 20.dp, vertical = 12.dp), maxLines = 2)
            actions.forEach { a ->
                Pressable({ a.onClick() }, Modifier.fillMaxWidth()) { pressed ->
                    Box(Modifier.fillMaxWidth().background(if (pressed) c.pressed else Color.Transparent).heightIn(min = 52.dp).padding(horizontal = 20.dp), contentAlignment = Alignment.CenterStart) {
                        Label(a.label, Type.body, if (a.destructive) c.error else c.text)
                    }
                }
            }
            Pressable(onDismiss, Modifier.fillMaxWidth()) { pressed ->
                Box(Modifier.fillMaxWidth().background(if (pressed) c.pressed else Color.Transparent).heightIn(min = 52.dp).padding(horizontal = 20.dp), contentAlignment = Alignment.CenterStart) { Label("Cancel", Type.body, c.textSecondary) }
            }
        }
    }
}

/** Asks for one line of text, for example a new name. */
@Composable
fun PromptDialog(title: String, initial: String, confirmLabel: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    val c = MC.colors
    var text by remember { mutableStateOf(initial) }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.width(300.dp).clip(RoundedCornerShape(20.dp)).background(c.surface).padding(20.dp)) {
            Label(title, Type.headline, c.text)
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(Dim.radiusField)).background(c.fill).padding(horizontal = 16.dp, vertical = 14.dp)) {
                BasicTextField(
                    value = text, onValueChange = { text = it.take(60) }, singleLine = true,
                    textStyle = Type.body.copy(color = c.text), cursorBrush = SolidColor(c.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { if (text.isNotBlank()) onConfirm(text) }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Cancel", onDismiss, Modifier.weight(1f))
                PrimaryButton(confirmLabel, { if (text.isNotBlank()) onConfirm(text) }, Modifier.weight(1f), enabled = text.isNotBlank())
            }
        }
    }
}

/** A search box that takes focus when it appears. */
@Composable
fun SearchBar(value: String, onValueChange: (String) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier, placeholder: String = "Search") {
    val c = MC.colors
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { focus.requestFocus() }
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(Dim.radiusField)).background(c.fill).padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Glyph.Search, c.textSecondary, size = 20.dp)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Label(placeholder, Type.body, c.textTertiary, maxLines = 1)
            BasicTextField(
                value = value, onValueChange = onValueChange, singleLine = true,
                textStyle = Type.body.copy(color = c.text), cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        Pressable(onClose, Modifier.size(Dim.touch), description = "Close search") { pressed -> Box(Modifier.size(Dim.touch).alpha(if (pressed) 0.5f else 1f), contentAlignment = Alignment.Center) { Icon(Glyph.Close, c.textSecondary, size = 18.dp) } }
    }
}

/** A switch. Flat: a rounded track with a round white thumb. */
@Composable
fun Toggle(on: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val c = MC.colors
    val x by animateFloatAsState(if (on) 1f else 0f, tween(160), label = "toggle")
    Pressable({ onChange(!on) }, Modifier.size(width = 52.dp, height = 32.dp), enabled = enabled, role = androidx.compose.ui.semantics.Role.Switch, state = if (on) "On" else "Off") { _ ->
        Box(
            Modifier.size(width = 52.dp, height = 32.dp).clip(RoundedCornerShape(16.dp))
                .background(if (on) c.accent.copy(alpha = if (enabled) 1f else 0.5f) else c.separator),
        ) {
            Box(Modifier.padding(start = 2.dp + 20.dp * x, top = 2.dp).size(28.dp).clip(CircleShape).background(Color.White))
        }
    }
}
