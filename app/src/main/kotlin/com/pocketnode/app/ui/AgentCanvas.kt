package com.pocketnode.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.pocketnode.app.AppModel
import com.pocketnode.app.CoreHost
import com.pocketnode.app.R
import com.pocketnode.core.AgentArgs
import com.pocketnode.core.AgentDef
import com.pocketnode.core.AgentStep
import com.pocketnode.core.AgentTrigger
import com.pocketnode.core.Agents
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

// The flow canvas: blocks placed anywhere, joined by wires. Drag from the dot under a block to another block to wire them.
// A "Only go on if" block has two dots: Yes (left) and No (right). Dragging a wire onto nothing, or tapping its dot, removes it.

private const val NW = 168f   // block width, dp
private const val NH = 80f    // block height, dp
private const val START = 0   // key of the "When" block
private const val YES = 1
private const val NO = 2

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentCanvasScreen(id: String?) {
    val c = MC.colors
    val density = LocalDensity.current
    val d = density.density
    val specs = remember { CoreHost.agentToolSpecs() }
    val existing = remember(id) { id?.let { CoreHost.agentList.firstOrNull { a -> a.def.id == it } } }
    var counter by remember { mutableStateOf(1) }
    val nodes = remember(id) { mutableStateListOf<Block>() }
    var startX by remember(id) { mutableStateOf(100f) }
    var startY by remember(id) { mutableStateOf(24f) }
    var startNext by remember(id) { mutableStateOf<Int?>(null) }
    remember(id) {
        existing?.def?.let { def ->
            val flow = Agents.isFlow(def)
            val keys = def.steps.map { counter++ }
            val byId = def.steps.withIndex().associate { (i, s) -> s.id to keys[i] }
            def.steps.forEachIndexed { i, s ->
                val b = Block(keys[i], s.type, if (s.type == "tool") s.tool else "", if (s.type == "notify") s.tool else "", s.text, s.save, s.from, s.op.ifBlank { "contains" })
                if (s.type == "tool") AgentArgs.parse(s.args).forEach { (k, v) -> b.args[k] = v }
                if (flow) { b.x = s.x.toFloat(); b.y = s.y.toFloat(); b.next = byId[s.next]; b.no = byId[s.no] }
                else { b.x = 100f; b.y = 24f + 120f * (i + 1); b.next = keys.getOrNull(i + 1) }
                nodes += b
            }
            startNext = keys.firstOrNull()
            nodes.firstOrNull()?.let { startX = it.x; startY = it.y - 120f }
        }
        true
    }
    var name by remember(id) { mutableStateOf(existing?.def?.name ?: "") }
    val t0 = existing?.def?.trigger ?: AgentTrigger("daily", 8, 0)
    var kind by remember(id) { mutableStateOf(t0.kind) }
    var hour by remember(id) { mutableStateOf(t0.hour.toString()) }
    var minute by remember(id) { mutableStateOf(t0.minute.toString().padStart(2, '0')) }
    var weekdays by remember(id) { mutableStateOf(t0.weekdaysOnly) }
    var every by remember(id) { mutableStateOf(if (t0.everyMinutes > 0) t0.everyMinutes.toString() else "60") }
    var problem by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Int?>(null) }
    var editing by remember { mutableStateOf(false) }   // the settings panel replaces the shelf while a block is open
    var pan by remember { mutableStateOf(Offset(8f * d, 8f * d)) }
    var wire by remember { mutableStateOf<Triple<Int, Int, Offset>?>(null) }   // from key, which dot, finger position (canvas px)
    var canvasRoot by remember { mutableStateOf(Offset.Zero) }
    var canvasSize by remember { mutableStateOf(Offset.Zero) }
    var rootOffset by remember { mutableStateOf(Offset.Zero) }
    var drag by remember { mutableStateOf<Drag?>(null) }

    fun node(key: Int?) = nodes.firstOrNull { it.key == key }

    /** Blocks whose results this block can use: everything that can lead to it. */
    fun ancestors(key: Int): List<Block> {
        val seen = mutableSetOf<Int>()
        fun walk(k: Int) { nodes.filter { it.next == k || it.no == k }.forEach { if (seen.add(it.key)) walk(it.key) } }
        walk(key)
        return nodes.filter { it.key in seen }
    }
    fun reaches(from: Int, to: Int): Boolean {
        val seen = mutableSetOf<Int>()
        fun walk(k: Int): Boolean { if (k == to) return true; if (!seen.add(k)) return false; return node(k)?.let { n -> listOfNotNull(n.next, n.no).any { walk(it) } } ?: false }
        return walk(from)
    }
    /** The order the blocks are saved in: the one the start wire leads to first, then the rest as they were added. */
    fun ordered(): List<Block> = node(startNext)?.let { first -> listOf(first) + nodes.filter { it !== first } } ?: nodes.toList()

    fun portPx(key: Int, which: Int): Offset {
        if (key == START) return Offset(pan.x + (startX + NW / 2) * d, pan.y + (startY + 60f) * d)
        val n = node(key) ?: return Offset.Zero
        val fx = when (which) { YES -> 0.3f; NO -> 0.7f; else -> 0.5f }
        return Offset(pan.x + (n.x + NW * fx) * d, pan.y + (n.y + NH) * d)
    }
    fun inPx(key: Int): Offset = node(key)?.let { Offset(pan.x + (it.x + NW / 2) * d, pan.y + it.y * d) } ?: Offset.Zero

    fun setLink(from: Int, which: Int, to: Int?) {
        if (to != null && (to == from || reaches(to, from))) { problem = "That wire would make a loop. A flow can only go forward."; return }
        problem = null
        if (from == START) startNext = to else node(from)?.let { if (which == NO) it.no = to else it.next = to }
    }

    @Composable fun GoesTo(label: String, current: Int?, pick: (Int?) -> Unit) {
        val order = ordered()
        val self = selected
        Label(label, Type.footnote, c.textSecondary)
        ChipRow(listOf("" to "Nothing") + order.filter { it.key != self }.map { it.key.toString() to "${order.indexOf(it) + 1}. ${blockTitle(it)}".take(26) }, current?.toString().orEmpty()) { pick(it.toIntOrNull()) }
    }

    fun addNode(type: String, tool: String, at: Offset?) {
        val b = Block(counter++, type, tool, if (type == "notify") "Agent" else "", op = "contains")
        b.open = true
        val sel = selected
        var linked = false
        if (at != null) { b.x = at.x; b.y = at.y }
        else {
            // Tapped on the shelf: put it under the selected block and wire it, so a tap-tap-tap makes a chain.
            val from = if (sel == START) null else node(sel)
            val free = when {
                sel == START && startNext == null -> START to 0
                from != null && from.type == "check" && from.next == null -> from.key to YES
                from != null && from.type == "check" && from.no == null -> from.key to NO
                from != null && from.type != "check" && from.next == null -> from.key to 0
                else -> null
            }
            if (free != null) {
                val bx = if (free.first == START) startX else from?.x ?: 0f
                val by = if (free.first == START) startY + 72f else from?.y?.plus(NH) ?: 0f
                b.x = bx + when (free.second) { YES -> -(NW / 2 + 8); NO -> NW / 2 + 8; else -> 0f }
                b.y = by + 56f
                nodes += b
                setLink(free.first, free.second, b.key)
                linked = true
            } else {
                b.x = (-pan.x / d + (canvasSize.x / d - NW) / 2 + (nodes.size % 5) * 14f)
                b.y = (-pan.y / d + (canvasSize.y / d - NH) / 3 + (nodes.size % 5) * 14f)
            }
        }
        if (!linked) nodes += b
        selected = b.key; editing = true
    }

    fun removeNode(b: Block) {
        nodes.forEach { if (it.next == b.key) it.next = null; if (it.no == b.key) it.no = null }
        if (startNext == b.key) startNext = null
        nodes.remove(b)
        selected = null; editing = false
    }

    fun finishDrag() {
        val dg = drag ?: return
        drag = null
        val x = dg.x - canvasRoot.x; val y = dg.y - canvasRoot.y
        if (x < 0 || y < 0 || x > canvasSize.x || y > canvasSize.y) return
        addNode(dg.type, dg.tool, Offset((x - pan.x) / d - NW / 2, (y - pan.y) / d - NH / 2))
    }

    fun save() {
        val order = ordered()
        val ids = order.associate { it.key to "n${it.key}" }
        val steps = order.map { b ->
            val base = when (b.type) {
                "tool" -> {
                    val spec = specs.firstOrNull { it.name == b.tool }
                    AgentStep("tool", tool = b.tool, args = if (spec != null) AgentArgs.build(spec.schema, b.args.toMap()) else "{}", save = b.save.ifBlank { "r${b.key}" })
                }
                "ask" -> AgentStep("ask", text = b.text, save = b.save.ifBlank { "r${b.key}" })
                "check" -> AgentStep("check", text = b.text, from = b.from, op = b.op)
                else -> AgentStep("notify", tool = b.title, text = b.text)
            }
            base.copy(id = ids.getValue(b.key), next = ids[b.next].orEmpty(), no = if (b.type == "check") ids[b.no].orEmpty() else "", x = b.x.roundToInt(), y = b.y.roundToInt())
        }
        val trigger = when (kind) {
            "daily" -> AgentTrigger("daily", hour.toIntOrNull() ?: -1, minute.toIntOrNull() ?: -1, weekdays)
            "every" -> AgentTrigger("every", everyMinutes = every.toIntOrNull() ?: 0)
            else -> AgentTrigger("manual")
        }
        if (startNext == null && nodes.isNotEmpty()) { problem = "Wire the When block to the first block."; return }
        val def = AgentDef(existing?.def?.id ?: "b-" + java.util.UUID.randomUUID().toString().take(8), name.trim(), trigger, steps)
        val found = Agents.validate(def, CoreHost.agentToolNames())
        if (found.isNotEmpty()) { problem = found.first(); return }
        saving = true
        CoreHost.addAgent(def, { saving = false; problem = it }) { saving = false; AppModel.pop() }
    }

    Frame(c.grouped) {
        Box(Modifier.fillMaxSize().onGloballyPositioned { rootOffset = it.positionInRoot() }) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                TopBar(if (existing == null) "New flow" else "Edit the flow", onBack = { AppModel.pop() }, trailing = { TextAction(if (saving) stringResource(R.string.agb_saving) else "Save", { save() }, enabled = !saving) })
                problem?.let { Box(Modifier.padding(horizontal = Dim.margin).padding(bottom = 4.dp)) { InlineStatus(StatusKind.Error, it) } }
                Box(Modifier.padding(horizontal = Dim.margin)) { Field("Name", name, { name = it }, placeholder = stringResource(R.string.agb_morning_weather)) }
                Spacer(Modifier.height(6.dp))
                // The canvas.
                Box(
                    Modifier.weight(1f).fillMaxWidth().clipToBounds().background(c.grouped)
                        .onGloballyPositioned { canvasRoot = it.positionInRoot(); canvasSize = Offset(it.size.width.toFloat(), it.size.height.toFloat()) }
                        .pointerInput(Unit) { detectTapGestures { selected = null; editing = false } }
                        .pointerInput(Unit) { detectDragGestures { change, amount -> change.consume(); pan += amount } },
                ) {
                    val wireYes = c.accent; val wireNo = c.error; val wireNeutral = c.textSecondary
                    Canvas(Modifier.fillMaxSize()) {
                        fun curve(a: Offset, b: Offset, color: androidx.compose.ui.graphics.Color) {
                            val k = max(40f * d, abs(b.y - a.y) / 2f)
                            val path = Path().apply { moveTo(a.x, a.y); cubicTo(a.x, a.y + k, b.x, b.y - k, b.x, b.y) }
                            drawPath(path, color, style = Stroke(width = 2.5f * d))
                            drawCircle(color, 4f * d, b)
                        }
                        startNext?.let { curve(portPx(START, 0), inPx(it), wireNeutral) }
                        nodes.forEach { n ->
                            if (n.type == "check") {
                                n.next?.let { curve(portPx(n.key, YES), inPx(it), wireYes) }
                                n.no?.let { curve(portPx(n.key, NO), inPx(it), wireNo) }
                            } else n.next?.let { curve(portPx(n.key, 0), inPx(it), wireNeutral) }
                        }
                        wire?.let { (from, which, at) -> curve(portPx(from, which), at, if (which == NO) wireNo else wireYes) }
                    }
                    // The "When" block.
                    CanvasCard(Modifier.offset { IntOffset((pan.x + startX * d).roundToInt(), (pan.y + startY * d).roundToInt()) }.size(width = NW.dp, height = 60.dp), selected == START, "When. " + triggerSummary(kind, hour, minute, weekdays, every),
                        onTap = { selected = START; editing = true }, onMove = { dx, dy -> startX += dx / d; startY += dy / d }) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconTile(Glyph.Clock, c.accent, c.accentSoft, 32.dp)
                            Spacer(Modifier.width(8.dp))
                            Column { Label("When", Type.subhead, c.text, maxLines = 1); Label(triggerSummary(kind, hour, minute, weekdays, every), Type.footnote, c.textSecondary, maxLines = 1) }
                        }
                    }
                    val order = ordered()
                    nodes.forEach { n ->
                        CanvasCard(Modifier.offset { IntOffset((pan.x + n.x * d).roundToInt(), (pan.y + n.y * d).roundToInt()) }.size(width = NW.dp, height = NH.dp), selected == n.key, "${order.indexOf(n) + 1}. " + blockTitle(n) + ". " + blockSummary(n) + if (n.type == "check") ". Has a Yes way out and a No way out." else "",
                            onTap = { selected = n.key; editing = true }, onMove = { dx, dy -> n.x += dx / d; n.y += dy / d }) {
                            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconTile(blockGlyph(n), c.accent, c.accentSoft, 32.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Column {
                                        Label("${order.indexOf(n) + 1}. " + blockTitle(n), Type.subhead, c.text, maxLines = 1)
                                        Label(blockSummary(n), Type.footnote, c.textSecondary, maxLines = 1)
                                    }
                                }
                                if (n.type == "check") Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Label("Yes", Type.footnote, c.accent, maxLines = 1) }
                                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Label("No", Type.footnote, c.error, maxLines = 1) }
                                }
                            }
                        }
                    }
                    // The dots wires start from. Drag one onto a block; tap it to cut its wire.
                    @Composable fun Dot(key: Int, which: Int, color: androidx.compose.ui.graphics.Color, label: String) {
                        val at = portPx(key, which)
                        Box(
                            Modifier.offset { IntOffset((at.x - 22f * d).roundToInt(), (at.y - 22f * d).roundToInt()) }.size(44.dp)
                                .pointerInput(key, which) { detectTapGestures { setLink(key, which, null) } }
                                .pointerInput(key, which) {
                                    var total = Offset.Zero
                                    detectDragGestures(
                                        onDragStart = { total = Offset.Zero; wire = Triple(key, which, portPx(key, which)) },
                                        onDrag = { change, amount -> change.consume(); total += amount; wire = Triple(key, which, portPx(key, which) + total) },
                                        onDragEnd = {
                                            val end = portPx(key, which) + total
                                            wire = null
                                            val slack = 10f * d
                                            val hit = nodes.firstOrNull { n -> n.key != key && end.x in (pan.x + n.x * d - slack)..(pan.x + (n.x + NW) * d + slack) && end.y in (pan.y + n.y * d - slack)..(pan.y + (n.y + NH) * d + slack) }
                                            setLink(key, which, hit?.key)
                                        },
                                        onDragCancel = { wire = null },
                                    )
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(Modifier.size(18.dp).clip(CircleShape).background(c.surface).border(2.dp, color, CircleShape).semanticsLabel(label))
                        }
                    }
                    Dot(START, 0, c.textSecondary, "Wire from When")
                    nodes.forEach { n ->
                        if (n.type == "check") { Dot(n.key, YES, c.accent, "Wire from Yes of ${blockTitle(n)}"); Dot(n.key, NO, c.error, "Wire from No of ${blockTitle(n)}") }
                        else Dot(n.key, 0, c.textSecondary, "Wire from ${blockTitle(n)}")
                    }
                    if (nodes.isEmpty() && !editing) Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.BottomCenter) {
                        Label("Tap or drag a tool from the shelf. Drag from the dot under a block to another block to wire them.", Type.subhead, c.textSecondary)
                    }
                }
                // Settings of the selected block.
                val sel = selected
                if (sel != null && editing) Column(Modifier.fillMaxWidth().heightIn(max = 330.dp).background(c.surface).verticalScroll(rememberScrollState()).padding(Dim.margin), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (sel == START) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.weight(1f)) { Label("When does it start?", Type.body, c.text) }; TextAction("Done", { editing = false }) }
                        ChipRow(listOf("manual" to stringResource(R.string.agb_only_when_i_tap), "daily" to stringResource(R.string.agb_every_day), "every" to stringResource(R.string.agb_every_few_minutes)), kind) { kind = it }
                        if (kind == "daily") {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(Modifier.weight(1f)) { Field(stringResource(R.string.agb_hour_0_to_23), hour, { hour = it.filter(Char::isDigit).take(2) }, keyboardType = androidx.compose.ui.text.input.KeyboardType.Number) }
                                Box(Modifier.weight(1f)) { Field(stringResource(R.string.agb_minute), minute, { minute = it.filter(Char::isDigit).take(2) }, keyboardType = androidx.compose.ui.text.input.KeyboardType.Number) }
                            }
                            ChipRow(listOf("all" to stringResource(R.string.agb_every_day), "week" to stringResource(R.string.agb_weekdays_only)), if (weekdays) "week" else "all") { weekdays = it == "week" }
                        }
                        if (kind == "every") Field(stringResource(R.string.agb_minutes_between_runs_15_or_more), every, { every = it.filter(Char::isDigit).take(4) }, keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                        GoesTo("Starts with", startNext) { setLink(START, 0, it) }
                    } else node(sel)?.let { b ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { Label(blockTitle(b), Type.body, c.text, maxLines = 1) }
                            TextAction("Remove", { removeNode(b) }, color = c.error)
                            TextAction("Done", { editing = false })
                        }
                        BlockEditor(b, specs, ancestors(b.key).filter { it.type == "tool" || it.type == "ask" }, ordered(), flow = true)
                        // The same wires as the dots, as buttons: for a screen reader, and for anyone who finds dragging hard.
                        if (b.type == "check") { GoesTo("Yes goes to", b.next) { setLink(b.key, YES, it) }; GoesTo("No goes to", b.no) { setLink(b.key, NO, it) } }
                        else GoesTo("Then goes to", b.next) { setLink(b.key, 0, it) }
                    }
                }
                // The shelf.
                if (!editing) Column(Modifier.fillMaxWidth().background(c.surface).padding(top = 8.dp, bottom = 8.dp)) {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Dim.margin), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(Triple("ask", "", stringResource(R.string.agb_ask_the_model)), Triple("check", "", "If / else"), Triple("notify", "", stringResource(R.string.agb_notify_me))).forEach { (t, tl, label) ->
                            ShelfChip(label, if (t == "ask") Glyph.Chat else if (t == "check") Glyph.Check else Glyph.Bolt, { addNode(t, tl, null) },
                                { x, y -> drag = Drag(label, t, tl, null, x, y) }, { dx, dy -> drag?.let { g -> drag = Drag(g.label, g.type, g.tool, null, g.x + dx, g.y + dy) } }, { finishDrag() }, { drag = null })
                        }
                    }
                    val typing = WindowInsets.isImeVisible
                    if (!typing) Box(Modifier.padding(horizontal = Dim.margin)) { Field(stringResource(R.string.agb_find_a_tool), query, { query = it }, placeholder = "weather, notes, map") }
                    val shown = specs.filter { query.isBlank() || it.name.contains(query.trim(), true) || it.description.contains(query.trim(), true) }
                    if (!typing) LazyHorizontalGrid(GridCells.Fixed(2), Modifier.height((96 * (0.6f + 0.4f * density.fontScale.coerceAtLeast(1f))).dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Dim.margin), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), userScrollEnabled = drag == null) {
                        items(shown, key = { it.name }) { s ->
                            ShelfChip(s.name, toolGlyph(s.name), { addNode("tool", s.name, null) },
                                { x, y -> drag = Drag(s.name, "tool", s.name, null, x, y) }, { dx, dy -> drag?.let { g -> drag = Drag(g.label, g.type, g.tool, null, g.x + dx, g.y + dy) } }, { finishDrag() }, { drag = null })
                        }
                    }
                }
            }
            drag?.let { g ->
                Box(Modifier.offset { IntOffset((g.x - rootOffset.x - 60f * d).roundToInt(), (g.y - rootOffset.y - 22f * d).roundToInt()) }
                    .clip(RoundedCornerShape(22.dp)).background(c.accent).padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Label(g.label, Type.subhead, c.surface, maxLines = 1)
                }
            }
        }
    }
}

/** A block on the canvas: tap to select, drag to move. */
@Composable
private fun CanvasCard(modifier: Modifier, selected: Boolean, description: String, onTap: () -> Unit, onMove: (Float, Float) -> Unit, content: @Composable () -> Unit) {
    val c = MC.colors
    Box(
        modifier.clip(RoundedCornerShape(14.dp)).background(c.surface).border(if (selected) 2.dp else 1.dp, if (selected) c.accent else c.separator, RoundedCornerShape(14.dp))
            .semantics(mergeDescendants = true) { contentDescription = description; role = androidx.compose.ui.semantics.Role.Button; this.selected = selected; onClick(label = "Open its settings") { onTap(); true } }
            .pointerInput(Unit) { detectTapGestures { onTap() } }
            .pointerInput(Unit) { detectDragGestures(onDragStart = { onTap() }) { change, amount -> change.consume(); onMove(amount.x, amount.y) } },
    ) { content() }
}

private fun Modifier.semanticsLabel(text: String): Modifier = this.semantics { contentDescription = text }
