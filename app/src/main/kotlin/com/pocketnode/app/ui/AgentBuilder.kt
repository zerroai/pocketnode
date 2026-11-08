package com.pocketnode.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.pocketnode.app.R
import com.pocketnode.app.AppModel
import com.pocketnode.app.CoreHost
import com.pocketnode.core.AgentArgs
import com.pocketnode.core.AgentDef
import com.pocketnode.core.AgentStep
import com.pocketnode.core.AgentTrigger
import com.pocketnode.core.Agents
import com.pocketnode.core.ToolSpec
import kotlin.math.roundToInt

// The agent builder: blocks joined by lines, from "When" down to the last step. Drag a tool from the shelf onto the line to add it,
// drag a block by its handle to move it, drop it on the shelf to remove it. Tapping a block opens its settings. Tapping a shelf item adds it at the end.

internal class Block(val key: Int, type: String, tool: String = "", title: String = "", text: String = "", save: String = "", from: String = "", op: String = "contains") {
    var type by mutableStateOf(type)
    var tool by mutableStateOf(tool)
    var title by mutableStateOf(title)
    var text by mutableStateOf(text)
    var save by mutableStateOf(save)
    var from by mutableStateOf(from)
    var op by mutableStateOf(op)
    var open by mutableStateOf(false)
    var lastField by mutableStateOf("")
    val args = mutableStateMapOf<String, String>()
    // Used by the flow canvas only: where the block sits (dp) and the keys of the blocks its wires lead to.
    var x by mutableStateOf(0f)
    var y by mutableStateOf(0f)
    var next by mutableStateOf<Int?>(null)
    var no by mutableStateOf<Int?>(null)
}

/** What is being dragged: a new block from the shelf (`type`/`tool` set) or an existing one (`moving` is its key). */
internal class Drag(val label: String, val type: String, val tool: String, val moving: Int?, var x: Float, var y: Float)

internal fun blockTitle(b: Block) = when (b.type) { "tool" -> b.tool; "ask" -> "Ask the model"; "check" -> "Only go on if"; else -> "Notify me" }

internal fun blockGlyph(b: Block) = when (b.type) { "tool" -> toolGlyph(b.tool); "ask" -> Glyph.Chat; "check" -> Glyph.Check; else -> Glyph.Bolt }

internal fun blockSummary(b: Block): String = when (b.type) {
    "tool" -> b.args.filterValues { it.isNotBlank() }.entries.joinToString(", ") { "${it.key} ${it.value}" }.take(80).ifBlank { "No inputs" }
    "ask" -> b.text.take(80).ifBlank { "Write the question" }
    "check" -> if (b.from.isBlank()) "Choose a result to look at" else "Result ${b.from} " + when (b.op) { "contains" -> "contains “${b.text}”"; "equals" -> "is “${b.text}”"; else -> "is not empty" }
    else -> (b.title.ifBlank { "Title" } + ": " + b.text.ifBlank { "Message" }).take(80)
}

@Composable
fun AgentBuilderScreen(id: String?) {
    val c = MC.colors
    val density = LocalDensity.current
    val specs = remember { CoreHost.agentToolSpecs() }
    val existing = remember(id) { id?.let { CoreHost.agentList.firstOrNull { a -> a.def.id == it } } }
    var counter by remember { mutableStateOf(1) }
    val blocks = remember(id) {
        mutableStateListOf<Block>().also { list ->
            existing?.def?.steps?.forEach { s ->
                val spec = specs.firstOrNull { it.name == s.tool }
                val b = Block(counter++, s.type, if (s.type == "tool") s.tool else "", if (s.type == "notify") s.tool else "", s.text, s.save, s.from, s.op.ifBlank { "contains" })
                if (s.type == "tool") { AgentArgs.parse(s.args).forEach { (k, v) -> b.args[k] = v }; if (spec == null) b.open = false }
                list += b
            }
        }
    }
    var name by remember(id) { mutableStateOf(existing?.def?.name ?: "") }
    val t0 = existing?.def?.trigger ?: AgentTrigger("daily", 8, 0)
    var kind by remember(id) { mutableStateOf(t0.kind) }
    var hour by remember(id) { mutableStateOf(t0.hour.toString()) }
    var minute by remember(id) { mutableStateOf(t0.minute.toString().padStart(2, '0')) }
    var weekdays by remember(id) { mutableStateOf(t0.weekdaysOnly) }
    var every by remember(id) { mutableStateOf(if (t0.everyMinutes > 0) t0.everyMinutes.toString() else "60") }
    var triggerOpen by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    val scroll = rememberScrollState()
    val bounds = remember { mutableStateMapOf<Int, Pair<Float, Float>>() }
    var chain by remember { mutableStateOf(Pair(0f, 0f)) }
    var shelf by remember { mutableStateOf(Pair(0f, 0f)) }
    var rootOffset by remember { mutableStateOf(Offset.Zero) }
    var drag by remember { mutableStateOf<Drag?>(null) }

    fun insertIndex(y: Float, skip: Int?): Int = blocks.filter { it.key != skip }.count { b -> bounds[b.key]?.let { (t, bt) -> (t + bt) / 2 < y } ?: false }
    fun newBlock(type: String, tool: String) = Block(counter++, type, tool, if (type == "notify") "Agent" else "").also { it.open = true }

    fun finishDrag() {
        val d = drag ?: return
        drag = null
        val over = d.y
        val inChain = over in chain.first..chain.second
        if (d.moving != null) {
            val b = blocks.firstOrNull { it.key == d.moving } ?: return
            if (over >= shelf.first) { blocks.remove(b); return }
            if (!inChain) return
            val at = insertIndex(over, b.key)
            blocks.remove(b); blocks.add(at.coerceIn(0, blocks.size), b)
        } else if (inChain) blocks.add(insertIndex(over, null).coerceIn(0, blocks.size), newBlock(d.type, d.tool))
    }

    LaunchedEffect(drag != null) {
        while (drag != null) {
            val y = drag?.y ?: break
            val step = with(density) { 14.dp.toPx() }
            if (y < chain.first + with(density) { 70.dp.toPx() }) scroll.scrollBy(-step) else if (y > chain.second - with(density) { 70.dp.toPx() } && y < shelf.first) scroll.scrollBy(step)
            kotlinx.coroutines.delay(16)
        }
    }

    fun save() {
        val steps = blocks.map { b ->
            when (b.type) {
                "tool" -> {
                    val spec = specs.firstOrNull { it.name == b.tool }
                    AgentStep("tool", tool = b.tool, args = if (spec != null) AgentArgs.build(spec.schema, b.args.toMap()) else "{}", save = b.save.ifBlank { "r${b.key}" })
                }
                "ask" -> AgentStep("ask", text = b.text, save = b.save.ifBlank { "r${b.key}" })
                "check" -> AgentStep("check", text = b.text, from = b.from, op = b.op)
                else -> AgentStep("notify", tool = b.title, text = b.text)
            }
        }
        val trigger = when (kind) {
            "daily" -> AgentTrigger("daily", hour.toIntOrNull() ?: -1, minute.toIntOrNull() ?: -1, weekdays)
            "every" -> AgentTrigger("every", everyMinutes = every.toIntOrNull() ?: 0)
            else -> AgentTrigger("manual")
        }
        val def = AgentDef(existing?.def?.id ?: "b-" + java.util.UUID.randomUUID().toString().take(8), name.trim(), trigger, steps)
        val found = Agents.validate(def, CoreHost.agentToolNames())
        if (found.isNotEmpty()) { problem = found.first(); return }
        saving = true
        CoreHost.addAgent(def, { saving = false; problem = it }) { saving = false; AppModel.pop() }
    }

    Frame(c.grouped) {
        Box(Modifier.fillMaxSize().onGloballyPositioned { rootOffset = it.positionInRoot() }) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                TopBar(if (existing == null) stringResource(R.string.agb_new_agent) else stringResource(R.string.agb_edit_agent), onBack = { AppModel.pop() }, trailing = { TextAction(if (saving) stringResource(R.string.agb_saving) else "Save", { save() }, enabled = !saving) })
                problem?.let { Box(Modifier.padding(horizontal = Dim.margin).padding(bottom = 4.dp)) { InlineStatus(StatusKind.Error, it) } }
                Column(
                    Modifier.weight(1f).verticalScroll(scroll, enabled = drag == null).padding(horizontal = Dim.margin)
                        .onGloballyPositioned { chain = it.positionInRoot().y to (it.positionInRoot().y + it.size.height) },
                ) {
                    Spacer(Modifier.height(8.dp))
                    Group { Box(Modifier.padding(horizontal = 4.dp)) { Field("Name", name, { name = it }, placeholder = stringResource(R.string.agb_morning_weather)) } }
                    Spacer(Modifier.height(12.dp))
                    // The first block: when the agent starts.
                    BlockCard(Glyph.Clock, "When", triggerSummary(kind, hour, minute, weekdays, every), triggerOpen, onToggle = { triggerOpen = !triggerOpen }, handle = null) {
                        ChipRow(listOf("manual" to stringResource(R.string.agb_only_when_i_tap), "daily" to stringResource(R.string.agb_every_day), "every" to stringResource(R.string.agb_every_few_minutes)), kind) { kind = it }
                        if (kind == "daily") {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                                Box(Modifier.weight(1f)) { Field(stringResource(R.string.agb_hour_0_to_23), hour, { hour = it.filter(Char::isDigit).take(2) }, keyboardType = androidx.compose.ui.text.input.KeyboardType.Number) }
                                Box(Modifier.weight(1f)) { Field(stringResource(R.string.agb_minute), minute, { minute = it.filter(Char::isDigit).take(2) }, keyboardType = androidx.compose.ui.text.input.KeyboardType.Number) }
                            }
                            ChipRow(listOf("all" to stringResource(R.string.agb_every_day), "week" to stringResource(R.string.agb_weekdays_only)), if (weekdays) "week" else "all") { weekdays = it == "week" }
                        }
                        if (kind == "every") Field(stringResource(R.string.agb_minutes_between_runs_15_or_more), every, { every = it.filter(Char::isDigit).take(4) }, keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                    }
                    blocks.forEachIndexed { i, b ->
                        val dragging = drag?.moving == b.key
                        val target = drag != null && drag?.y?.let { insertIndex(it, drag?.moving) } == i && drag?.y?.let { it in chain.first..chain.second } == true
                        Connector(target)
                        Box(Modifier.onGloballyPositioned { bounds[b.key] = it.positionInRoot().y to (it.positionInRoot().y + it.size.height) }.then(if (dragging) Modifier.background(c.grouped) else Modifier)) {
                            val earlier = blocks.take(i).filter { it.type == "tool" || it.type == "ask" }
                            BlockCard(
                                blockGlyph(b), blockTitle(b), blockSummary(b), b.open && !dragging, onToggle = { b.open = !b.open },
                                handle = Modifier.pointerInput(b.key) {
                                    detectDragGestures(
                                        onDragStart = { o -> val r = bounds[b.key]; drag = Drag(blockTitle(b), b.type, b.tool, b.key, rootOffset.x + with(density) { 140.dp.toPx() }, (r?.first ?: 0f) + o.y) },
                                        onDrag = { change, amount -> change.consume(); drag?.let { d -> drag = Drag(d.label, d.type, d.tool, d.moving, d.x + amount.x, d.y + amount.y) } },
                                        onDragEnd = { finishDrag() }, onDragCancel = { drag = null },
                                    )
                                },
                                onRemove = { blocks.remove(b) },
                            ) { BlockEditor(b, specs, earlier, blocks) }
                        }
                    }
                    Connector(drag != null && drag?.let { insertIndex(it.y, it.moving) } == blocks.size && drag?.y?.let { it in chain.first..chain.second } == true)
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(Dim.radiusGroup)).border(1.dp, c.separator, RoundedCornerShape(Dim.radiusGroup)).padding(16.dp), contentAlignment = Alignment.Center) {
                        Label(if (blocks.isEmpty()) stringResource(R.string.agb_drag_a_tool_from_the_shelf_below_t) else stringResource(R.string.agb_drag_more_blocks_here_or_tap_one_o), Type.subhead, c.textSecondary)
                    }
                    Spacer(Modifier.height(24.dp))
                }
                // The shelf: what can be dragged in.
                Column(Modifier.fillMaxWidth().background(c.surface).onGloballyPositioned { shelf = it.positionInRoot().y to (it.positionInRoot().y + it.size.height) }.padding(top = 8.dp, bottom = 8.dp)) {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Dim.margin), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                        listOf(Triple("ask", "", stringResource(R.string.agb_ask_the_model)), Triple("check", "", stringResource(R.string.agb_only_go_on_if)), Triple("notify", "", stringResource(R.string.agb_notify_me))).forEach { (t, tl, label) ->
                            ShelfChip(label, Glyph.Chat.takeIf { t == "ask" } ?: if (t == "check") Glyph.Check else Glyph.Bolt, { blocks.add(newBlock(t, tl)) },
                                { x, y -> drag = Drag(label, t, tl, null, x, y) }, { dx, dy -> drag?.let { d -> drag = Drag(d.label, d.type, d.tool, null, d.x + dx, d.y + dy) } }, { finishDrag() }, { drag = null })
                        }
                    }
                    Box(Modifier.padding(horizontal = Dim.margin)) { Field(stringResource(R.string.agb_find_a_tool), query, { query = it }, placeholder = "weather, notes, map") }
                    val shown = specs.filter { query.isBlank() || it.name.contains(query.trim(), true) || it.description.contains(query.trim(), true) }
                    LazyHorizontalGrid(GridCells.Fixed(2), Modifier.height((96 * (0.6f + 0.4f * density.fontScale.coerceAtLeast(1f))).dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Dim.margin), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp), userScrollEnabled = drag == null) {
                        items(shown, key = { it.name }) { s ->
                            ShelfChip(s.name, toolGlyph(s.name), { blocks.add(newBlock("tool", s.name)) },
                                { x, y -> drag = Drag(s.name, "tool", s.name, null, x, y) }, { dx, dy -> drag?.let { d -> drag = Drag(d.label, d.type, d.tool, null, d.x + dx, d.y + dy) } }, { finishDrag() }, { drag = null })
                        }
                    }
                }
            }
            // The block being carried.
            drag?.let { d ->
                Box(Modifier.offset { IntOffset((d.x - rootOffset.x - with(density) { 60.dp.toPx() }).roundToInt(), (d.y - rootOffset.y - with(density) { 22.dp.toPx() }).roundToInt()) }
                    .clip(RoundedCornerShape(22.dp)).background(c.accent).padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Label(d.label, Type.subhead, c.surface, maxLines = 1)
                }
            }
        }
    }
}

internal fun triggerSummary(kind: String, hour: String, minute: String, weekdays: Boolean, every: String) = when (kind) {
    "daily" -> "At ${hour.padStart(2, '0')}:${minute.padStart(2, '0')}" + if (weekdays) " on weekdays" else " every day"
    "every" -> "Every $every minutes"
    else -> "Only when you tap Run now"
}

/** The line between two blocks. When something is being dragged over this slot it widens into a bar. */
@Composable
private fun Connector(target: Boolean) {
    val c = MC.colors
    Box(Modifier.fillMaxWidth().height(if (target) 28.dp else 22.dp).padding(start = 31.dp), contentAlignment = Alignment.CenterStart) {
        if (target) Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.accent))
        else Box(Modifier.width(2.dp).fillMaxSize().background(c.separator))
    }
}

@Composable
internal fun BlockCard(
    glyph: Glyph, title: String, summary: String, open: Boolean, onToggle: () -> Unit, handle: Modifier?, onRemove: (() -> Unit)? = null, content: @Composable () -> Unit,
) {
    val c = MC.colors
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Dim.radiusGroup)).background(c.surface)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Pressable(onToggle, Modifier.weight(1f), description = "$title. $summary") { _ ->
                Row(Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconTile(glyph, c.accent, c.accentSoft, 36.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Label(title, Type.body, c.text, maxLines = 1)
                        Label(summary, Type.footnote, c.textSecondary, maxLines = 2)
                    }
                }
            }
            if (onRemove != null) Pressable(onRemove, Modifier.size(40.dp), description = stringResource(R.string.agb_remove_this_block)) { _ -> Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { Icon(Glyph.Close, c.textTertiary, size = 18.dp) } }
            if (handle != null) {
                Box(Modifier.size(width = 44.dp, height = 48.dp).then(handle), contentAlignment = Alignment.Center) { Grip() }
            }
        }
        if (open) Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) { content() }
    }
}

/** Six dots: "hold and drag". */
@Composable
internal fun Grip() {
    val c = MC.colors
    Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(3.dp)) {
        repeat(3) { Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(3.dp)) { repeat(2) { Box(Modifier.size(4.dp).clip(RoundedCornerShape(2.dp)).background(c.textTertiary)) } } }
    }
}

@Composable
internal fun ChipRow(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    val c = MC.colors
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
        options.forEach { (k, label) ->
            val on = k == selected
            Pressable({ onPick(k) }, state = if (on) "selected" else null) { _ ->
                Box(Modifier.clip(RoundedCornerShape(18.dp)).background(if (on) c.accent else c.accentSoft).padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Label(label, Type.subhead, if (on) c.surface else c.accent, maxLines = 1)
                }
            }
        }
    }
}

/** A block on the shelf. Tap to add it at the end; press and drag to put it where you want. */
@Composable
internal fun ShelfChip(label: String, glyph: Glyph, onTap: () -> Unit, onStart: (Float, Float) -> Unit, onMove: (Float, Float) -> Unit, onEnd: () -> Unit, onCancel: () -> Unit) {
    val c = MC.colors
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier.onGloballyPositioned { origin = it.positionInRoot() }.clip(RoundedCornerShape(18.dp)).background(c.accentSoft)
            .pointerInput(label) {
                detectDragGestures(onDragStart = { o -> onStart(origin.x + o.x, origin.y + o.y) }, onDrag = { change, amount -> change.consume(); onMove(amount.x, amount.y) }, onDragEnd = onEnd, onDragCancel = onCancel)
            },
    ) {
        Pressable(onTap, description = "Add $label") { _ ->
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(glyph, c.accent, size = 18.dp)
                Spacer(Modifier.width(6.dp))
                Label(label, Type.subhead, c.accent, maxLines = 1)
            }
        }
    }
}

/** The settings inside an open block. */
@Composable
internal fun BlockEditor(b: Block, specs: List<ToolSpec>, earlier: List<Block>, all: List<Block>, flow: Boolean = false) {
    val c = MC.colors
    fun stepNo(x: Block) = all.indexOf(x) + 1
    @Composable fun Insert(onInsert: (String) -> Unit) {
        if (earlier.isNotEmpty()) {
            Label(stringResource(R.string.agb_put_in_an_earlier_result), Type.footnote, c.textSecondary)
            ChipRow(earlier.map { "{${it.save.ifBlank { "r${it.key}" }}}" to "Step ${stepNo(it)}" }, "") { onInsert(it) }
        }
    }
    when (b.type) {
        "tool" -> {
            val spec = specs.firstOrNull { it.name == b.tool }
            if (spec == null) Label(stringResource(R.string.agb_this_tool_is_not_available_any_mor), Type.subhead, c.error)
            else {
                Label(spec.description.take(160), Type.footnote, c.textSecondary)
                val fields = AgentArgs.fields(spec.schema)
                if (fields.isEmpty()) Label(stringResource(R.string.agb_this_tool_needs_no_inputs), Type.footnote, c.textSecondary)
                fields.forEach { f ->
                    Field(f.name + if (f.required) "" else " (optional)", b.args[f.name].orEmpty(), { b.args[f.name] = it; b.lastField = f.name }, placeholder = f.about.take(60))
                }
                if (fields.isNotEmpty()) Insert { ref -> val k = b.lastField.ifBlank { fields.first().name }; b.args[k] = b.args[k].orEmpty() + ref }
            }
        }
        "ask" -> {
            Field(stringResource(R.string.agb_what_should_the_model_do), b.text, { b.text = it }, placeholder = stringResource(R.string.agb_say_in_one_sentence))
            Insert { b.text = b.text + it }
        }
        "check" -> {
            if (earlier.isEmpty()) Label(stringResource(R.string.agb_put_a_tool_or_a_question_before_th), Type.footnote, c.textSecondary)
            else {
                Label(stringResource(R.string.agb_look_at_the_result_of), Type.footnote, c.textSecondary)
                ChipRow(earlier.map { it.save.ifBlank { "r${it.key}" } to "Step ${stepNo(it)}" }, b.from) { b.from = it }
                ChipRow(listOf("contains" to "contains", "equals" to "is exactly", "notEmpty" to "is not empty"), b.op) { b.op = it }
                if (b.op != "notEmpty") Field(stringResource(R.string.agb_the_words), b.text, { b.text = it }, placeholder = "yes")
                Label(if (flow) "Yes leaves by the left dot, No by the right dot. Leave No empty to stop quietly." else stringResource(R.string.agb_if_this_is_not_true_the_agent_stop), Type.footnote, c.textSecondary)
            }
        }
        else -> {
            Field("Title", b.title, { b.title = it })
            Field(stringResource(R.string.agb_message), b.text, { b.text = it }, placeholder = stringResource(R.string.agb_what_to_tell_you))
            Insert { b.text = b.text + it }
        }
    }
    // Names of results are kept behind the scenes: a tool or question block always keeps its answer for later blocks.
    if ((b.type == "tool" || b.type == "ask") && b.save.isBlank()) b.save = "r${b.key}"
}
