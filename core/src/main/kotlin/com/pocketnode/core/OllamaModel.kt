package com.pocketnode.core

import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * [ModelClient] for an Ollama server (`/api/chat`, non-streaming, tool calling). The server can
 * be on the same network, or reached through a tunnel such as ngrok. Blocking; call from a worker thread.
 */
class OllamaModel(
    private val baseUrl: () -> String?,
    private val model: () -> String,
    private val system: () -> String = { "You are Pocketnode, a helpful assistant running on the user's phone. Be concise." },
    private val extraHeaders: Map<String, String> = mapOf("ngrok-skip-browser-warning" to "true"),
) : ModelClient {
    private val json = Json { ignoreUnknownKeys = true }

    override fun complete(messages: List<Msg>, tools: List<ToolSpec>): ModelReply {
        val url = baseUrl()?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: throw IllegalStateException("No model server set. Add one in settings.")
        val body = buildJsonObject {
            put("model", model())
            put("stream", false)
            put("messages", encodeMessages(messages))
            if (tools.isNotEmpty()) put("tools", encodeTools(tools))
        }
        return decode(post("$url/api/chat", body.toString()))
    }

    override fun stream(messages: List<Msg>, tools: List<ToolSpec>, onText: (String) -> Unit): ModelReply {
        val url = baseUrl()?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: throw IllegalStateException("No model server set. Add one in settings.")
        val body = buildJsonObject {
            put("model", model())
            put("stream", true)
            put("messages", encodeMessages(messages))
            if (tools.isNotEmpty()) put("tools", encodeTools(tools))
        }
        var delivered = false
        val reader = StreamReader.Ollama { delivered = true; onText(it) }
        try {
            // A local model can take minutes on the first call while it loads; the timeout counts silence between lines.
            val r = ModelHttp.postStream("$url/api/chat", extraHeaders, body.toString(), 300_000, reader::line)
            if (r.code !in 200..299) throw ModelHttpException(r.code, errorMessage(r.body))
            return reader.reply()
        } catch (e: StreamStopped) {
            throw e
        } catch (e: Exception) {
            if (!delivered && streamingNotSupported(e)) return complete(messages, tools).also { if (it.text.isNotEmpty()) onText(it.text) }
            throw e
        }
    }

    internal fun encodeMessages(messages: List<Msg>): JsonArray = buildJsonArray {
        add(buildJsonObject { put("role", "system"); put("content", system()) })
        val photos = Images.attachments(messages)
        var waiting = mutableListOf<String>()
        // Pictures follow the tool results as one user message, which is where this API takes an `images` list.
        fun flushPhotos() {
            if (waiting.isEmpty()) return
            add(buildJsonObject {
                put("role", "user")
                put("content", "The photo${if (waiting.size > 1) "s" else ""} from the tool above.")
                put("images", buildJsonArray { for (ref in waiting) Images.base64(ref)?.let { add(JsonPrimitive(it)) } })
            })
            waiting = mutableListOf()
        }
        for ((i, m) in messages.withIndex()) {
            if (m !is Msg.ToolResult) flushPhotos()
            add(
                when (m) {
                    is Msg.User -> {
                        val mine = photos[i].orEmpty()
                        buildJsonObject {
                            put("role", "user")
                            put("content", Images.readable(Attachments.expandForModel(m.text), mine))
                            if (mine.isNotEmpty()) put("images", buildJsonArray { for (ref in mine) Images.base64(ref)?.let { add(JsonPrimitive(it)) } })
                        }
                    }
                    is Msg.Assistant -> buildJsonObject {
                        put("role", "assistant")
                        put("content", m.text)
                        if (m.calls.isNotEmpty()) put("tool_calls", buildJsonArray {
                            for (c in m.calls) add(buildJsonObject {
                                put("id", c.id)
                                put("function", buildJsonObject {
                                    put("name", ToolNames.wire(c.name))
                                    put("arguments", parseObject(c.args))
                                })
                            })
                        })
                    }
                    is Msg.ToolResult -> {
                        val mine = photos[i].orEmpty()
                        waiting.addAll(mine)
                        buildJsonObject {
                            put("role", "tool")
                            put("tool_name", ToolNames.wire(m.name))
                            put("content", Images.readable(m.text, mine))
                        }
                    }
                },
            )
        }
        flushPhotos()
    }

    internal fun encodeTools(tools: List<ToolSpec>): JsonArray = buildJsonArray {
        for (t in tools) add(buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", ToolNames.wire(t.name))
                put("description", t.description)
                val schema = parseObject(t.schema)
                put("parameters", if (schema.containsKey("type")) schema else JsonObject(schema + ("type" to kotlinx.serialization.json.JsonPrimitive("object"))))
            })
        })
    }

    internal fun decode(responseBody: String): ModelReply {
        val message = json.parseToJsonElement(responseBody).jsonObject["message"]?.jsonObject ?: throw IllegalStateException("model server returned no message")
        val calls = message["tool_calls"]?.jsonArray.orEmpty().map { c ->
            val f = c.jsonObject.getValue("function").jsonObject
            ToolCall(
                id = c.jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: "call_${UUID.randomUUID().toString().take(8)}",
                name = ToolNames.core(f.getValue("name").jsonPrimitive.content),
                args = (f["arguments"] ?: JsonObject(emptyMap())).toString(),
            )
        }
        return ModelReply(message["content"]?.jsonPrimitive?.contentOrNull.orEmpty(), calls)
    }

    private fun post(url: String, payload: String): String {
        // A local model can take minutes on the first call while it loads.
        val r = ModelHttp.post(url, extraHeaders, payload, 300_000)
        if (r.code !in 200..299) throw ModelHttpException(r.code, errorMessage(r.body))
        return r.body
    }

    private fun errorMessage(body: String): String = try {
        json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.contentOrNull ?: body.take(200)
    } catch (e: Exception) {
        body.take(200)
    }

    private fun parseObject(s: String): JsonObject = try {
        json.parseToJsonElement(s.ifBlank { "{}" }) as? JsonObject ?: JsonObject(emptyMap())
    } catch (e: Exception) {
        JsonObject(emptyMap())
    }
}
