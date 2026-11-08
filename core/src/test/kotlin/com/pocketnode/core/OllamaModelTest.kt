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

class OllamaModelTest {
    private var server: HttpServer? = null
    private var lastBody = ""
    private val headers = mutableMapOf<String, String>()

    @AfterTest fun stop() { server?.stop(0) }

    private fun serve(status: Int, response: String): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/api/chat") { ex ->
            lastBody = ex.requestBody.readBytes().decodeToString()
            ex.requestHeaders.forEach { (k, v) -> headers[k.lowercase()] = v.first() }
            val b = response.toByteArray()
            ex.sendResponseHeaders(status, b.size.toLong()); ex.responseBody.use { it.write(b) }
        }
        s.start(); server = s
        return "http://127.0.0.1:${s.address.port}"
    }

    private val clock = ToolSpec("clock.now", "time", "{}", idempotent = true)
    private fun model(url: String) = OllamaModel({ url }, { "llama3.1:8b" })

    @Test
    fun `sends model, mapped tools, a system prompt and the tunnel header`() {
        val url = serve(200, """{"message":{"role":"assistant","content":"hi"}}""")
        assertEquals("hi", model(url).complete(listOf(Msg.User("yo")), listOf(clock)).text)
        val b = Json.parseToJsonElement(lastBody).jsonObject
        assertEquals("llama3.1:8b", b["model"]!!.jsonPrimitive.content)
        assertEquals(false, b["stream"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("system", b["messages"]!!.jsonArray[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("clock__now", b["tools"]!!.jsonArray.single().jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("true", headers["ngrok-skip-browser-warning"])
    }

    @Test
    fun `parses tool calls with object arguments and maps names back`() {
        val url = serve(200, """{"message":{"role":"assistant","content":"","tool_calls":[{"id":"call_1","function":{"index":0,"name":"clock__now","arguments":{"tz":"UTC"}}}]}}""")
        val r = model(url).complete(listOf(Msg.User("t")), listOf(clock))
        assertEquals(listOf(ToolCall("call_1", "clock.now", """{"tz":"UTC"}""")), r.calls)
    }

    @Test
    fun `invents an id when the server gives none`() {
        val url = serve(200, """{"message":{"role":"assistant","content":"","tool_calls":[{"function":{"name":"clock__now","arguments":{}}}]}}""")
        assertTrue(model(url).complete(listOf(Msg.User("t")), listOf(clock)).calls.single().id.startsWith("call_"))
    }

    @Test
    fun `sends assistant tool calls with object arguments and tool results by name`() {
        val url = serve(200, """{"message":{"role":"assistant","content":"ok"}}""")
        model(url).complete(listOf(Msg.User("x"), Msg.Assistant("", listOf(ToolCall("c1", "clock.now", """{"a":1}"""))), Msg.ToolResult("c1", "clock.now", "12:00")), listOf(clock))
        val msgs = Json.parseToJsonElement(lastBody).jsonObject["messages"]!!.jsonArray
        val call = msgs[2].jsonObject["tool_calls"]!!.jsonArray.single().jsonObject["function"]!!.jsonObject
        assertEquals("1", call["arguments"]!!.jsonObject["a"]!!.jsonPrimitive.content)
        assertEquals("tool", msgs[3].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("clock__now", msgs[3].jsonObject["tool_name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `reports server errors and a missing server clearly`() {
        val url = serve(404, """{"error":"model 'x' not found"}""")
        val e = assertFailsWith<ModelHttpException> { model(url).complete(listOf(Msg.User("x")), emptyList()) }
        assertTrue(e.message!!.contains("not found"))
        assertFailsWith<IllegalStateException> { OllamaModel({ null }, { "m" }).complete(listOf(Msg.User("x")), emptyList()) }
    }
}
