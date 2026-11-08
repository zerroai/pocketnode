package com.pocketnode.core

import java.util.Calendar
import java.util.TimeZone

/** The owner's morning briefing: a short summary the assistant prepares at the same time each day. */
data class BriefingSettings(val enabled: Boolean = false, val hour: Int = 7, val minute: Int = 30, val place: String = "") {
    fun timeText(): String = "%d:%02d".format(hour, minute)
}

object Briefing {
    /** The next moment it is [hour]:[minute], today if that is still ahead of [now], otherwise tomorrow. */
    fun nextAt(hour: Int, minute: Int, now: Long, tz: TimeZone = TimeZone.getDefault()): Long {
        require(hour in 0..23 && minute in 0..59) { "That is not a time of day." }
        val c = Calendar.getInstance(tz).apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        if (c.timeInMillis <= now) c.add(Calendar.DAY_OF_MONTH, 1)
        return c.timeInMillis
    }

    /**
     * What the assistant is asked. It names only tools that work with no one at the screen: asking to read the calendar, contacts or
     * email would be refused in the background, and those stay for when the owner asks in person.
     */
    fun prompt(place: String): String {
        val where = place.trim().replace(Regex("\\s+"), " ").take(80)
        return "Give me my morning briefing. In at most five short plain lines: a friendly greeting with today's date, " +
            (if (where.isNotEmpty()) "the weather in $where now and for today, " else "") +
            "and the reminders that are waiting for today. Use only the weather and reminder tools, and only when they are available. " +
            "Do not use the calendar, contacts, photos, camera or email. If something is not available, leave it out without apologising."
    }
}
