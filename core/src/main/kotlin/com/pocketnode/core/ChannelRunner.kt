package com.pocketnode.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class ChannelMessage(val id: Long, val chatId: String, val text: String, val name: String = "")

/**
 * Connects a channel pack (`channel.poll`, `channel.ack`, `channel.send`) to the agent.
 *
 * Delivery is at-least-once with dedupe: the reply is saved before it is sent, and a "sent"
 * marker is saved before the message is confirmed to the platform. After a crash the same
 * update comes back, the saved reply is reused, and a reply already sent is not sent again.
 * Only a crash between the platform accepting the send and the marker write can repeat a reply.
 */
class ChannelRunner(
    private val channel: String,
    private val packId: String,
    private val sandbox: PackSandbox,
    private val runtime: AgentRuntime,
    private val kv: PackKv,
    /** Who may use the assistant. Null means everyone, which only tests use. */
    private val access: ChannelAccess? = null,
    /** Told when a message from someone not approved is ignored, for the activity record. */
    private val onUnapproved: (channel: String, name: String) -> Unit = { _, _ -> },
    /** Called with the conversation id and the sender's display name so the UI can title the chat. */
    private val onChatName: (sessionId: String, name: String) -> Unit = { _, _ -> },
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Handles every pending message once. Returns how many were handled. Throws on pack/network errors; call again later. */
    fun pollOnce(): Int {
        val messages = json.decodeFromString(ListSerializer(ChannelMessage.serializer()), sandbox.callTool("channel.poll", "{}")).sortedBy { it.id }
        for (m in messages) deliver(m) { sandbox.callTool("channel.ack", buildJsonObject { put("id", JsonPrimitive(m.id)) }.toString()) }
        return messages.size
    }

    /**
     * Answers one message and sends the reply, then calls [confirm] to tell the source it is done.
     * Safe to call again for the same message after a crash: the reply is reused and not sent twice.
     */
    fun deliver(m: ChannelMessage, confirm: () -> Unit) {
        if (access != null && !access.isAllowed(m.chatId)) {
            // A stranger gets no assistant. They are told once, and the owner sees them on the pack screen.
            if (access.noteUnknown(m.chatId, m.name, m.text)) {
                onUnapproved(channel, m.name.ifBlank { m.chatId })
                runCatching { sandbox.callTool("channel.send", buildJsonObject { put("chatId", JsonPrimitive(m.chatId)); put("text", JsonPrimitive(ChannelAccess.MESSAGE_FOR_STRANGERS)) }.toString()) }
            }
            confirm()
            return
        }
        if (m.name.isNotBlank()) onChatName("$channel:${m.chatId}", m.name)
        val replyKey = "core.reply:${m.id}"
        val sentKey = "core.sent:${m.id}"
        val reply = kv.get(packId, replyKey) ?: runtime.run("$channel:${m.chatId}", m.text).text.also { kv.set(packId, replyKey, it) }
        if (kv.get(packId, sentKey) == null) {
            sandbox.callTool("channel.send", buildJsonObject { put("chatId", JsonPrimitive(m.chatId)); put("text", JsonPrimitive(reply)) }.toString())
            kv.set(packId, sentKey, "1")
        }
        confirm()
        kv.delete(packId, replyKey)
        kv.delete(packId, sentKey)
    }
}
