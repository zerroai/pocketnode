package com.pocketnode.core

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class OpenAiModelTest {
    private var server: HttpServer? = null
    private var lastBody = ""
    private var lastPath = ""
    private val headers = mutableMapOf<String, String>()

    @AfterTest fun stop() { server?.stop(0) }

    private fun serve(status: Int, response: String): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/") { ex ->
            lastBody = ex.requestBody.readBytes().decodeToString(); lastPath = ex.requestURI.path
            ex.requestHeaders.forEach { (k, v) -> headers[k.lowercase()] = v.first() }
            val b = response.toByteArray()
            ex.sendResponseHeaders(status, b.size.toLong()); ex.responseBody.use { it.write(b) }
        }
        s.start(); server = s
        return "http://127.0.0.1:${s.address.port}/v1"
    }

    private val clock = ToolSpec("clock.now", "time", "{}", idempotent = true)
    private fun model(url: String, key: String? = "sk-test") = OpenAiModel({ url }, { key }, { "gpt-test" })

    @Test
    fun `sends the model, the key, mapped tools and a system prompt to chat completions`() {
        val url = serve(200, """{"choices":[{"message":{"role":"assistant","content":"hi"}}]}""")
        assertEquals("hi", model(url).complete(listOf(Msg.User("yo")), listOf(clock)).text)
        assertEquals("/v1/chat/completions", lastPath)
        assertEquals("Bearer sk-test", headers["authorization"])
        val b = Json.parseToJsonElement(lastBody).jsonObject
        assertEquals("gpt-test", b["model"]!!.jsonPrimitive.content)
        assertEquals("system", b["messages"]!!.jsonArray[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("clock__now", b["tools"]!!.jsonArray.single().jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `reads tool calls whose arguments are a JSON string, and maps names back`() {
        val url = serve(200, """{"choices":[{"message":{"content":null,"tool_calls":[{"id":"call_9","type":"function","function":{"name":"clock__now","arguments":"{\"tz\":\"UTC\"}"}}]}}]}""")
        val r = model(url).complete(listOf(Msg.User("t")), listOf(clock))
        assertEquals(listOf(ToolCall("call_9", "clock.now", """{"tz":"UTC"}""")), r.calls)
        assertEquals("", r.text)
    }

    @Test
    fun `sends earlier tool calls with string arguments and results tied to the call id`() {
        val url = serve(200, """{"choices":[{"message":{"content":"ok"}}]}""")
        model(url).complete(listOf(Msg.User("x"), Msg.Assistant("", listOf(ToolCall("c1", "clock.now", """{"a":1}"""))), Msg.ToolResult("c1", "clock.now", "12:00")), listOf(clock))
        val msgs = Json.parseToJsonElement(lastBody).jsonObject["messages"]!!.jsonArray
        assertEquals("""{"a":1}""", msgs[2].jsonObject["tool_calls"]!!.jsonArray.single().jsonObject["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content)
        assertEquals("c1", msgs[3].jsonObject["tool_call_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `no key means no authorization header, and errors carry the server's message`() {
        val url = serve(401, """{"error":{"message":"Incorrect API key provided"}}""")
        val e = assertFailsWith<ModelHttpException> { model(url, key = null).complete(listOf(Msg.User("x")), emptyList()) }
        assertEquals(401, e.status)
        assertTrue(e.message!!.contains("Incorrect API key"))
        assertTrue("authorization" !in headers)
    }

    /** A server that answers each request from [replies] in turn (status to body) and records the request bodies. */
    private fun serveSequence(vararg replies: Pair<Int, String>, bodies: MutableList<String>): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var i = 0
        s.createContext("/") { ex ->
            bodies.add(ex.requestBody.readBytes().decodeToString())
            val (status, response) = replies[minOf(i++, replies.lastIndex)]
            val b = response.toByteArray()
            ex.sendResponseHeaders(status, b.size.toLong()); ex.responseBody.use { it.write(b) }
        }
        s.start(); server = s
        return "http://127.0.0.1:${s.address.port}/v1"
    }

    private val tooPricey = """{"error":{"message":"This request requires more credits, or fewer max_tokens. You requested up to 16384 tokens, but can only afford 14010."}}"""

    @Test
    fun `when the provider says the account can only afford a shorter reply, the request is repeated once with that limit`() {
        val bodies = mutableListOf<String>()
        val url = serveSequence(402 to tooPricey, 200 to """{"choices":[{"message":{"role":"assistant","content":"ok"}}]}""", bodies = bodies)
        assertEquals("ok", model(url).complete(listOf(Msg.User("hi")), emptyList()).text)
        assertEquals(2, bodies.size)
        assertTrue("max_tokens" !in bodies[0])
        assertEquals("12609", Json.parseToJsonElement(bodies[1]).jsonObject["max_tokens"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a streamed request is also repeated with the affordable limit`() {
        val bodies = mutableListOf<String>()
        val sse = "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n"
        val url = serveSequence(402 to tooPricey, 200 to sse, bodies = bodies)
        val seen = StringBuilder()
        val reply = model(url).stream(listOf(Msg.User("hi")), emptyList()) { seen.append(it) }
        assertEquals("ok", reply.text)
        assertEquals(2, bodies.size)
        assertEquals("12609", Json.parseToJsonElement(bodies[1]).jsonObject["max_tokens"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the shorter-reply retry happens only once and not when the room is too small to help`() {
        val bodies = mutableListOf<String>()
        val url = serveSequence(402 to tooPricey, bodies = bodies)
        assertFailsWith<ModelHttpException> { model(url).complete(listOf(Msg.User("hi")), emptyList()) }
        assertEquals(2, bodies.size)
        val tiny = mutableListOf<String>()
        val url2 = serveSequence(402 to tooPricey.replace("14010", "40"), bodies = tiny)
        assertFailsWith<ModelHttpException> { model(url2).complete(listOf(Msg.User("hi")), emptyList()) }
        assertEquals(1, tiny.size)
    }

    @Test
    fun `the probe accepts a working server and explains the common failures`() {
        assertIs<ProbeResult.Ok>(ModelProbe.openAi(serve(200, """{"data":[]}"""), "k", "m"))
        assertTrue((ModelProbe.openAi(serve(401, "{}"), "k", "m") as ProbeResult.Failed).reason.contains("key"))
        assertTrue((ModelProbe.openAi(serve(404, "{}"), "k", "m") as ProbeResult.Failed).reason.contains("/v1"))
        assertTrue(ModelProbe.openAi("nope", "k", "m") is ProbeResult.Failed)
        assertTrue(ModelProbe.openAi("https://x.test/v1", "k", "") is ProbeResult.Failed)
    }
}
