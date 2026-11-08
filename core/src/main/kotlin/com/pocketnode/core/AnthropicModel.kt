package com.pocketnode.core

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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
 * [ModelClient] for the Anthropic Messages API (tool use). Blocking; call from a worker thread.
 * The API key is read per call so a key entered in settings applies to the next turn.
 */
class AnthropicModel(
    private val apiKey: () -> String?,
    private val model: () -> String = { "claude-haiku-5-5" },
    private val system: () -> String = { "You are Pocketnode, a helpful assistant running on the user's phone. Be concise." },
    private val baseUrl: String = "https://api.anthropic.com",
    private val maxTokens: Int = 1024,
) : ModelClient {
    private val json = Json { ignoreUnknownKeys = true }

    override fun complete(messages: List<Msg>, tools: List<ToolSpec>): ModelReply {
        val key = apiKey()?.takeIf { it.isNotBlank() } ?: throw IllegalStateException("No API key set. Add one in settings.")
        val body = buildJsonObject {
            put("model", model())
            put("max_tokens", maxTokens)
            put("system", system())
            put("messages", encodeMessages(messages))
            if (tools.isNotEmpty()) put("tools", encodeTools(tools))
        }
        val reply = post(key, body.toString())
        return decode(reply)
    }

    override fun stream(messages: List<Msg>, tools: List<ToolSpec>, onText: (String) -> Unit): ModelReply {
        val key = apiKey()?.takeIf { it.isNotBlank() } ?: throw IllegalStateException("No API key set. Add one in settings.")
        val body = buildJsonObject {
            put("model", model())
            put("max_tokens", maxTokens)
            put("stream", true)
            put("system", system())
            put("messages", encodeMessages(messages))
            if (tools.isNotEmpty()) put("tools", encodeTools(tools))
        }
        var delivered = false
        val reader = StreamReader.Anthropic { delivered = true; onText(it) }
        try {
            val r = ModelHttp.postStream("$baseUrl/v1/messages", mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"), body.toString(), 120_000, reader::line)
            if (r.code !in 200..299) throw ModelHttpException(r.code, errorMessage(r.body))
            if (!reader.sawData && reader.other.isNotBlank()) return decode(reader.other.toString()).also { if (it.text.isNotEmpty()) onText(it.text) }
            return reader.reply()
        } catch (e: StreamStopped) {
            throw e
        } catch (e: Exception) {
            if (!delivered && (streamingNotSupported(e) || e is kotlinx.serialization.SerializationException)) return complete(messages, tools).also { if (it.text.isNotEmpty()) onText(it.text) }
            throw e
        }
    }

    internal fun encodeMessages(messages: List<Msg>): JsonArray = buildJsonArray {
        val photos = Images.attachments(messages)
        var pendingResults = mutableListOf<JsonElement>()
        fun flush() {
            if (pendingResults.isEmpty()) return
            add(buildJsonObject { put("role", "user"); put("content", JsonArray(pendingResults)) })
            pendingResults = mutableListOf()
        }
        for ((i, m) in messages.withIndex()) {
            when (m) {
                is Msg.ToolResult -> pendingResults += buildJsonObject {
                    put("type", "tool_result")
                    put("tool_use_id", m.callId)
                    val mine = photos[i].orEmpty()
                    if (mine.isEmpty()) put("content", Images.readable(m.text, mine).ifBlank { "(empty)" })
                    else put("content", buildJsonArray {
                        add(buildJsonObject { put("type", "text"); put("text", Images.readable(m.text, mine)) })
                        for (ref in mine) Images.base64(ref)?.let { b ->
                            add(buildJsonObject {
                                put("type", "image")
                                put("source", buildJsonObject { put("type", "base64"); put("media_type", "image/jpeg"); put("data", b) })
                            })
                        }
                    })
                }
                is Msg.User -> {
                    flush()
                    val mine = photos[i].orEmpty()
                    val words = Images.readable(Attachments.expandForModel(m.text), mine)
                    add(buildJsonObject {
                        put("role", "user")
                        if (mine.isEmpty()) put("content", words.ifBlank { "(empty)" })
                        else put("content", buildJsonArray {
                            for (ref in mine) Images.base64(ref)?.let { b ->
                                add(buildJsonObject {
                                    put("type", "image")
                                    put("source", buildJsonObject { put("type", "base64"); put("media_type", "image/jpeg"); put("data", b) })
                                })
                            }
                            add(buildJsonObject { put("type", "text"); put("text", words.ifBlank { "(see the attached photo)" }) })
                        })
                    })
                }
                is Msg.Assistant -> {
                    flush()
                    add(buildJsonObject {
                        put("role", "assistant")
                        put("content", buildJsonArray {
                            if (m.text.isNotBlank()) add(buildJsonObject { put("type", "text"); put("text", m.text) })
                            for (c in m.calls) add(buildJsonObject {
                                put("type", "tool_use")
                                put("id", c.id)
                                put("name", ToolNames.wire(c.name))
                                put("input", parseObject(c.args))
                            })
                            // The API rejects an empty content array.
                            if (m.text.isBlank() && m.calls.isEmpty()) add(buildJsonObject { put("type", "text"); put("text", "(no reply)") })
                        })
                    })
                }
            }
        }
        flush()
    }

    internal fun encodeTools(tools: List<ToolSpec>): JsonArray = buildJsonArray {
        for (t in tools) add(buildJsonObject {
            put("name", ToolNames.wire(t.name))
            put("description", t.description)
            val schema = parseObject(t.schema)
            put("input_schema", if (schema.containsKey("type")) schema else JsonObject(schema + ("type" to JsonPrimitive("object"))))
        })
    }

    internal fun decode(responseBody: String): ModelReply {
        val root = json.parseToJsonElement(responseBody).jsonObject
        val text = StringBuilder()
        val calls = mutableListOf<ToolCall>()
        for (block in root["content"]?.jsonArray.orEmpty()) {
            val o = block.jsonObject
            when (o["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> text.append(o["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
                "tool_use" -> calls += ToolCall(
                    id = o.getValue("id").jsonPrimitive.content,
                    name = ToolNames.core(o.getValue("name").jsonPrimitive.content),
                    args = (o["input"] ?: JsonObject(emptyMap())).toString(),
                )
            }
        }
        return ModelReply(text.toString(), calls)
    }

    private fun post(key: String, payload: String): String {
        val r = ModelHttp.post("$baseUrl/v1/messages", mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"), payload, 120_000)
        if (r.code !in 200..299) throw ModelHttpException(r.code, errorMessage(r.body))
        return r.body
    }

    private fun errorMessage(body: String): String = try {
        json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull ?: body.take(200)
    } catch (e: Exception) {
        body.take(200)
    }

    private fun parseObject(s: String): JsonObject = try {
        json.parseToJsonElement(s.ifBlank { "{}" }) as? JsonObject ?: JsonObject(emptyMap())
    } catch (e: Exception) {
        JsonObject(emptyMap())
    }

}

class ModelHttpException(val status: Int, message: String) : RuntimeException("HTTP $status: $message")
