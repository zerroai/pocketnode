package com.pocketnode.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The Activity record as plain text the owner can keep or send, oldest first so it reads like a diary. */
object ActivityText {
    fun render(entries: List<ActivityEntry>, nowMillis: Long): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val sb = StringBuilder("Pocketnode activity record, saved ${stamp.format(Date(nowMillis))}\n")
        sb.append("${entries.size} ${if (entries.size == 1) "entry" else "entries"}. T tool, N network request from a pack, M model request, ? asked the owner, S system. ! means it failed or was refused.\n\n")
        for (e in entries.sortedBy { it.at }) {
            val mark = if (e.problem) "!" else when (e.kind) { ActivityKind.TOOL -> "T"; ActivityKind.NETWORK -> "N"; ActivityKind.MODEL -> "M"; ActivityKind.ASKED -> "?"; ActivityKind.SYSTEM -> "S" }
            sb.append(stamp.format(Date(e.at))).append("  ").append(mark).append("  ").append(e.subject)
            if (e.times > 1) sb.append("  x").append(e.times)
            if (e.detail.isNotBlank()) sb.append("\n    ").append(e.detail.replace(Regex("\\s*\\n\\s*"), " "))
            sb.append('\n')
        }
        return sb.toString()
    }
}
