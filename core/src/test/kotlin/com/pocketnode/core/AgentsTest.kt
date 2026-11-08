package com.pocketnode.core

import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentsTest {
    private val tools = setOf("weather.forecast", "notes.add")
    private fun agent(vararg s: AgentStep, trigger: AgentTrigger = AgentTrigger("daily", 7, 30)) = AgentDef("a1", "Rain watch", trigger, s.toList())
    private val forecast = AgentStep("tool", tool = "weather.forecast", args = """{"city":"Accra"}""", save = "wx")
    private val ask = AgentStep("ask", text = "Will it rain? {wx}", save = "verdict")
    private val check = AgentStep("check", from = "verdict", op = "contains", text = "yes")
    private val tell = AgentStep("notify", tool = "Rain", text = "{verdict}")

    @Test fun goodAgentValidates() = assertEquals(emptyList(), Agents.validate(agent(forecast, ask, check, tell), tools))

    @Test fun problemsAreFound() {
        fun p(a: AgentDef) = Agents.validate(a, tools).joinToString("|")
        assertTrue(p(agent(ask)).contains("{wx}"))
        assertTrue(p(agent(forecast.copy(tool = "phone.call"))).contains("not available"))
        assertTrue(p(agent(check)).contains("checks a result called verdict"))
        assertTrue(p(agent(check.copy(from = ""))).contains("nothing to check yet"))
        assertTrue(p(agent(forecast, forecast)).contains("Two steps save"))
        assertTrue(p(agent(forecast, trigger = AgentTrigger("every", everyMinutes = 5))).contains("every 15"))
        assertTrue(p(agent(forecast, trigger = AgentTrigger("daily", hour = 25))).contains("time of day"))
        assertTrue(p(AgentDef("a", "", steps = listOf(forecast))).contains("name"))
        assertTrue(p(agent()).contains("no steps"))
        assertTrue(p(agent(forecast.copy(save = "Bad Name"))).contains("not allowed"))
        assertTrue(p(agent(AgentStep("dance"))).contains("unknown type"))
    }

    private fun runner(toolAnswer: String = "rain tomorrow", model: String = "yes", sent: MutableList<String> = mutableListOf(), asked: MutableList<String> = mutableListOf()) =
        AgentRunner({ _, _ -> toolAnswer }, { asked += it; model }, { t, x -> sent += "$t: $x" })

    @Test fun runsStepsInOrderAndFillsNames() {
        val sent = mutableListOf<String>(); val asked = mutableListOf<String>()
        val r = runner(sent = sent, asked = asked).run(agent(forecast, ask, check, tell))
        assertEquals(AgentOutcome.DONE, r.outcome)
        assertEquals(listOf("Will it rain? rain tomorrow"), asked)
        assertEquals(listOf("Rain: yes"), sent)
    }

    @Test fun failedCheckStopsQuietly() {
        val sent = mutableListOf<String>()
        val r = runner(model = "no", sent = sent).run(agent(forecast, ask, check, tell))
        assertEquals(AgentOutcome.STOPPED_BY_CHECK, r.outcome)
        assertTrue(sent.isEmpty())
    }

    @Test fun toolErrorFailsTheRun() {
        val r = runner(toolAnswer = "Denied: not allowed").run(agent(forecast, ask))
        assertEquals(AgentOutcome.FAILED, r.outcome)
        assertTrue(r.lines.last().contains("Denied"))
    }

    @Test fun thrownErrorFailsTheRun() {
        val r = AgentRunner({ _, _ -> error("boom") }, { "" }, { _, _ -> }).run(agent(forecast))
        assertEquals(AgentOutcome.FAILED, r.outcome)
        assertTrue(r.lines.single().contains("boom"))
    }

    @Test fun threeFailuresPause() {
        var s = AgentState()
        repeat(2) { s = s.after(AgentOutcome.FAILED) }
        assertEquals(false, s.paused)
        s = s.after(AgentOutcome.DONE); assertEquals(0, s.failuresInRow)
        repeat(3) { s = s.after(AgentOutcome.FAILED) }
        assertTrue(s.paused)
        assertTrue(s.after(AgentOutcome.DONE).paused)
    }

    @Test fun nextRunDailyAndEvery() {
        val z = ZoneId.of("Africa/Accra")
        val at = Instant.parse("2026-10-23T08:00:00Z") // a Friday, after 07:30
        assertEquals(Instant.parse("2026-10-24T07:30:00Z"), Agents.nextRun(AgentTrigger("daily", 7, 30), at, z))
        assertEquals(Instant.parse("2026-10-26T07:30:00Z"), Agents.nextRun(AgentTrigger("daily", 7, 30, weekdaysOnly = true), at, z))
        assertEquals(Instant.parse("2026-10-23T07:30:00Z").plusSeconds(86400 * 0), Agents.nextRun(AgentTrigger("daily", 7, 30), Instant.parse("2026-10-23T07:00:00Z"), z))
        assertEquals(at.plusSeconds(1800), Agents.nextRun(AgentTrigger("every", everyMinutes = 30), at, z))
        assertNull(Agents.nextRun(AgentTrigger(), at, z))
    }

    // A flow: forecast -> ask -> check; yes goes to "umbrella", no goes to "sun".
    private fun flow(vararg s: AgentStep) = AgentDef("f1", "Flow", AgentTrigger(), s.toList())
    private val f1 = forecast.copy(id = "a", next = "b")
    private val f2 = ask.copy(id = "b", next = "c")
    private val f3 = check.copy(id = "c", next = "d", no = "e")
    private val f4 = tell.copy(id = "d", text = "Take an umbrella")
    private val f5 = tell.copy(id = "e", text = "No rain")

    @Test fun flowValidatesAndTakesTheYesWay() {
        val a = flow(f1, f2, f3, f4, f5)
        assertEquals(emptyList(), Agents.validate(a, tools))
        val sent = mutableListOf<String>()
        assertEquals(AgentOutcome.DONE, runner(model = "yes", sent = sent).run(a).outcome)
        assertEquals(listOf("Rain: Take an umbrella"), sent)
    }

    @Test fun flowTakesTheNoWay() {
        val sent = mutableListOf<String>()
        val r = runner(model = "no", sent = sent).run(flow(f1, f2, f3, f4, f5))
        assertEquals(AgentOutcome.DONE, r.outcome)
        assertEquals(listOf("Rain: No rain"), sent)
    }

    @Test fun flowWithoutNoWayStopsQuietly() {
        val sent = mutableListOf<String>()
        val r = runner(model = "no", sent = sent).run(flow(f1, f2, f3.copy(no = ""), f4))
        assertEquals(AgentOutcome.STOPPED_BY_CHECK, r.outcome)
        assertTrue(sent.isEmpty())
    }

    @Test fun flowProblemsAreFound() {
        fun p(vararg s: AgentStep) = Agents.validate(flow(*s), tools).joinToString("|")
        assertTrue(p(f1, f2, f3, f4).contains("does not exist"))
        assertTrue(p(f1, f2, f3, f4, f5.copy(id = "z")).let { it.contains("does not exist") || it.contains("not joined") })
        assertTrue(p(f1.copy(next = "b"), f2, f3.copy(no = ""), f4, f5).contains("Step 5 is not joined"))
        assertTrue(p(f1, f2, f3.copy(no = "a"), f4, f5).contains("loop"))
        assertTrue(p(f1, f2.copy(no = "d"), f3, f4, f5).contains("second way out"))
        assertTrue(p(f1, f2, f3, f4, f5.copy(id = "d")).contains("share the id"))
        // A name saved only on the other branch is not available.
        assertTrue(p(f1, f2, f3, f4.copy(text = "{nothere}"), f5).contains("{nothere}"))
    }

    @Test fun planAgentsStillRunInOrderWithoutIds() {
        assertTrue(!Agents.isFlow(agent(forecast, ask)))
    }
}
