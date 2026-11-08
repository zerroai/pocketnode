package com.pocketnode.core

import java.util.Calendar
import java.util.TimeZone

/** A reminder that will show as a notification at [at] (milliseconds since 1970). [repeat] is null, "daily", "weekly" or "weekdays". */
data class Reminder(val id: Long, val text: String, val at: Long, val repeat: String?, val createdAt: Long)

/** When a repeating reminder next fires. Works in the phone's own time zone, so it stays at the same clock time across daylight saving changes. */
object Recurrence {
    val KINDS = setOf("daily", "weekly", "weekdays")

    /** The first occurrence after [now], or null if the reminder does not repeat. */
    /** A weekday reminder never starts on a weekend: a Saturday or Sunday start moves to the Monday. */
    fun firstOccurrence(at: Long, repeat: String?, tz: TimeZone = TimeZone.getDefault()): Long {
        if (repeat != "weekdays") return at
        val c = Calendar.getInstance(tz).apply { timeInMillis = at }
        while (c.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY || c.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) c.add(Calendar.DAY_OF_MONTH, 1)
        return c.timeInMillis
    }

    /** The next moment it is [clock] ("9:00" or "09:00") after [now], skipping weekends for a weekday repeat. */
    fun nextAtClock(clock: String, repeat: String?, now: Long, tz: TimeZone = TimeZone.getDefault()): Long {
        val parts = clock.trim().split(':')
        val h = parts.getOrNull(0)?.toIntOrNull(); val m = parts.getOrNull(1)?.toIntOrNull()
        require(parts.size == 2 && h != null && m != null && h in 0..23 && m in 0..59) { "A time looks like 09:00." }
        val c = Calendar.getInstance(tz).apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        if (c.timeInMillis <= now) c.add(Calendar.DAY_OF_MONTH, 1)
        return firstOccurrence(c.timeInMillis, repeat, tz)
    }

    fun next(at: Long, repeat: String?, now: Long, tz: TimeZone = TimeZone.getDefault()): Long? {
        if (repeat == null || repeat !in KINDS) return null
        val c = Calendar.getInstance(tz).apply { timeInMillis = at }
        var guard = 0
        do {
            when (repeat) {
                "weekly" -> c.add(Calendar.DAY_OF_MONTH, 7)
                else -> {
                    c.add(Calendar.DAY_OF_MONTH, 1)
                    if (repeat == "weekdays") while (c.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY || c.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) c.add(Calendar.DAY_OF_MONTH, 1)
                }
            }
        } while (c.timeInMillis <= now && ++guard < 6_000)
        return c.timeInMillis
    }
}

/**
 * The owner's reminders, kept in the app's database. The store only remembers and does the date arithmetic; waking the phone
 * at the right moment is the platform's job (on Android, the alarm manager), and it asks the store what is due.
 */
class ReminderStore(private val db: SqlDb, private val now: () -> Long = System::currentTimeMillis) {
    init {
        db.transaction { tx ->
            tx.exec("CREATE TABLE IF NOT EXISTS reminders (id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT NOT NULL, at INTEGER NOT NULL, repeat TEXT NOT NULL DEFAULT '', created INTEGER NOT NULL)")
        }
    }

    /** Saves a reminder. Throws [IllegalArgumentException] with a message fit to show the owner if it cannot be set. */
    fun add(text: String, at: Long, repeat: String? = null): Reminder {
        val clean = text.trim().replace(Regex("\\s+"), " ")
        require(clean.isNotEmpty()) { "Say what the reminder is for." }
        require(clean.length <= MAX_TEXT) { "A reminder can be up to $MAX_TEXT characters." }
        require(repeat == null || repeat in Recurrence.KINDS) { "A reminder can repeat daily, weekly or on weekdays." }
        val t = now()
        val at = Recurrence.firstOccurrence(at, repeat)
        require(at >= t - 60_000) { "That time has already passed." }
        require(at <= t + MAX_AHEAD_MS) { "A reminder can be set up to a year ahead." }
        return db.transaction { tx ->
            val count = tx.query("SELECT COUNT(*) FROM reminders") { it.long(0) }.first()
            require(count < MAX_PENDING) { "There are already $MAX_PENDING reminders. Cancel some first." }
            tx.exec("INSERT INTO reminders (text, at, repeat, created) VALUES (?, ?, ?, ?)", clean, at, repeat.orEmpty(), t)
            val id = tx.query("SELECT last_insert_rowid()") { it.long(0) }.first()
            Reminder(id, clean, at, repeat, t)
        }
    }

    private fun read(r: Row) = Reminder(r.long(0), r.string(1), r.long(2), r.string(3).ifEmpty { null }, r.long(4))
    private val cols = "id, text, at, repeat, created"

    fun list(): List<Reminder> = db.transaction { it.query("SELECT $cols FROM reminders ORDER BY at, id") { r -> read(r) } }

    fun get(id: Long): Reminder? = db.transaction { it.query("SELECT $cols FROM reminders WHERE id = ?", id) { r -> read(r) }.firstOrNull() }

    fun cancel(id: Long): Boolean = db.transaction { tx ->
        val had = tx.query("SELECT 1 FROM reminders WHERE id = ?", id) { 1 }.isNotEmpty()
        tx.exec("DELETE FROM reminders WHERE id = ?", id)
        had
    }

    /** Reminders whose time has come (or passed while the phone was off). */
    fun due(at: Long = now()): List<Reminder> = db.transaction { it.query("SELECT $cols FROM reminders WHERE at <= ? ORDER BY at, id", at) { r -> read(r) } }

    /**
     * Records that a reminder was shown. A repeating one moves to its next time and is returned so it can be scheduled again;
     * a one-off one is removed and null is returned.
     */
    fun fired(id: Long, at: Long = now()): Reminder? = db.transaction { tx ->
        val r = tx.query("SELECT $cols FROM reminders WHERE id = ?", id) { row -> read(row) }.firstOrNull() ?: return@transaction null
        val next = Recurrence.next(r.at, r.repeat, at)
        if (next == null) { tx.exec("DELETE FROM reminders WHERE id = ?", id); null }
        else { tx.exec("UPDATE reminders SET at = ? WHERE id = ?", next, id); r.copy(at = next) }
    }

    companion object {
        const val MAX_TEXT = 200
        const val MAX_PENDING = 100
        const val MAX_AHEAD_MS = 366L * 24 * 60 * 60 * 1000
    }
}
