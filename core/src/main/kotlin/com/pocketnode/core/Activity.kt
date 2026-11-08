package com.pocketnode.core

/** What kind of thing happened, for the owner's Activity screen. */
enum class ActivityKind(val label: String) {
    TOOL("Tools"), NETWORK("Network"), MODEL("Model"), ASKED("Asked you"), SYSTEM("System");

    companion object { fun of(name: String): ActivityKind = entries.firstOrNull { it.name == name } ?: SYSTEM }
}

data class ActivityEntry(val id: Long, val at: Long, val kind: ActivityKind, val subject: String, val detail: String, val problem: Boolean, val times: Int = 1)

/**
 * A plain record, kept only on this phone, of what the assistant did: each tool it used, each request that left the phone (to the model
 * provider, or by a pack to a website), each time it asked the owner, and changes to what it may do. It is the owner's way to check
 * the assistant. Nothing here is sent anywhere.
 */
class ActivityLog(private val db: SqlDb, private val now: () -> Long = System::currentTimeMillis) {
    init {
        db.transaction { tx ->
            tx.exec("CREATE TABLE IF NOT EXISTS activity (id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, kind TEXT NOT NULL, subject TEXT NOT NULL, detail TEXT NOT NULL, problem INTEGER NOT NULL DEFAULT 0)")
            tx.exec("CREATE INDEX IF NOT EXISTS activity_at ON activity (at)")
        }
        // An older copy of the table has no repeat count; add the column once.
        runCatching { db.transaction { it.exec("ALTER TABLE activity ADD COLUMN times INTEGER NOT NULL DEFAULT 1") } }
    }

    /** Records one event. Never throws: a logging problem must not stop the assistant. */
    fun add(kind: ActivityKind, subject: String, detail: String = "", problem: Boolean = false) {
        runCatching {
            db.transaction { tx ->
                // The same thing happening again straight away (a pack polling a service) is one row with a count, not a flood.
                if (kind == ActivityKind.NETWORK) {
                    val recent = tx.query("SELECT id, kind, subject, detail FROM activity ORDER BY id DESC LIMIT 6") { Triple(it.long(0), it.string(1) + "|" + it.string(2), it.string(3)) }
                    val same = recent.firstOrNull { it.second == "${kind.name}|${subject.take(MAX_SUBJECT)}" && it.third == detail.take(MAX_DETAIL) }
                    if (same != null) {
                        tx.exec("UPDATE activity SET at = ?, times = times + 1 WHERE id = ?", now(), same.first)
                        return@transaction
                    }
                }
                tx.exec("INSERT INTO activity (at, kind, subject, detail, problem) VALUES (?, ?, ?, ?, ?)", now(), kind.name, subject.take(MAX_SUBJECT), detail.take(MAX_DETAIL), if (problem) 1L else 0L)
                val count = tx.query("SELECT COUNT(*) FROM activity") { it.long(0) }.first()
                if (count > KEEP + SLACK) tx.exec("DELETE FROM activity WHERE id <= (SELECT id FROM activity ORDER BY id DESC LIMIT 1 OFFSET ?)", KEEP.toLong())
            }
        }
    }

    /**
     * Newest first. [kind] narrows it to one kind, [problemsOnly] to what failed or was refused, and [query] to entries whose
     * subject or details contain those words (ignoring case; % and _ are plain characters).
     */
    fun recent(limit: Int = 200, kind: ActivityKind? = null, query: String = "", problemsOnly: Boolean = false): List<ActivityEntry> = db.transaction { tx ->
        val rows = { r: Row -> ActivityEntry(r.long(0), r.long(1), ActivityKind.of(r.string(2)), r.string(3), r.string(4), r.long(5) != 0L, r.long(6).toInt()) }
        val where = mutableListOf<String>(); val args = mutableListOf<Any?>()
        if (kind != null) { where += "kind = ?"; args += kind.name }
        if (problemsOnly) where += "problem != 0"
        val q = query.trim()
        if (q.isNotEmpty()) {
            val like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            where += "(subject LIKE ? ESCAPE '\\' OR detail LIKE ? ESCAPE '\\')"; args += like; args += like
        }
        args += limit.toLong()
        tx.query("SELECT id, at, kind, subject, detail, problem, times FROM activity" + (if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")) + " ORDER BY at DESC, id DESC LIMIT ?", *args.toTypedArray(), map = rows)
    }

    /** How many events of each kind since [since]. */
    fun counts(since: Long): Map<ActivityKind, Int> = db.transaction { tx ->
        tx.query("SELECT kind, SUM(times) FROM activity WHERE at >= ? GROUP BY kind", since) { ActivityKind.of(it.string(0)) to it.long(1).toInt() }.toMap()
    }

    fun clear() { db.transaction { it.exec("DELETE FROM activity") } }

    companion object {
        const val KEEP = 2_000
        private const val SLACK = 200
        private const val MAX_SUBJECT = 120
        private const val MAX_DETAIL = 400
    }
}

/**
 * Lets the owner stop the assistant. [check] is asked before every step of a run and returns a reason to stop, or null to carry on.
 * A step already underway (a request to the model, a tool in the middle of its work) finishes; nothing new starts.
 */
fun interface RunGuard {
    fun check(runId: String): String?

    companion object { val None = RunGuard { null } }
}
