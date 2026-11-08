package com.pocketnode.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StatusToolsTest {
    private val host = object : StatusHost {
        var lastLimit = 0
        override fun activity(limit: Int, about: String, problemsOnly: Boolean): List<ActivityEntry> { lastLimit = limit; return if (about == "none") emptyList() else listOf(ActivityEntry(1, 0, ActivityKind.TOOL, "weather.now", "Accra", true)) }
        override fun status() = listOf("Assistant" to "on", "Used today" to "3k of 50k")
        override fun devices() = listOf("Desktop")
    }
    private fun call(n: String, a: String = "{}") = StatusTools.all(host).first { it.spec.name == n }.call(a)

    @Test fun activityListsAndMarksProblems() {
        val r = call("activity.recent", """{"limit":500}""")
        assertTrue(r.contains("weather.now") && r.contains("[problem]"))
        assertEquals(30, host.lastLimit)
        assertEquals("Nothing matches.", call("activity.recent", """{"about":"none"}"""))
    }

    @Test fun statusAndDevices() {
        assertEquals("Assistant: on\nUsed today: 3k of 50k", call("assistant.status"))
        assertEquals("Desktop", call("device.list"))
    }
}
