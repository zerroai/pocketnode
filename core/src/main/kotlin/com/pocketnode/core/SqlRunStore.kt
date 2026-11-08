package com.pocketnode.core

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Durable [RunStore]. Every write that carries a fence re-reads the session's current fence
 * inside the same transaction, so a superseded run cannot commit.
 */
class SqlRunStore(private val db: SqlDb) : RunStore {
    private val json = Json
    private val callsSer = ListSerializer(ToolCall.serializer())

    init {
        db.transaction { tx ->
            tx.exec("CREATE TABLE IF NOT EXISTS sessions (id TEXT PRIMARY KEY, fence INTEGER NOT NULL)")
            tx.exec("CREATE TABLE IF NOT EXISTS messages (session_id TEXT NOT NULL, seq INTEGER NOT NULL, role TEXT NOT NULL, text TEXT NOT NULL, PRIMARY KEY (session_id, seq))")
            tx.exec("CREATE TABLE IF NOT EXISTS runs (run_id TEXT PRIMARY KEY, session_id TEXT NOT NULL, fence INTEGER NOT NULL, finished INTEGER NOT NULL DEFAULT 0, created INTEGER NOT NULL)")
            tx.exec(
                "CREATE TABLE IF NOT EXISTS steps (run_id TEXT NOT NULL, idx INTEGER NOT NULL, kind TEXT NOT NULL, status TEXT NOT NULL, " +
                    "text TEXT NOT NULL, calls TEXT NOT NULL, call TEXT, tool_free INTEGER NOT NULL, PRIMARY KEY (run_id, idx))",
            )
            // Added after the first spike builds; older databases get the column here.
            val hasTitle = tx.query("PRAGMA table_info(sessions)") { it.string(1) }.contains("title")
            if (!hasTitle) tx.exec("ALTER TABLE sessions ADD COLUMN title TEXT NOT NULL DEFAULT ''")
            val hasPinned = tx.query("PRAGMA table_info(sessions)") { it.string(1) }.contains("pinned")
            if (!hasPinned) tx.exec("ALTER TABLE sessions ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
        }
    }

    override fun claimRun(sessionId: String, runId: String, userText: String): RunInfo = db.transaction { tx ->
        tx.exec("INSERT OR IGNORE INTO sessions (id, fence) VALUES (?, 0)", sessionId)
        tx.exec("UPDATE sessions SET fence = fence + 1 WHERE id = ?", sessionId)
        val fence = tx.query("SELECT fence FROM sessions WHERE id = ?", sessionId) { it.long(0) }.single()
        appendMessage(tx, sessionId, "user", userText)
        tx.exec("INSERT INTO runs (run_id, session_id, fence, created) VALUES (?, ?, ?, ?)", runId, sessionId, fence, System.currentTimeMillis())
        RunInfo(runId, sessionId, fence)
    }

    override fun steps(runId: String): List<Step> = db.transaction { tx ->
        tx.query("SELECT idx, kind, status, text, calls, call, tool_free FROM steps WHERE run_id = ? ORDER BY idx", runId) { r ->
            val call = r.string(5)
            Step(
                idx = r.long(0).toInt(),
                kind = StepKind.valueOf(r.string(1)),
                status = StepStatus.valueOf(r.string(2)),
                text = r.string(3),
                calls = json.decodeFromString(callsSer, r.string(4)),
                call = if (call.isEmpty()) null else json.decodeFromString(ToolCall.serializer(), call),
                toolFree = r.long(6) != 0L,
            )
        }
    }

    override fun history(sessionId: String): List<Msg> = db.transaction { tx ->
        tx.query("SELECT role, text FROM messages WHERE session_id = ? AND role != 'tool' ORDER BY seq", sessionId) {
            if (it.string(0) == "user") Msg.User(it.string(1)) else Msg.Assistant(it.string(1))
        }
    }

    override fun timeline(sessionId: String): List<Msg> = db.transaction { tx ->
        tx.query("SELECT role, text FROM messages WHERE session_id = ? ORDER BY seq", sessionId) {
            when (it.string(0)) {
                "user" -> Msg.User(it.string(1))
                "tool" -> it.string(1).let { t -> Msg.ToolResult("", t.substringBefore('\n'), t.substringAfter('\n', "")) }
                else -> Msg.Assistant(it.string(1))
            }
        }
    }

    override fun reopenFailed(sessionId: String): RunInfo? = db.transaction { tx ->
        val run = tx.query("SELECT run_id, finished FROM runs WHERE session_id = ? ORDER BY created DESC, rowid DESC LIMIT 1", sessionId) { it.string(0) to it.long(1) }.firstOrNull()
            ?: return@transaction null
        if (run.second == 0L) return@transaction null
        val last = tx.query("SELECT seq, role, text FROM messages WHERE session_id = ? ORDER BY seq DESC LIMIT 1", sessionId) { Triple(it.long(0), it.string(1), it.string(2)) }.firstOrNull()
            ?: return@transaction null
        if (last.second != "assistant" || !last.third.startsWith(MODEL_ERROR)) return@transaction null
        tx.exec("DELETE FROM messages WHERE session_id = ? AND seq = ?", sessionId, last.first)
        tx.exec("UPDATE sessions SET fence = fence + 1 WHERE id = ?", sessionId)
        val fence = tx.query("SELECT fence FROM sessions WHERE id = ?", sessionId) { it.long(0) }.single()
        tx.exec("UPDATE runs SET finished = 0, fence = ? WHERE run_id = ?", fence, run.first)
        RunInfo(run.first, sessionId, fence)
    }

    override fun putStep(run: RunInfo, step: Step) {
        db.transaction { tx ->
            checkFence(tx, run)
            tx.exec(
                "INSERT OR REPLACE INTO steps (run_id, idx, kind, status, text, calls, call, tool_free) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                run.runId, step.idx, step.kind.name, step.status.name, step.text,
                json.encodeToString(callsSer, step.calls),
                step.call?.let { json.encodeToString(ToolCall.serializer(), it) } ?: "",
                if (step.toolFree) 1 else 0,
            )
            // The screen shows what each tool did, so a finished tool step also leaves a row in the conversation.
            if (step.kind == StepKind.TOOL && (step.status == StepStatus.DONE || step.status == StepStatus.FAILED)) {
                appendMessage(tx, run.sessionId, "tool", step.call?.name.orEmpty() + "\n" + step.text)
            }
        }
    }

    override fun finishRun(run: RunInfo, outcome: RunOutcome, text: String) {
        db.transaction { tx ->
            checkFence(tx, run)
            appendMessage(tx, run.sessionId, "assistant", text)
            tx.exec("UPDATE runs SET finished = 1 WHERE run_id = ?", run.runId)
        }
    }

    override fun unfinishedRuns(): List<RunInfo> = db.transaction { tx ->
        tx.query("SELECT run_id, session_id, fence FROM runs WHERE finished = 0 ORDER BY created, rowid") {
            RunInfo(it.string(0), it.string(1), it.long(2))
        }
    }

    override fun sessions(): List<SessionSummary> = db.transaction { tx ->
        tx.query(
            "SELECT s.id, s.title, " +
                "COALESCE((SELECT text FROM messages m WHERE m.session_id = s.id AND m.role != 'tool' ORDER BY seq DESC LIMIT 1), ''), " +
                "COALESCE((SELECT role FROM messages m WHERE m.session_id = s.id AND m.role != 'tool' ORDER BY seq DESC LIMIT 1), ''), " +
                "COALESCE((SELECT MAX(created) FROM runs r WHERE r.session_id = s.id), 0) AS last, s.pinned " +
                "FROM sessions s ORDER BY s.pinned DESC, last DESC, s.rowid DESC",
        ) { SessionSummary(it.string(0), it.string(1), it.string(2), it.string(3) == "user", it.long(4), it.long(5) != 0L) }
    }

    override fun deleteSession(sessionId: String): Boolean = db.transaction { tx ->
        val exists = tx.query("SELECT 1 FROM sessions WHERE id = ?", sessionId) { it.long(0) }.isNotEmpty()
        tx.exec("DELETE FROM steps WHERE run_id IN (SELECT run_id FROM runs WHERE session_id = ?)", sessionId)
        tx.exec("DELETE FROM runs WHERE session_id = ?", sessionId)
        tx.exec("DELETE FROM messages WHERE session_id = ?", sessionId)
        tx.exec("DELETE FROM sessions WHERE id = ?", sessionId)
        exists
    }

    override fun importSession(sessionId: String, title: String, messages: List<Msg>, lastActivityMillis: Long): Boolean = db.transaction { tx ->
        if (tx.query("SELECT 1 FROM sessions WHERE id = ?", sessionId) { it.long(0) }.isNotEmpty()) return@transaction false
        tx.exec("INSERT INTO sessions (id, fence, title) VALUES (?, 0, ?)", sessionId, title)
        for (m in messages) when (m) {
            is Msg.User -> appendMessage(tx, sessionId, "user", m.text)
            is Msg.Assistant -> appendMessage(tx, sessionId, "assistant", m.text)
            is Msg.ToolResult -> appendMessage(tx, sessionId, "tool", m.name + "\n" + m.text)
        }
        // A finished run gives the conversation its place in the list, by the time of its last activity.
        tx.exec("INSERT INTO runs (run_id, session_id, fence, finished, created) VALUES (?, ?, 0, 1, ?)", "import:$sessionId", sessionId, lastActivityMillis)
        true
    }

    override fun setPinned(sessionId: String, pinned: Boolean) {
        db.transaction { it.exec("UPDATE sessions SET pinned = ? WHERE id = ?", if (pinned) 1 else 0, sessionId) }
    }

    override fun searchSessions(query: String): List<SessionHit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        val texts = db.transaction { tx ->
            tx.query(
                "SELECT m.session_id, m.text FROM messages m WHERE m.role IN ('user','assistant') AND m.text LIKE ? ESCAPE '\\' ORDER BY m.seq",
                like,
            ) { it.string(0) to it.string(1) }
        }.toMap() // the last matching message of each conversation wins
        return sessions().mapNotNull { sum ->
            val text = texts[sum.id]
            when {
                text != null -> SessionHit(sum, ChatSearch.snippet(text, q))
                sum.title.contains(q, ignoreCase = true) -> SessionHit(sum, ChatSearch.readable(sum.lastText).take(80))
                else -> null
            }
        }
    }

    override fun deleteAllSessions() {
        db.transaction { tx ->
            for (t in listOf("steps", "runs", "messages", "sessions")) tx.exec("DELETE FROM $t")
        }
    }

    override fun setSessionTitle(sessionId: String, title: String) {
        db.transaction { tx ->
            tx.exec("INSERT OR IGNORE INTO sessions (id, fence) VALUES (?, 0)", sessionId)
            tx.exec("UPDATE sessions SET title = ? WHERE id = ?", title, sessionId)
        }
    }

    private fun appendMessage(tx: Tx, sessionId: String, role: String, text: String) {
        tx.exec(
            "INSERT INTO messages (session_id, seq, role, text) VALUES (?, COALESCE((SELECT MAX(seq) FROM messages WHERE session_id = ?), 0) + 1, ?, ?)",
            sessionId, sessionId, role, text,
        )
    }

    private fun checkFence(tx: Tx, run: RunInfo) {
        val current = tx.query("SELECT fence FROM sessions WHERE id = ?", run.sessionId) { it.long(0) }.firstOrNull()
        if (current != run.fence) {
            throw StaleFenceException("run ${run.runId} holds fence ${run.fence}, session is at $current")
        }
    }
}
