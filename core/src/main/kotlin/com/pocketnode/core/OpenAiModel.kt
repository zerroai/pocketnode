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
 * [ModelClient] for any server that speaks the OpenAI chat completions format (`/chat/completions`, non-streaming, tool
 * calling): OpenAI itself, OpenRouter, Groq, Together, LM Studio, vLLM and others. Blocking; call from a worker thread.
 */
class OpenAiModel(
    private val baseUrl: () -> String?,
    private val apiKey: () -> String?,
    private val model: () -> String,
    private val system: () -> String = { "You are Pocketnode, a helpful assistant running on the user's phone. Be concise." },
) : ModelClient {
    private val json = Json { ignoreUnknownKeys = true }

    override fun complete(messages: List<Msg>, tools: List<ToolSpec>): ModelReply = complete(messages, tools, null)

    /** [cap] limits the length of the reply; it is only set on the one retry after a provider says the account cannot afford a long one. */
    private fun complete(messages: List<Msg>, tools: List<ToolSpec>, cap: Int?): ModelReply {
        val url = baseUrl()?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: throw IllegalStateException("No model server set. Add one in settings.")
        val body = buildJsonObject {
            put("model", model())
            put("messages", encodeMessages(messages))
            if (tools.isNotEmpty()) put("tools", encodeTools(tools))
            if (cap != null) put("max_tokens", cap)
        }
        try {
            return decode(post("$url/chat/completions", body.toString()))
        } catch (e: ModelHttpException) {
            val n = if (cap == null) affordableTokens(e) else null
            if (n != null) return complete(messages, tools, n)
            throw e
        }
    }

    override fun stream(messages: List<Msg>, tools: List<ToolSpec>, onText: (String) -> Unit): ModelReply = stream(messages, tools, onText, null)

    private fun stream(messages: List<Msg>, tools: List<ToolSpec>, onText: (String) -> Unit, cap: Int?): ModelReply {
        val url = baseUrl()?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: throw IllegalStateException("No model server set. Add one in settings.")
        val body = buildJsonObject {
            put("model", model())
            put("stream", true)
            put("messages", encodeMessages(messages))
            if (tools.isNotEmpty()) put("tools", encodeTools(tools))
            if (cap != null) put("max_tokens", cap)
        }
        var delivered = false
        val reader = StreamReader.OpenAi { delivered = true; onText(it) }
        try {
            val auth = apiKey()?.takeIf { it.isNotBlank() }?.let { mapOf("authorization" to "Bearer ${it.trim()}") }.orEmpty()
            val r = ModelHttp.postStream("$url/chat/completions", auth, body.toString(), 90_000, reader::line)
            if (r.code !in 200..299) throw ModelHttpException(r.code, errorMessage(r.body))
            // A server that ignored the request to stream sends one ordinary answer.
            if (!reader.sawData && reader.other.isNotBlank()) return decode(reader.other.toString()).also { if (it.text.isNotEmpty()) onText(it.text) }
            return reader.reply()
        } catch (e: StreamStopped) {
            throw e
        } catch (e: ModelHttpException) {
            val n = if (cap == null && !delivered) affordableTokens(e) else null
            if (n != null) return stream(messages, tools, onText, n)
            if (!delivered && streamingNotSupported(e)) return complete(messages, tools).also { if (it.text.isNotEmpty()) onText(it.text) }
            throw e
        } catch (e: Exception) {
            // Streaming is a nicety. If the server does not take it and nothing was shown yet, ask the ordinary way.
            if (!delivered && (streamingNotSupported(e) || e is kotlinx.serialization.SerializationException)) return complete(messages, tools).also { if (it.text.isNotEmpty()) onText(it.text) }
            throw e
        }
    }

    /**
     * Some providers (OpenRouter) refuse a request with 402 when the account holds less credit than the longest possible reply
     * would cost, and say how many tokens it can afford ("can only afford 14010"). A reply that short is still useful, so the
     * request is repeated once with that limit. Null when the answer is not of that kind or the room is too small to help.
     */
    internal fun affordableTokens(e: ModelHttpException): Int? {
        if (e.status != 402) return null
        val n = Regex("""can only afford (\d+)""").find(e.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: return null
        // A tenth is kept back: the figure the provider names has been seen to be a little too high once the question itself is counted.
        return (n - n / 10).takeIf { it >= 256 }
    }

    internal fun encodeMessages(messages: List<Msg>): JsonArray = buildJsonArray {
        add(buildJsonObject { put("role", "system"); put("content", system()) })
        val photos = Images.attachments(messages)
        var waiting = mutableListOf<String>()
        // Tool results cannot carry pictures in this format, so the pictures follow as one user message after the last result.
        fun flushPhotos() {
            if (waiting.isEmpty()) return
            add(buildJsonObject {
                put("role", "user")
                put("content", buildJsonArray {
                    add(buildJsonObject { put("type", "text"); put("text", "The photo${if (waiting.size > 1) "s" else ""} from the tool above:") })
                    for (ref in waiting) Images.base64(ref)?.let { b ->
                        add(buildJsonObject { put("type", "image_url"); put("image_url", buildJsonObject { put("url", "data:image/jpeg;base64,$b") }) })
                    }
                })
            })
            waiting = mutableListOf()
        }
        for ((i, m) in messages.withIndex()) {
            if (m !is Msg.ToolResult) flushPhotos()
            add(
                when (m) {
                    is Msg.User -> {
                        val mine = photos[i].orEmpty()
                        val words = Images.readable(Attachments.expandForModel(m.text), mine)
                        if (mine.isEmpty()) buildJsonObject { put("role", "user"); put("content", words) }
                        else buildJsonObject {
                            put("role", "user")
                            put("content", buildJsonArray {
                                add(buildJsonObject { put("type", "text"); put("text", words) })
                                for (ref in mine) Images.base64(ref)?.let { b ->
                                    add(buildJsonObject { put("type", "image_url"); put("image_url", buildJsonObject { put("url", "data:image/jpeg;base64,$b") }) })
                                }
                            })
                        }
                    }
                    is Msg.Assistant -> buildJsonObject {
                        put("role", "assistant")
                        put("content", m.text)
                        if (m.calls.isNotEmpty()) put("tool_calls", buildJsonArray {
                            for (c in m.calls) add(buildJsonObject {
                                put("id", c.id)
                                put("type", "function")
                                // This format wants the arguments as a string holding JSON.
                                put("function", buildJsonObject { put("name", ToolNames.wire(c.name)); put("arguments", c.args.ifBlank { "{}" }) })
                            })
                        })
                    }
                    is Msg.ToolResult -> {
                        val mine = photos[i].orEmpty()
                        waiting.addAll(mine)
                        buildJsonObject { put("role", "tool"); put("tool_call_id", m.callId); put("content", Images.readable(m.text, mine)) }
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
                put("parameters", if (schema.containsKey("type")) schema else JsonObject(schema + ("type" to JsonPrimitive("object"))))
            })
        })
    }

    internal fun decode(responseBody: String): ModelReply {
        val message = json.parseToJsonElement(responseBody).jsonObject["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
            ?: throw IllegalStateException("model server returned no message")
        val calls = message["tool_calls"]?.jsonArray.orEmpty().map { c ->
            val f = c.jsonObject.getValue("function").jsonObject
            val args = f["arguments"]
            ToolCall(
                id = c.jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: "call_${UUID.randomUUID().toString().take(8)}",
                name = ToolNames.core(f.getValue("name").jsonPrimitive.content),
                // Normally a string of JSON; accept an object too, since some servers send one.
                args = when (args) { null -> "{}"; is JsonPrimitive -> args.content.ifBlank { "{}" }; else -> args.toString() },
            )
        }
        return ModelReply(message["content"]?.takeIf { it is JsonPrimitive }?.jsonPrimitive?.contentOrNull.orEmpty(), calls)
    }

    private fun post(url: String, payload: String): String {
        val auth = apiKey()?.takeIf { it.isNotBlank() }?.let { mapOf("authorization" to "Bearer ${it.trim()}") }.orEmpty()
        val r = ModelHttp.post(url, auth, payload, 150_000)
        if (r.code !in 200..299) throw ModelHttpException(r.code, errorMessage(r.body))
        return r.body
    }

    private fun errorMessage(body: String): String = try {
        val e = json.parseToJsonElement(body).jsonObject["error"]
        (e as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull ?: (e as? JsonPrimitive)?.contentOrNull ?: body.take(200)
    } catch (e: Exception) {
        body.take(200)
    }

    private fun parseObject(s: String): JsonObject = try {
        json.parseToJsonElement(s.ifBlank { "{}" }) as? JsonObject ?: JsonObject(emptyMap())
    } catch (e: Exception) {
        JsonObject(emptyMap())
    }
}
