package com.pocketnode.core

/** Thrown when a write carries a fence older than the session's current claim. */
class StaleFenceException(message: String) : RuntimeException(message)

/**
 * The only owner of transcript and step state. Every write supplies the fence the run was
 * given at [claimRun]; implementations must reject a stale fence inside the same transaction
 * as the write, so a run that lost its claim (for example after a restart) cannot commit.
 */
interface RunStore {
    /** Creates a run, takes the session's claim and returns the new fence. Supersedes earlier claims. */
    fun claimRun(sessionId: String, runId: String, userText: String): RunInfo

    /** What the model should see of earlier turns: the questions and the answers. Tool activity is not included. */
    fun history(sessionId: String): List<Msg>

    /**
     * Everything the screen shows, in order: questions, answers and each tool that ran in between
     * (as [Msg.ToolResult] with an empty call id; its text starting with "Error:" or "Denied:" means it failed).
     */
    fun timeline(sessionId: String): List<Msg>

    /**
     * Reopens the session's latest run if it ended with a model error, so it can continue where it stopped (tools that
     * already ran are not run again). Returns null if there is nothing to retry.
     */
    fun reopenFailed(sessionId: String): RunInfo?

    fun steps(runId: String): List<Step>

    fun putStep(run: RunInfo, step: Step)

    fun finishRun(run: RunInfo, outcome: RunOutcome, text: String)

    /** Runs that were claimed but never finished, e.g. because the process was killed. */
    fun unfinishedRuns(): List<RunInfo>

    /** Every conversation, most recently active first. */
    fun sessions(): List<SessionSummary>

    /** Removes every conversation, message, run and step. Pack storage is not touched. */
    fun deleteAllSessions()

    /** Display name for a conversation, e.g. the Telegram chat's name. Empty clears it. */
    fun setSessionTitle(sessionId: String, title: String)

    /** Removes one conversation with its messages, runs and steps. False if there is no such conversation. */
    fun deleteSession(sessionId: String): Boolean

    /** Pinned conversations are listed first. */
    fun setPinned(sessionId: String, pinned: Boolean)

    /** Adds a whole conversation, for an import. False, and nothing changes, if a conversation with this id already exists. */
    fun importSession(sessionId: String, title: String, messages: List<Msg>, lastActivityMillis: Long): Boolean

    /** Conversations whose title or any of whose questions and answers contain [query] (ignoring case), in list order, each with the words around the match. */
    fun searchSessions(query: String): List<SessionHit>
}

/** The start of the stored text when a run ends because the model could not be reached. */
const val MODEL_ERROR = "Model error: "

data class SessionSummary(
    val id: String,
    val title: String,
    val lastText: String,
    val lastIsUser: Boolean,
    val lastActivityMillis: Long,
    val pinned: Boolean = false,
)

/** One search result: the conversation and a short piece of the text that matched. */
data class SessionHit(val session: SessionSummary, val snippet: String)

/** Shared by the stores: cuts a short piece of [text] around the first match of [query]. */
object ChatSearch {
    /** What a question or answer looks like to a reader: no attachment references, no links to files that are not there. */
    fun readable(text: String): String = MarkdownLite.hideInternalLinks(Attachments.parse(text).text.ifBlank { text })

    fun snippet(text: String, query: String, radius: Int = 40): String {
        val clean = readable(text).replace(Regex("\\s+"), " ").trim()
        val at = clean.indexOf(query.trim(), ignoreCase = true)
        if (at < 0) return clean.take(radius * 2)
        val from = (at - radius).coerceAtLeast(0)
        val to = (at + query.trim().length + radius).coerceAtMost(clean.length)
        return (if (from > 0) "..." else "") + clean.substring(from, to) + (if (to < clean.length) "..." else "")
    }
}

/** In-memory store used by tests and as the reference for the SQLite implementation. */
class InMemoryRunStore : RunStore {
    private class SessionState {
        var fence = 0L
        var title = ""
        var lastActivity = 0L
        val messages = mutableListOf<Msg>()
        var lastRun: String? = null
        var pinned = false
    }

    private val sessions = mutableMapOf<String, SessionState>()
    private val steps = mutableMapOf<String, MutableMap<Int, Step>>()
    private val open = linkedMapOf<String, RunInfo>()

    @Synchronized
    override fun claimRun(sessionId: String, runId: String, userText: String): RunInfo {
        val s = sessions.getOrPut(sessionId) { SessionState() }
        s.fence += 1
        s.lastActivity = System.currentTimeMillis()
        s.messages += Msg.User(userText)
        val info = RunInfo(runId, sessionId, s.fence)
        open[runId] = info
        steps[runId] = mutableMapOf()
        s.lastRun = runId
        return info
    }

    @Synchronized
    override fun history(sessionId: String): List<Msg> = sessions[sessionId]?.messages?.filter { it !is Msg.ToolResult }.orEmpty()

    @Synchronized
    override fun timeline(sessionId: String): List<Msg> = sessions[sessionId]?.messages?.toList().orEmpty()

    @Synchronized
    override fun reopenFailed(sessionId: String): RunInfo? {
        val s = sessions[sessionId] ?: return null
        val runId = s.lastRun ?: return null
        if (runId in open) return null
        val last = s.messages.lastOrNull() as? Msg.Assistant ?: return null
        if (!last.text.startsWith(MODEL_ERROR)) return null
        s.messages.removeAt(s.messages.lastIndex)
        s.fence += 1
        val info = RunInfo(runId, sessionId, s.fence)
        open[runId] = info
        return info
    }

    @Synchronized
    override fun steps(runId: String): List<Step> = steps[runId]?.values?.sortedBy { it.idx }.orEmpty()

    @Synchronized
    override fun putStep(run: RunInfo, step: Step) {
        checkFence(run)
        steps.getValue(run.runId)[step.idx] = step
        if (step.kind == StepKind.TOOL && (step.status == StepStatus.DONE || step.status == StepStatus.FAILED)) {
            sessions.getValue(run.sessionId).messages += Msg.ToolResult("", step.call?.name.orEmpty(), step.text)
        }
    }

    @Synchronized
    override fun finishRun(run: RunInfo, outcome: RunOutcome, text: String) {
        checkFence(run)
        sessions.getValue(run.sessionId).messages += Msg.Assistant(text)
        open.remove(run.runId)
    }

    @Synchronized
    override fun unfinishedRuns(): List<RunInfo> = open.values.toList()

    @Synchronized
    override fun sessions(): List<SessionSummary> = sessions.entries.sortedWith(compareByDescending<Map.Entry<String, SessionState>> { it.value.pinned }.thenByDescending { it.value.lastActivity }).map { (id, s) ->
        val last = s.messages.lastOrNull { it !is Msg.ToolResult }
        SessionSummary(id, s.title, when (last) { is Msg.User -> last.text; is Msg.Assistant -> last.text; else -> "" }, last is Msg.User, s.lastActivity, s.pinned)
    }

    @Synchronized
    override fun deleteSession(sessionId: String): Boolean {
        val s = sessions.remove(sessionId) ?: return false
        s.lastRun?.let { steps.remove(it); open.remove(it) }
        open.entries.removeAll { it.value.sessionId == sessionId }
        return true
    }

    @Synchronized
    override fun setPinned(sessionId: String, pinned: Boolean) { sessions[sessionId]?.pinned = pinned }

    @Synchronized
    override fun importSession(sessionId: String, title: String, messages: List<Msg>, lastActivityMillis: Long): Boolean {
        if (sessionId in sessions) return false
        sessions[sessionId] = SessionState().also { s -> s.title = title; s.lastActivity = lastActivityMillis; s.messages += messages }
        return true
    }

    @Synchronized
    override fun searchSessions(query: String): List<SessionHit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return sessions().mapNotNull { sum ->
            val s = sessions.getValue(sum.id)
            val hit = s.messages.lastOrNull { m -> (m is Msg.User && m.text.contains(q, true)) || (m is Msg.Assistant && m.text.contains(q, true)) }
            val text = when (hit) { is Msg.User -> hit.text; is Msg.Assistant -> hit.text; else -> null }
            when {
                text != null -> SessionHit(sum, ChatSearch.snippet(text, q))
                s.title.contains(q, true) -> SessionHit(sum, ChatSearch.readable(sum.lastText).take(80))
                else -> null
            }
        }
    }

    @Synchronized
    override fun deleteAllSessions() {
        sessions.clear(); steps.clear(); open.clear()
    }

    @Synchronized
    override fun setSessionTitle(sessionId: String, title: String) {
        sessions.getOrPut(sessionId) { SessionState() }.title = title
    }

    private fun checkFence(run: RunInfo) {
        val current = sessions[run.sessionId]?.fence
        if (current != run.fence) {
            throw StaleFenceException("run ${run.runId} holds fence ${run.fence}, session is at $current")
        }
    }
}
