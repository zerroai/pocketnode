package com.pocketnode.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsToolsTest {
    private class Host(var look: String = "system", var limit: Long = 0, var brief: BriefingSettings = BriefingSettings(), var answer: String? = null) : SettingsHost {
        var asked = 0
        override fun appearance() = look
        override fun setAppearance(mode: String) { look = mode }
        override fun usageLimit() = limit
        override fun setUsageLimit(tokens: Long) { limit = tokens }
        override fun briefing() = brief
        override fun setBriefing(s: BriefingSettings) { brief = s }
        override fun ask(title: String, message: String): String? { asked++; return answer }
    }
    private fun call(h: Host, n: String, a: String = "{}") = SettingsTools.all(h).first { it.spec.name == n }.call(a)

    @Test fun `appearance changes without asking and reads back`() {
        val h = Host()
        assertTrue(call(h, "settings.appearance").contains("system"))
        assertTrue(call(h, "settings.appearance", """{"mode":"Dark"}""").contains("dark"))
        assertEquals("dark", h.look); assertEquals(0, h.asked)
        assertTrue(call(h, "settings.appearance", """{"mode":"pink"}""").startsWith("Error"))
    }

    @Test fun `limit is parsed and the owner must agree`() {
        assertEquals(300_000L, SettingsTools.parseLimit("300k")); assertEquals(1_000_000L, SettingsTools.parseLimit("1 million")); assertEquals(0L, SettingsTools.parseLimit("no limit"))
        val h = Host(answer = "The owner said no.")
        assertTrue(call(h, "settings.usage_limit", """{"limit":"300k"}""").startsWith("Error"))
        assertEquals(0L, h.limit)
        h.answer = null
        assertTrue(call(h, "settings.usage_limit", """{"limit":"300 thousand"}""").contains("300 thousand"))
        assertEquals(300_000L, h.limit)
        assertTrue(call(h, "settings.usage_limit", """{"limit":"123"}""").startsWith("Error"))   // not one of the choices
        assertTrue(call(h, "settings.usage_limit", """{"limit":"300k"}""").contains("already"))
    }

    @Test fun `briefing time and switch`() {
        val h = Host()
        assertTrue(call(h, "settings.briefing").contains("off"))
        assertTrue(call(h, "settings.briefing", """{"at":"6:45"}""").contains("on at 6:45"))   // setting a time switches it on
        assertEquals(BriefingSettings(true, 6, 45), h.brief)
        call(h, "settings.briefing", """{"on":false}""")
        assertEquals(false, h.brief.enabled); assertEquals(6, h.brief.hour)
        h.answer = "No."
        assertTrue(call(h, "settings.briefing", """{"on":true}""").startsWith("Error"))
        assertEquals(false, h.brief.enabled)
        assertTrue(call(h, "settings.briefing", """{"at":"banana"}""").startsWith("Error"))
    }
}
