package com.pocketnode.core

import kotlinx.serialization.json.Json

/** What the list shows for one saved agent. */
data class SavedAgent(
    val def: AgentDef,
    val enabled: Boolean,
    /** Millis of the next planned start, or 0 for none. */
    val nextAt: Long,
    val state: AgentState,
    val lastAt: Long,
    val lastOutcome: String,
    val lastLog: String,
)

/** The owner's agents, kept in the app's database. Like [ReminderStore] it remembers and does the arithmetic; the platform wakes the phone. */
class AgentStore(private val db: SqlDb, private val now: () -> Long = System::currentTimeMillis) {
    private val json = Json { ignoreUnknownKeys = true }

    init {
        db.transaction { tx ->
            tx.exec(
                "CREATE TABLE IF NOT EXISTS agents (id TEXT PRIMARY KEY, def TEXT NOT NULL, enabled INTEGER NOT NULL DEFAULT 1, next_at INTEGER NOT NULL DEFAULT 0, " +
                    "failures INTEGER NOT NULL DEFAULT 0, paused INTEGER NOT NULL DEFAULT 0, last_at INTEGER NOT NULL DEFAULT 0, last_outcome TEXT NOT NULL DEFAULT '', last_log TEXT NOT NULL DEFAULT '')",
            )
        }
    }

    private val cols = "def, enabled, next_at, failures, paused, last_at, last_outcome, last_log"
    private fun read(r: Row) = SavedAgent(
        json.decodeFromString(AgentDef.serializer(), r.string(0)), r.long(1) == 1L, r.long(2),
        AgentState(r.long(3).toInt(), r.long(4) == 1L), r.long(5), r.string(6), r.string(7),
    )

    /** Saves a new agent or replaces one with the same id. The caller has already run [Agents.validate]. A saved change clears any pause. */
    fun save(a: AgentDef, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): SavedAgent {
        val t = now()
        val next = Agents.nextRun(a.trigger, java.time.Instant.ofEpochMilli(t), zone)?.toEpochMilli() ?: 0L
        val text = json.encodeToString(AgentDef.serializer(), a)
        db.transaction { tx ->
            val have = tx.query("SELECT COUNT(*) FROM agents WHERE id <> ?", a.id) { it.long(0) }.first()
            require(have < MAX_AGENTS) { "There are already $MAX_AGENTS agents. Remove one first." }
            tx.exec("INSERT INTO agents (id, def, enabled, next_at) VALUES (?, ?, 1, ?) ON CONFLICT(id) DO UPDATE SET def = excluded.def, enabled = 1, next_at = excluded.next_at, failures = 0, paused = 0", a.id, text, next)
        }
        return get(a.id)!!
    }

    fun get(id: String): SavedAgent? = db.transaction { it.query("SELECT $cols FROM agents WHERE id = ?", id) { r -> read(r) }.firstOrNull() }
    fun list(): List<SavedAgent> = db.transaction { it.query("SELECT $cols FROM agents ORDER BY rowid") { r -> read(r) } }
    fun remove(id: String) = db.transaction { it.exec("DELETE FROM agents WHERE id = ?", id) }

    /** Switching on also clears a pause and plans the next start. */
    fun setEnabled(id: String, on: Boolean, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()) {
        val a = get(id) ?: return
        val next = if (on) Agents.nextRun(a.def.trigger, java.time.Instant.ofEpochMilli(now()), zone)?.toEpochMilli() ?: 0L else 0L
        db.transaction { it.exec("UPDATE agents SET enabled = ?, next_at = ?, paused = 0, failures = 0 WHERE id = ?", if (on) 1 else 0, next, id) }
    }

    /** Agents that should start now: switched on, not paused, with a planned time that has come (or passed while the phone was off). */
    fun due(at: Long = now()): List<SavedAgent> = list().filter { it.enabled && !it.state.paused && it.nextAt in 1..at }

    /** Records how a run ended and plans the next start. A paused agent gets no next start. */
    fun finished(id: String, run: AgentRun, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): SavedAgent? {
        val a = get(id) ?: return null
        val state = a.state.after(run.outcome)
        val t = now()
        val next = if (state.paused || !a.enabled) 0L else Agents.nextRun(a.def.trigger, java.time.Instant.ofEpochMilli(t), zone)?.toEpochMilli() ?: 0L
        db.transaction {
            it.exec(
                "UPDATE agents SET failures = ?, paused = ?, next_at = ?, last_at = ?, last_outcome = ?, last_log = ? WHERE id = ?",
                state.failuresInRow, if (state.paused) 1 else 0, next, t, run.outcome.name, run.lines.joinToString("\n").take(2000), id,
            )
        }
        return get(id)
    }

    companion object { const val MAX_AGENTS = 30 }
}
