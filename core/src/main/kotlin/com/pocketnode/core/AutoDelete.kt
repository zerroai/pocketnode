package com.pocketnode.core

/**
 * "Delete old chats": when the owner picks a number of days, chats that have not been used for that long are deleted, except
 * pinned ones. The choice is kept with the other core settings, so Erase my data leaves it alone.
 */
class AutoDelete(private val kv: PackKv) {
    var days: Int
        get() = kv.get(NS, KEY)?.toIntOrNull()?.takeIf { it in CHOICES } ?: 0
        set(value) { kv.set(NS, KEY, (if (value in CHOICES) value else 0).toString()) }

    /** The chats that [days] would delete at [nowMillis]. Pinned chats are never listed. */
    fun expired(sessions: List<SessionSummary>, nowMillis: Long, days: Int = this.days): List<SessionSummary> {
        if (days <= 0) return emptyList()
        val cutoff = nowMillis - days * DAY
        return sessions.filter { !it.pinned && it.lastActivityMillis < cutoff }
    }

    companion object {
        private const val NS = "core"
        private const val KEY = "autodelete.days"
        private const val DAY = 24L * 60 * 60 * 1000
        val CHOICES = listOf(0, 7, 30, 90, 365)

        fun describe(days: Int): String = when (days) { 0 -> "never"; 7 -> "after a week"; 365 -> "after a year"; else -> "after $days days" }
    }
}
