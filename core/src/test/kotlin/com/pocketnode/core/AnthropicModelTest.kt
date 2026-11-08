package com.pocketnode.core

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class AnthropicModelTest {
    private var server: HttpServer? = null
    private var lastBody = ""
    private val lastHeaders = mutableMapOf<String, String>()

    @AfterTest fun stop() { server?.stop(0) }

    private fun serve(status: Int, response: String): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/v1/messages") { ex ->
            lastBody = ex.requestBody.readBytes().decodeToString()
            ex.requestHeaders.forEach { (k, v) -> lastHeaders[k.lowercase()] = v.first() }
            val bytes = response.toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        s.start()
        server = s
        return "http://127.0.0.1:${s.address.port}"
    }

    private val clock = ToolSpec("clock.now", "Current time", "{}", idempotent = true)

    @Test
    fun `sends key, version, mapped tool names and an object schema`() {
        val url = serve(200, """{"content":[{"type":"text","text":"hello"}]}""")
        val reply = AnthropicModel({ "sk-test" }, baseUrl = url).complete(listOf(Msg.User("hi")), listOf(clock))
        assertEquals("hello", reply.text)
        assertEquals("sk-test", lastHeaders["x-api-key"])
        assertEquals("2023-06-01", lastHeaders["anthropic-version"])
        val tool = Json.parseToJsonElement(lastBody).jsonObject["tools"]!!.jsonArray.single().jsonObject
        assertEquals("clock__now", tool["name"]!!.jsonPrimitive.content)
        assertEquals("object", tool["input_schema"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `parses tool_use and maps the name back`() {
        val url = serve(200, """{"content":[{"type":"text","text":"checking"},{"type":"tool_use","id":"tu1","name":"clock__now","input":{"tz":"UTC"}}],"stop_reason":"tool_use"}""")
        val reply = AnthropicModel({ "k" }, baseUrl = url).complete(listOf(Msg.User("time?")), listOf(clock))
        assertEquals("checking", reply.text)
        assertEquals(listOf(ToolCall("tu1", "clock.now", """{"tz":"UTC"}""")), reply.calls)
    }

    @Test
    fun `merges consecutive tool results into one user message after the assistant tool_use`() {
        val url = serve(200, """{"content":[{"type":"text","text":"ok"}]}""")
        val calls = listOf(ToolCall("a", "clock.now", "{}"), ToolCall("b", "clock.now", "{}"))
        AnthropicModel({ "k" }, baseUrl = url).complete(
            listOf(Msg.User("x"), Msg.Assistant("", calls), Msg.ToolResult("a", "clock.now", "1"), Msg.ToolResult("b", "clock.now", "")),
            listOf(clock),
        )
        val msgs = Json.parseToJsonElement(lastBody).jsonObject["messages"]!!.jsonArray
        assertEquals(listOf("user", "assistant", "user"), msgs.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        val assistant = msgs[1].jsonObject["content"]!!.jsonArray
        assertEquals(listOf("tool_use", "tool_use"), assistant.map { it.jsonObject["type"]!!.jsonPrimitive.content }) // no empty text block
        val results = msgs[2].jsonObject["content"]!!.jsonArray
        assertEquals(listOf("a", "b"), results.map { it.jsonObject["tool_use_id"]!!.jsonPrimitive.content })
        assertEquals("(empty)", results[1].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `omits tools when none are offered`() {
        val url = serve(200, """{"content":[{"type":"text","text":"plain"}]}""")
        AnthropicModel({ "k" }, baseUrl = url).complete(listOf(Msg.User("x")), emptyList())
        assertTrue(!Json.parseToJsonElement(lastBody).jsonObject.containsKey("tools"))
    }

    @Test
    fun `surfaces the API error message without the key`() {
        val url = serve(401, """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""")
        val e = assertFailsWith<ModelHttpException> { AnthropicModel({ "sk-secret" }, baseUrl = url).complete(listOf(Msg.User("x")), emptyList()) }
        assertEquals(401, e.status)
        assertTrue(e.message!!.contains("invalid x-api-key"))
        assertTrue(!e.message!!.contains("sk-secret"))
    }

    @Test
    fun `fails clearly when no key is set`() {
        val e = assertFailsWith<IllegalStateException> { AnthropicModel({ null }).complete(listOf(Msg.User("x")), emptyList()) }
        assertTrue(e.message!!.contains("API key"))
    }
}
