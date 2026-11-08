package com.pocketnode.core

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.DayOfWeek
import kotlinx.serialization.Serializable

/** When an agent starts by itself. `kind` is `manual`, `daily` or `every`. */
@Serializable
data class AgentTrigger(
    val kind: String = "manual",
    val hour: Int = 0,
    val minute: Int = 0,
    val weekdaysOnly: Boolean = false,
    val everyMinutes: Int = 0,
)

/**
 * One block of an agent. `type` is:
 * - `tool`: call [tool] with [args] (a JSON object, with `{name}` filled from earlier results); the answer is kept as [save].
 * - `ask`: give the model [text] (with `{name}` filled in) and keep its answer as [save].
 * - `check`: continue only if the value called [from] satisfies [op] (`contains`, `equals`, `notEmpty`) against [text]; otherwise the run ends quietly.
 * - `notify`: show a notification with the title [tool] and the body [text] (with `{name}` filled in).
 *
 * A flow agent has wires: every step has an [id], the first step is where the run starts, [next] says where it goes on, and a `check` has a
 * second way out, [no]. Wires may split and join again but may not loop back.
 */
@Serializable
data class AgentStep(
    val type: String,
    val tool: String = "",
    val args: String = "{}",
    val text: String = "",
    val save: String = "",
    val from: String = "",
    val op: String = "",
    /** Flow agents only (any step has an [id]): the id of the step that runs next; empty ends the run. Plain agents run in list order. */
    val id: String = "",
    val next: String = "",
    /** For `check` in a flow: the step to run when the check is not met; empty ends the run quietly. */
    val no: String = "",
    /** Where the block sits on the builder canvas, in dp. Not used when running. */
    val x: Int = 0,
    val y: Int = 0,
)

@Serializable
data class AgentDef(val id: String, val name: String, val trigger: AgentTrigger = AgentTrigger(), val steps: List<AgentStep> = emptyList())

object Agents {
    const val MAX_STEPS = 20
    const val MIN_EVERY_MINUTES = 15
    const val MAX_TEXT = 2000
    const val PAUSE_AFTER_FAILURES = 3
    private val VAR = Regex("[a-z][a-z0-9_]{0,23}")
    private val USE = Regex("\\{([a-z][a-z0-9_]{0,23})\\}")
    val TYPES = setOf("tool", "ask", "check", "notify")
    val OPS = setOf("contains", "equals", "notEmpty")

    /** Problems with a definition, in plain words; empty means it can be saved. [knownTools] are the tool names the owner has allowed. */
    fun validate(a: AgentDef, knownTools: Set<String>): List<String> {
        val p = mutableListOf<String>()
        if (a.name.isBlank() || a.name.length > 60) p += "The agent needs a name of 1 to 60 characters."
        when (a.trigger.kind) {
            "manual" -> {}
            "daily" -> if (a.trigger.hour !in 0..23 || a.trigger.minute !in 0..59) p += "The time of day is not valid."
            "every" -> if (a.trigger.everyMinutes < MIN_EVERY_MINUTES) p += "An agent cannot run more often than every $MIN_EVERY_MINUTES minutes."
            else -> p += "Unknown trigger \"${a.trigger.kind.take(20)}\"."
        }
        if (a.steps.isEmpty()) p += "The agent has no steps."
        if (a.steps.size > MAX_STEPS) p += "An agent can have at most $MAX_STEPS steps."
        val flow = isFlow(a)
        val before = if (flow) flowProblems(a, p) else null
        val saved = mutableSetOf<String>()
        a.steps.forEachIndexed { i, s ->
            val n = i + 1
            val defined: Set<String> = if (before != null) before[i] else a.steps.take(i).mapNotNull { it.save.ifEmpty { null } }.toSet()
            if (s.type !in TYPES) { p += "Step $n has an unknown type."; return@forEachIndexed }
            if (s.text.length > MAX_TEXT || s.args.length > MAX_TEXT) p += "Step $n is too long."
            val used = USE.findAll(s.text + s.args).map { it.groupValues[1] }.toSet()
            used.filter { it !in defined }.forEach { p += "Step $n uses {$it}, which no earlier step saves." }
            when (s.type) {
                "tool" -> if (s.tool !in knownTools) p += "Step $n uses a tool that is not available: ${s.tool.take(40)}."
                "ask" -> if (s.text.isBlank()) p += "Step $n has no question."
                "check" -> {
                    if (s.from.isBlank()) p += "Step $n has nothing to check yet. Open it and pick a result from an earlier step; add a tool or Ask the model step before it if there is none."
                    else if (s.from !in defined) p += "Step $n checks a result called ${s.from}, but no step before it saves one with that name."
                    if (s.op !in OPS) p += "Step $n has an unknown check."
                    if (s.op != "notEmpty" && s.text.isEmpty()) p += "Step $n has nothing to compare with."
                }
                "notify" -> if (s.tool.isBlank() || s.text.isBlank()) p += "Step $n needs a title and a message."
            }
            if (s.save.isNotEmpty()) {
                if (s.type !in setOf("tool", "ask")) p += "Step $n cannot save a result."
                else if (!VAR.matches(s.save)) p += "Step $n saves under a name that is not allowed (lower case letters, digits and _)."
                else if (!saved.add(s.save)) p += "Two steps save under the name ${s.save}."
            }
        }
        return p
    }

    /** True when the agent is a flow: its steps are joined by wires instead of running in list order. */
    fun isFlow(a: AgentDef) = a.steps.any { it.id.isNotEmpty() }

    /** Checks the wires of a flow and returns, for each step, the result names saved by the steps that come before it on any path. */
    private fun flowProblems(a: AgentDef, p: MutableList<String>): List<Set<String>> {
        val steps = a.steps
        val index = HashMap<String, Int>()
        steps.forEachIndexed { i, s ->
            if (s.id.isEmpty()) p += "Step ${i + 1} has no id."
            else if (index.put(s.id, i) != null) p += "Two steps share the id ${s.id.take(20)}."
        }
        val out = steps.map { s -> listOf(s.next, s.no).filter { it.isNotEmpty() } }
        steps.forEachIndexed { i, s ->
            if (s.no.isNotEmpty() && s.type != "check") p += "Step ${i + 1} cannot have a second way out."
            out[i].filter { it !in index }.forEach { p += "Step ${i + 1} leads to a step that does not exist." }
        }
        val edges = out.map { l -> l.mapNotNull { index[it] } }
        // Reachable from the first step.
        val seen = BooleanArray(steps.size)
        val stack = ArrayDeque<Int>()
        if (steps.isNotEmpty()) { seen[0] = true; stack.add(0) }
        while (stack.isNotEmpty()) { val i = stack.removeLast(); edges[i].forEach { if (!seen[it]) { seen[it] = true; stack.add(it) } } }
        seen.forEachIndexed { i, ok -> if (!ok) p += "Step ${i + 1} is not joined to the start." }
        // No loops: repeatedly drop steps that lead nowhere left.
        val remaining = edges.map { it.toMutableList() }
        val alive = BooleanArray(steps.size) { true }
        var changed = true
        while (changed) {
            changed = false
            for (i in steps.indices) if (alive[i] && remaining[i].none { alive[it] }) { alive[i] = false; changed = true }
        }
        if (alive.any { it }) { p += "The wires make a loop; a flow can only go forward."; return steps.map { emptySet() } }
        // Names available at each step: saved by any step that can reach it.
        val parents = List(steps.size) { mutableListOf<Int>() }
        edges.forEachIndexed { i, l -> l.forEach { parents[it] += i } }
        val memo = arrayOfNulls<Set<String>>(steps.size)
        fun ancestors(i: Int): Set<String> = memo[i] ?: parents[i].flatMap { q -> ancestors(q) + listOfNotNull(steps[q].save.ifEmpty { null }) }.toSet().also { memo[i] = it }
        return steps.indices.map { ancestors(it) }
    }

    /** The next time the agent should start, after [after]; null for a manual agent. */
    fun nextRun(t: AgentTrigger, after: Instant, zone: ZoneId): Instant? = when (t.kind) {
        "every" -> after.plusSeconds(t.everyMinutes.coerceAtLeast(MIN_EVERY_MINUTES) * 60L)
        "daily" -> {
            var day = after.atZone(zone).toLocalDate()
            var out: Instant
            while (true) {
                out = day.atTime(LocalTime.of(t.hour, t.minute)).atZone(zone).toInstant()
                val weekend = day.dayOfWeek == DayOfWeek.SATURDAY || day.dayOfWeek == DayOfWeek.SUNDAY
                if (out.isAfter(after) && !(t.weekdaysOnly && weekend)) break
                day = day.plusDays(1)
            }
            out
        }
        else -> null
    }

    fun fill(template: String, vars: Map<String, String>): String = USE.replace(template) { vars[it.groupValues[1]] ?: "" }
}

fun interface AgentToolCaller { fun call(tool: String, argsJson: String): String }
fun interface AgentAsker { fun ask(prompt: String): String }
fun interface AgentNotifier { fun notify(title: String, text: String) }

enum class AgentOutcome { DONE, STOPPED_BY_CHECK, FAILED }
data class AgentRun(val outcome: AgentOutcome, val lines: List<String>)

/** Runs one agent from start to end. Any tool answer that starts with `Error:` or `Denied:` ends the run as failed. */
class AgentRunner(private val tools: AgentToolCaller, private val model: AgentAsker, private val notifier: AgentNotifier, private val maxAnswer: Int = 4000) {
    fun run(a: AgentDef): AgentRun {
        val vars = mutableMapOf<String, String>()
        val lines = mutableListOf<String>()
        val flow = Agents.isFlow(a)
        val at = a.steps.withIndex().associate { (i, s) -> s.id to i }
        fun after(i: Int, id: String) = if (flow) (if (id.isEmpty()) -1 else at[id] ?: -1) else if (i + 1 < a.steps.size) i + 1 else -1
        var i = 0
        var ran = 0
        while (i in a.steps.indices) {
            if (++ran > Agents.MAX_STEPS) { lines += "Stopped: too many steps in one run."; return AgentRun(AgentOutcome.FAILED, lines) }
            val s = a.steps[i]
            val n = i + 1
            var go = after(i, s.next)
            try {
                when (s.type) {
                    "tool" -> {
                        val r = tools.call(s.tool, Agents.fill(s.args, vars))
                        if (r.startsWith("Error:") || r.startsWith("Denied:")) { lines += "Step $n (${s.tool}) failed: ${r.take(160)}"; return AgentRun(AgentOutcome.FAILED, lines) }
                        if (s.save.isNotEmpty()) vars[s.save] = r.take(maxAnswer)
                        lines += "Step $n: ${s.tool} done"
                    }
                    "ask" -> {
                        val r = model.ask(Agents.fill(s.text, vars))
                        if (s.save.isNotEmpty()) vars[s.save] = r.take(maxAnswer)
                        lines += "Step $n: asked the model"
                    }
                    "check" -> {
                        val v = vars[s.from].orEmpty()
                        val ok = when (s.op) { "contains" -> v.contains(Agents.fill(s.text, vars), ignoreCase = true); "equals" -> v.trim().equals(Agents.fill(s.text, vars).trim(), ignoreCase = true); else -> v.isNotBlank() }
                        if (ok) lines += "Step $n: check met"
                        else if (flow && s.no.isNotEmpty()) { lines += "Step $n: check not met, took the other way"; go = after(i, s.no) }
                        else { lines += "Step $n: check not met, stopped"; return AgentRun(AgentOutcome.STOPPED_BY_CHECK, lines) }
                    }
                    "notify" -> { notifier.notify(Agents.fill(s.tool, vars).take(80), Agents.fill(s.text, vars).take(500)); lines += "Step $n: notified" }
                }
            } catch (e: Exception) {
                lines += "Step $n failed: ${(e.message ?: e::class.simpleName).orEmpty().take(160)}"
                return AgentRun(AgentOutcome.FAILED, lines)
            }
            i = go
        }
        return AgentRun(AgentOutcome.DONE, lines)
    }
}

/** Keeps track of failures in a row; after [Agents.PAUSE_AFTER_FAILURES] the agent is paused until the owner turns it on. */
data class AgentState(val failuresInRow: Int = 0, val paused: Boolean = false) {
    fun after(outcome: AgentOutcome): AgentState = when (outcome) {
        AgentOutcome.FAILED -> (failuresInRow + 1).let { AgentState(it, paused || it >= Agents.PAUSE_AFTER_FAILURES) }
        else -> AgentState(0, paused)
    }
}
