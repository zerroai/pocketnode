package com.pocketnode.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Plain text for a crash report. It names the kinds of error and where in the code they happened, and nothing else: error
 * messages are left out on purpose, because they can quote what the owner typed or what a website sent. Nothing is sent
 * anywhere; the owner can read the report and choose to share it.
 */
object CrashReport {
    private const val MAX_FRAMES = 40
    private const val MAX_CAUSES = 4

    fun format(t: Throwable, threadName: String, appVersion: String, androidVersion: String, phone: String, nowMillis: Long): String {
        val sb = StringBuilder()
        sb.append("Pocketnode stopped unexpectedly\n")
        sb.append("When: ").append(stamp(nowMillis)).append(" UTC\n")
        sb.append("App version: ").append(appVersion).append('\n')
        sb.append("Android: ").append(androidVersion).append('\n')
        sb.append("Phone: ").append(phone).append('\n')
        sb.append("Thread: ").append(threadName.take(60)).append('\n')
        sb.append("This report has no messages, chats, files or keys in it. Error messages are left out because they can quote your text.\n\n")
        var cur: Throwable? = t
        var n = 0
        while (cur != null && n < MAX_CAUSES) {
            sb.append(if (n == 0) "Error: " else "Caused by: ").append(cur.javaClass.name).append('\n')
            for (f in cur.stackTrace.take(MAX_FRAMES)) {
                sb.append("  at ").append(f.className).append('.').append(f.methodName)
                sb.append('(').append(f.fileName ?: "unknown").append(if (f.lineNumber >= 0) ":${f.lineNumber}" else "").append(")\n")
            }
            if (cur.stackTrace.size > MAX_FRAMES) sb.append("  ... ").append(cur.stackTrace.size - MAX_FRAMES).append(" more\n")
            cur = cur.cause?.takeIf { it !== cur }
            n++
        }
        return sb.toString()
    }

    private fun stamp(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))
}
