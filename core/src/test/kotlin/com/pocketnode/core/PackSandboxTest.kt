package com.pocketnode.core

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PackSandboxTest {
    private val sandboxes = mutableListOf<PackSandbox>()
    private var server: HttpServer? = null

    @AfterTest fun cleanup() { sandboxes.forEach { it.close() }; server?.stop(0) }

    private val requested = mutableListOf<String>()
    private val fakeNet = HostNet { url, method, _, body ->
        requested += "$method $url ${body.orEmpty()}"
        HostResponse(200, """{"ok":true,"url":"$url"}""")
    }

    private fun sandbox(
        script: String,
        declared: Set<Permission> = setOf(Permission("net", "api.telegram.org")),
        grant: Boolean = true,
        limits: PackLimits = PackLimits(),
    ): PackSandbox {
        val broker = PermissionBroker()
        if (grant) broker.grantDefaults("p", declared)
        return PackSandbox("p", declared, broker, fakeNet, script, limits).also { sandboxes += it }
    }

    @Test
    fun `runs an async tool and returns its result as JSON`() {
        val s = sandbox("pack = { tools: { 'math.add': async (a) => ({ sum: a.x + a.y }) } };")
        assertEquals("""{"sum":5}""", s.callTool("math.add", """{"x":2,"y":3}"""))
    }

    @Test
    fun `fetch to a declared and granted https host goes through the host network`() {
        val s = sandbox("pack = { tools: { go: async () => { const r = await fetch('https://api.telegram.org/bot1/getMe', { method: 'POST', body: 'hi' }); return await r.text(); } } };")
        val out = s.callTool("go", "{}")
        assertTrue(out.contains("api.telegram.org"))
        assertEquals(listOf("POST https://api.telegram.org/bot1/getMe hi"), requested)
    }

    @Test
    fun `fetch to an undeclared host is refused and never reaches the network`() {
        val s = sandbox("pack = { tools: { go: async () => (await fetch('https://evil.example/x')).text() } };")
        val e = assertFailsWith<Exception> { s.callTool("go", "{}") }
        assertTrue(e.message!!.contains("did not declare"), e.message)
        assertTrue(requested.isEmpty())
    }

    @Test
    fun `fetch to a declared host the user has not granted is refused`() {
        val s = sandbox("pack = { tools: { go: async () => (await fetch('https://api.telegram.org/x')).text() } };", grant = false)
        val e = assertFailsWith<Exception> { s.callTool("go", "{}") }
        assertTrue(e.message!!.contains("not granted"), e.message)
        assertTrue(requested.isEmpty())
    }

    @Test
    fun `plain http is refused even for an allowed host`() {
        val s = sandbox("pack = { tools: { go: async () => (await fetch('http://api.telegram.org/x')).text() } };")
        val e = assertFailsWith<Exception> { s.callTool("go", "{}") }
        assertTrue(e.message!!.contains("https"), e.message)
        assertTrue(requested.isEmpty())
    }

    @Test
    fun `a pack has no ambient access to the host`() {
        val s = sandbox("pack = { tools: { probe: async () => [typeof process, typeof require, typeof std, typeof os, typeof __hostFetch, typeof __hostSecret, typeof java, typeof Deno].join(',') } };")
        assertEquals("undefined,undefined,undefined,undefined,undefined,undefined,undefined,undefined", s.callTool("probe", "{}"))
    }

    @Test
    fun `an infinite loop is interrupted by the call timeout`() {
        val s = sandbox("pack = { tools: { spin: async () => { while (true) {} } } };", limits = PackLimits(callTimeoutMs = 300))
        val started = System.nanoTime()
        assertFailsWith<Exception> { s.callTool("spin", "{}") }
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000)
    }

    @Test
    fun `a pack that allocates without bound hits the memory limit`() {
        val s = sandbox(
            "pack = { tools: { hog: async () => { const a = []; while (true) a.push(new Array(10000).fill(1)); } } };",
            limits = PackLimits(memoryBytes = 8L * 1024 * 1024, callTimeoutMs = 5_000),
        )
        assertFailsWith<Exception> { s.callTool("hog", "{}") }
    }

    @Test
    fun `the sandbox is still usable after a failed call`() {
        val s = sandbox("pack = { tools: { bad: async () => { throw new Error('boom'); }, ok: async () => 'fine' } };")
        assertFailsWith<Exception> { s.callTool("bad", "{}") }
        assertEquals("fine", s.callTool("ok", "{}"))
    }

    @Test
    fun `an unknown tool is an error`() {
        val s = sandbox("pack = { tools: {} };")
        assertFailsWith<Exception> { s.callTool("nope", "{}") }
    }

    @Test
    fun `a pack tool runs through the agent runtime like any other tool`() {
        val s = sandbox("pack = { tools: { 'text.upper': async (a) => a.s.toUpperCase() } };")
        val tool = PackTool(s, ToolSpec("text.upper", "Uppercase text", "{}", idempotent = true))
        val replies = mutableListOf(ModelReply("", listOf(ToolCall("c1", "text.upper", """{"s":"abc"}"""))), ModelReply("done"))
        var seenResult: String? = null
        val model = ModelClient { msgs, _ ->
            seenResult = msgs.filterIsInstance<Msg.ToolResult>().lastOrNull()?.text
            replies.removeAt(0)
        }
        val rt = AgentRuntime(InMemoryRunStore(), model, listOf(RegisteredTool(tool, "p", emptySet())), PermissionBroker(), Budget.TierA)
        assertEquals("done", rt.run("s", "go").text)
        assertEquals("ABC", seenResult)
    }

    @Test
    fun `the real network adapter does not follow redirects`() {
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        srv.createContext("/r") { ex -> ex.responseHeaders.add("Location", "http://127.0.0.1:1/elsewhere"); ex.sendResponseHeaders(302, -1); ex.close() }
        srv.start(); server = srv
        val r = HttpHostNet().fetch("http://127.0.0.1:${srv.address.port}/r", "GET", emptyMap(), null)
        assertEquals(302, r.status)
    }
}
