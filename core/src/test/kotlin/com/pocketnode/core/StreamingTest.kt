package com.pocketnode.core

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamingTest {
    private var server: HttpServer? = null
    @AfterTest fun stop() { server?.stop(0) }

    private fun feed(reader: StreamReader, vararg lines: String) { lines.forEach(reader::line) }

    // ---- readers, with canned lines ------------------------------------------------------

    @Test fun `OpenAI text arrives piece by piece and the whole text grows`() {
        val seen = mutableListOf<String>()
        val r = StreamReader.OpenAi { seen += it }
        feed(r,
            ": keep-alive comment",
            """data: {"choices":[{"delta":{"role":"assistant","content":""}}]}""",
            """data: {"choices":[{"delta":{"content":"Hel"}}]}""",
            "",
            """data: {"choices":[{"delta":{"content":"lo"}}]}""",
            """data: {"choices":[{"delta":{"content":null},"finish_reason":"stop"}]}""",
            "data: [DONE]",
        )
        assertEquals(listOf("Hel", "Hello"), seen)
        assertEquals(ModelReply("Hello", emptyList()), r.reply())
        assertTrue(r.sawData)
    }

    @Test fun `OpenAI tool calls are put together from pieces, in order`() {
        val r = StreamReader.OpenAi { }
        feed(r,
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_a","type":"function","function":{"name":"clock__now","arguments":""}}]}}]}""",
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"tz\":"}}]}}]}""",
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"UTC\"}"}},{"index":1,"id":"call_b","function":{"name":"math__calc","arguments":"{}"}}]}}]}""",
            "data: [DONE]",
        )
        assertEquals(
            listOf(ToolCall("call_a", "clock.now", """{"tz":"UTC"}"""), ToolCall("call_b", "math.calc", "{}")),
            r.reply().calls,
        )
    }

    @Test fun `OpenAI junk lines are skipped and an error in the stream is raised in the server's words`() {
        val r = StreamReader.OpenAi { }
        feed(r, "data: not json at all", """data: {"choices":[]}""")
        assertEquals("", r.reply().text)
        val e = assertFailsWith<IllegalStateException> { r.line("""data: {"error":{"message":"rate limit hit","type":"x"}}""") }
        assertEquals("rate limit hit", e.message)
    }

    @Test fun `a server that ignored the request to stream leaves its answer in other`() {
        val r = StreamReader.OpenAi { }
        r.line("""{"choices":[{"message":{"content":"plain"}}]}""")
        assertFalse(r.sawData)
        assertTrue("plain" in r.other)
    }

    @Test fun `Anthropic text and tool use arrive in pieces`() {
        val seen = mutableListOf<String>()
        val r = StreamReader.Anthropic { seen += it }
        feed(r,
            "event: message_start", """data: {"type":"message_start","message":{"id":"m"}}""",
            """data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Let me"}}""",
            """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":" check."}}""",
            """data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"tu_1","name":"weather__now","input":{}}}""",
            """data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"place\":"}}""",
            """data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"\"Lagos\"}"}}""",
            """data: {"type":"message_stop"}""",
        )
        assertEquals(listOf("Let me", "Let me check."), seen)
        assertEquals(ModelReply("Let me check.", listOf(ToolCall("tu_1", "weather.now", """{"place":"Lagos"}"""))), r.reply())
    }

    @Test fun `Anthropic tool use with no input gets empty arguments, and an error event is raised`() {
        val r = StreamReader.Anthropic { }
        feed(r, """data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"t","name":"clock__now"}}""")
        assertEquals("{}", r.reply().calls.single().args)
        assertFailsWith<IllegalStateException> { r.line("""data: {"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""") }
    }

    @Test fun `Ollama lines carry text and whole tool calls`() {
        val seen = mutableListOf<String>()
        val r = StreamReader.Ollama { seen += it }
        feed(r,
            """{"message":{"role":"assistant","content":"Hi"},"done":false}""",
            """{"message":{"role":"assistant","content":" there"},"done":false}""",
            """{"message":{"role":"assistant","content":"","tool_calls":[{"function":{"name":"clock__now","arguments":{"a":1}}}]},"done":true}""",
        )
        assertEquals(listOf("Hi", "Hi there"), seen)
        assertEquals("clock.now", r.reply().calls.single().name)
        assertEquals("""{"a":1}""", r.reply().calls.single().args)
    }

    // ---- over a real connection ---------------------------------------------------------

    private fun sse(vararg events: String) = events.joinToString("") { "data: $it\n\n" }

    private fun serve(handler: (com.sun.net.httpserver.HttpExchange, String) -> Unit): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/") { ex -> handler(ex, ex.requestBody.readBytes().decodeToString()) }
        s.start(); server = s
        return "http://127.0.0.1:${s.address.port}/v1"
    }

    private fun model(url: String) = OpenAiModel({ url }, { "k" }, { "m" })

    @Test fun `the first words reach the app before the server has finished`() {
        val firstSeen = CountDownLatch(1)
        var serverSawClientAhead = false
        val url = serve { ex, _ ->
            ex.responseHeaders.add("content-type", "text/event-stream")
            ex.sendResponseHeaders(200, 0) // chunked: the end is not known yet
            ex.responseBody.use { out ->
                out.write(sse("""{"choices":[{"delta":{"content":"First"}}]}""").toByteArray()); out.flush()
                // If the app only got the words at the end, this would time out.
                serverSawClientAhead = firstSeen.await(5, TimeUnit.SECONDS)
                out.write(sse("""{"choices":[{"delta":{"content":" and last"}}]}""", "[DONE]").toByteArray()); out.flush()
            }
        }
        val seen = mutableListOf<String>()
        val reply = model(url).stream(listOf(Msg.User("hi")), emptyList()) { seen += it; firstSeen.countDown() }
        assertTrue(serverSawClientAhead, "the first piece was shown while the reply was still being written")
        assertEquals(listOf("First", "First and last"), seen)
        assertEquals("First and last", reply.text)
    }

    @Test fun `the request asks for a stream`() {
        var body = ""
        val url = serve { ex, b ->
            body = b
            ex.sendResponseHeaders(200, 0); ex.responseBody.use { it.write(sse("""{"choices":[{"delta":{"content":"x"}}]}""", "[DONE]").toByteArray()) }
        }
        model(url).stream(listOf(Msg.User("hi")), emptyList()) { }
        assertTrue("\"stream\":true" in body.replace(" ", ""))
    }

    @Test fun `stopping from the callback ends the reply and closes the connection`() {
        val url = serve { ex, _ ->
            ex.sendResponseHeaders(200, 0)
            runCatching {
                ex.responseBody.use { out ->
                    repeat(200) { out.write(sse("""{"choices":[{"delta":{"content":"word "}}]}""").toByteArray()); out.flush(); Thread.sleep(20) }
                }
            }
        }
        var pieces = 0
        val started = System.currentTimeMillis()
        val e = assertFailsWith<StreamStopped> {
            model(url).stream(listOf(Msg.User("hi")), emptyList()) { pieces++; if (pieces == 3) throw StreamStopped("Stopped. Nothing more was done.") }
        }
        assertEquals("Stopped. Nothing more was done.", e.reason)
        assertEquals(3, pieces)
        assertTrue(System.currentTimeMillis() - started < 3_000, "it did not wait for the rest of the 4 second reply")
    }

    @Test fun `a server that refuses streaming is asked again the ordinary way, once`() {
        var calls = 0
        val url = serve { ex, b ->
            calls++
            if ("\"stream\":true" in b.replace(" ", "")) { ex.sendResponseHeaders(400, 0); ex.responseBody.use { it.write("""{"error":{"message":"stream not supported"}}""".toByteArray()) } }
            else { val r = """{"choices":[{"message":{"content":"plain answer"}}]}""".toByteArray(); ex.sendResponseHeaders(200, r.size.toLong()); ex.responseBody.use { it.write(r) } }
        }
        val seen = mutableListOf<String>()
        val reply = model(url).stream(listOf(Msg.User("hi")), emptyList()) { seen += it }
        assertEquals("plain answer", reply.text)
        assertEquals(listOf("plain answer"), seen)
        assertEquals(2, calls)
    }

    @Test fun `a real error such as a bad key is not retried and still reaches the owner`() {
        var calls = 0
        val url = serve { ex, _ -> calls++; ex.sendResponseHeaders(401, 0); ex.responseBody.use { it.write("""{"error":{"message":"bad key"}}""".toByteArray()) } }
        val e = assertFailsWith<ModelHttpException> { model(url).stream(listOf(Msg.User("hi")), emptyList()) { } }
        assertEquals(401, e.status)
        assertEquals(1, calls)
    }

    @Test fun `a server that ignores the request to stream still works`() {
        val url = serve { ex, _ -> val r = """{"choices":[{"message":{"content":"whole"}}]}""".toByteArray(); ex.sendResponseHeaders(200, r.size.toLong()); ex.responseBody.use { it.write(r) } }
        val seen = mutableListOf<String>()
        assertEquals("whole", model(url).stream(listOf(Msg.User("hi")), emptyList()) { seen += it }.text)
        assertEquals(listOf("whole"), seen)
    }

    // ---- in the agent loop --------------------------------------------------------------

    private class Streaming(val pieces: List<String>) : ModelClient {
        override fun complete(messages: List<Msg>, tools: List<ToolSpec>) = ModelReply(pieces.joinToString(""))
        override fun stream(messages: List<Msg>, tools: List<ToolSpec>, onText: (String) -> Unit): ModelReply {
            var so = ""
            for (p in pieces) { so += p; onText(so) }
            return ModelReply(so)
        }
    }

    @Test fun `the agent reports the answer as it grows and saves the whole answer`() {
        val events = mutableListOf<RunEvent>()
        val store = InMemoryRunStore()
        val r = AgentRuntime(store, Streaming(listOf("One ", "two ", "three")), emptyList<RegisteredTool>(), PermissionBroker(), Budget.TierA)
            .run("s", "count", EventSink { events += it })
        assertEquals("One two three", r.text)
        assertEquals(listOf("One ", "One two ", "One two three"), events.filterIsInstance<RunEvent.Partial>().map { it.text })
        assertTrue(events.last() is RunEvent.Ended)
        assertEquals(listOf(Msg.User("count"), Msg.Assistant("One two three")), store.history("s"))
    }

    @Test fun `Stop pressed while the answer is being written ends the run at once and keeps what was said`() {
        val store = InMemoryRunStore()
        var checks = 0
        val guard = RunGuard { if (++checks >= 2) "Stopped. Nothing more was done." else null } // the loop check, then the first piece
        val r = AgentRuntime(store, Streaming(listOf("a ", "b ", "c ")), { emptyList<RegisteredTool>() }, PermissionBroker(), Budget.TierA, guard)
            .run("s", "go")
        assertEquals(RunOutcome.FAILED, r.outcome)
        assertEquals("Stopped. Nothing more was done.", r.text)
    }
}
