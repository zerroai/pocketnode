package com.pocketnode.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pocketnode.app.AppModel
import com.pocketnode.app.CoreHost
import com.pocketnode.app.R
import com.pocketnode.app.Route
import com.pocketnode.core.AgentDef
import com.pocketnode.core.AgentStep
import com.pocketnode.core.AgentTrigger
import com.pocketnode.core.SavedAgent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// The Agents tab: jobs that run on their own. Text is written here in plain English; moving it to resources belongs to G2.

private fun two(n: Int) = n.toString().padStart(2, '0')

internal fun triggerText(t: AgentTrigger): String = when (t.kind) {
    "daily" -> "At ${two(t.hour)}:${two(t.minute)}" + if (t.weekdaysOnly) " on weekdays" else " every day"
    "every" -> if (t.everyMinutes % 60 == 0) "Every ${t.everyMinutes / 60} hour" + (if (t.everyMinutes == 60) "" else "s") else "Every ${t.everyMinutes} minutes"
    else -> "Only when you tap Run now"
}

private fun ago(at: Long): String {
    val m = (System.currentTimeMillis() - at) / 60_000
    return when { m < 1 -> "just now"; m < 60 -> "$m min ago"; m < 24 * 60 -> "${m / 60} h ago"; else -> SimpleDateFormat("d MMM", Locale.US).format(Date(at)) }
}

private fun statusText(a: SavedAgent): String = when {
    a.state.paused -> "Paused after ${com.pocketnode.core.Agents.PAUSE_AFTER_FAILURES} failed runs"
    !a.enabled -> "Off"
    a.lastAt == 0L -> triggerText(a.def.trigger)
    else -> triggerText(a.def.trigger) + ". Last run " + ago(a.lastAt) + when (a.lastOutcome) { "FAILED" -> ", failed"; "STOPPED_BY_CHECK" -> ", nothing to report"; else -> "" }
}

private fun stepText(s: AgentStep): String = when (s.type) {
    "tool" -> "Use ${s.tool}" + com.pocketnode.core.AgentArgs.parse(s.args).filterValues { it.isNotBlank() }.values.joinToString(", ").let { if (it.isBlank()) "" else " ($it)" }.take(80)
    "ask" -> "Ask the model: " + s.text.take(90) + if (s.text.length > 90) "..." else ""
    "check" -> "Go on only if ${s.from} " + when (s.op) { "contains" -> "contains “${s.text}”"; "equals" -> "is “${s.text}”"; else -> "is not empty" }
    "notify" -> "Notify you: ${s.tool}"
    else -> s.type
}

/** Opens a new chat with a starting sentence in the message box. Nothing is sent until the owner finishes it and presses send. */
private fun describeInChat() = AppModel.receiveShare("Make an agent that every day at 7:30 ", emptyList())

@Composable
fun AgentsScreen() {
    val c = MC.colors
    LaunchedEffect(Unit) { while (true) { CoreHost.loadAgents(); kotlinx.coroutines.delay(3000) } }
    val items = CoreHost.agentList
    LazyColumn(Modifier.fillMaxSize().background(c.grouped), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 120.dp)) {
        item {
            LargeTitleHeader("Agents") {
                Pressable({ AppModel.push(Route.AgentCanvas(null)) }, Modifier.size(Dim.touch), description = "Add a new agent") { pressed ->
                    Box(Modifier.size(Dim.touch), contentAlignment = Alignment.Center) { Icon(Glyph.Plus, if (pressed) c.textSecondary else c.accent, size = 26.dp, weight = 2f) }
                }
            }
        }
        if (items.isEmpty()) item {
            if (!CoreHost.agentsLoaded) Box(Modifier.fillMaxSize().padding(top = 80.dp), contentAlignment = Alignment.Center) { Spinner(c.textSecondary, 24.dp) }
            else EmptyState(Glyph.Bolt, "No agents yet", "An agent does a job on its own, such as telling you each morning if it will rain. You make each one yourself.") {
                Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                    PrimaryButton("Add a new agent", { AppModel.push(Route.AgentCanvas(null)) })
                    SecondaryButton("Describe it in a chat", { describeInChat() })
                }
            }
        } else {
            item {
                Group(Modifier.padding(horizontal = Dim.margin)) {
                    items.forEachIndexed { i, a ->
                        ListRow(
                            a.def.name, subtitle = statusText(a), dividerInset = if (i == items.lastIndex) null else 16.dp, chevron = false,
                            leading = { IconTile(Glyph.Bolt, c.accent, c.accentSoft) },
                            trailing = { Toggle(a.enabled && !a.state.paused, { on -> CoreHost.switchAgent(a.def.id, on) }) },
                            onClick = { AppModel.push(Route.Agent(a.def.id)) },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Group(Modifier.padding(horizontal = Dim.margin)) {
                    ListRow("Add a new agent", subtitle = "Pick tools and wire them into a flow", chevron = true, onClick = { AppModel.push(Route.AgentCanvas(null)) })
                    ListRow("Describe a new agent in a chat", subtitle = "Say what you want, then approve the plan", chevron = true, onClick = { describeInChat() }, dividerInset = null)
                }
                SectionFooter("Agents use your model and your daily limit. Each run is listed in Activity, and three failed runs in a row pause an agent.")
            }
        }
    }
}

@Composable
fun AgentScreen(id: String) {
    val c = MC.colors
    LaunchedEffect(Unit) { while (true) { CoreHost.loadAgents(); kotlinx.coroutines.delay(2000) } }
    val a = CoreHost.agentList.firstOrNull { it.def.id == id }
    var confirmDelete by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    Frame(c.grouped) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            TopBar(a?.def?.name ?: "Agent", onBack = { AppModel.pop() })
            if (a == null) { Label("This agent is gone.", Type.subhead, c.textSecondary, Modifier.padding(Dim.margin)); return@Column }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Dim.margin).padding(bottom = 24.dp)) {
                Spacer(Modifier.height(8.dp))
                Group {
                    ListRow("On", subtitle = statusText(a), trailing = { Toggle(a.enabled && !a.state.paused, { on -> CoreHost.switchAgent(a.def.id, on) }) }, dividerInset = null)
                }
                SectionHeader("STEPS")
                Group {
                    a.def.steps.forEachIndexed { i, s -> ListRow("${i + 1}. " + stepText(s), dividerInset = if (i == a.def.steps.lastIndex) null else 16.dp) }
                }
                Spacer(Modifier.height(8.dp))
                Group { ListRow("Edit the steps", subtitle = "Drag blocks to change what it does", chevron = true, onClick = { AppModel.push(Route.AgentCanvas(a.def.id)) }, dividerInset = null) }
                Spacer(Modifier.height(16.dp))
                PrimaryButton(if (running) "Running" else "Run now", { running = true; CoreHost.runAgent(a.def.id) { running = false } }, enabled = !running, loading = running)
                if (a.lastLog.isNotBlank()) {
                    SectionHeader("LAST RUN")
                    Group { Label(a.lastLog, Type.subhead, c.text, Modifier.padding(16.dp)) }
                }
                Spacer(Modifier.height(16.dp))
                Group { ListRow("Delete this agent", titleColor = c.error, onClick = { confirmDelete = true }, dividerInset = null) }
            }
        }
    }
    if (confirmDelete) ConfirmDialog("Delete this agent?", "It stops running and its steps are removed. Earlier runs stay in Activity.", "Delete", { confirmDelete = false; CoreHost.deleteAgent(id); AppModel.pop() }, { confirmDelete = false }, destructive = true)
}
