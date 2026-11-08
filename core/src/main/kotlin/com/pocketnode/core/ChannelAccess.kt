package com.pocketnode.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class ChannelPerson(val chatId: String, val name: String = "", val firstMessage: String = "", val at: Long = 0)

/**
 * Who may use the assistant through a chat app. Anyone can find a Telegram bot or message a WhatsApp number, so a message from a chat the
 * owner has not approved is never given to the assistant: it cannot use the owner's model credit or tools, and it cannot read the owner's
 * notes. The sender is told once that the assistant is private, and appears on the owner's pack screen to approve or block.
 */
class ChannelAccess(private val kv: PackKv, private val packId: String, private val now: () -> Long = System::currentTimeMillis) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(ChannelPerson.serializer())

    private fun read(key: String): List<ChannelPerson> = kv.get(packId, key)?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()
    private fun write(key: String, list: List<ChannelPerson>) = kv.set(packId, key, json.encodeToString(serializer, list))

    @Synchronized fun allowed(): List<ChannelPerson> = read(ALLOWED)
    @Synchronized fun pending(): List<ChannelPerson> = read(PENDING)
    @Synchronized fun isAllowed(chatId: String): Boolean = read(ALLOWED).any { it.chatId == chatId }

    /** Records a message from someone not approved. Returns true the first time this person is seen, so they are told once and not on every message. */
    @Synchronized fun noteUnknown(chatId: String, name: String, text: String): Boolean {
        val list = read(PENDING)
        if (list.any { it.chatId == chatId }) return false
        val person = ChannelPerson(chatId.take(64), name.replace(Regex("\\s+"), " ").trim().take(40), text.replace(Regex("\\s+"), " ").trim().take(80), now())
        write(PENDING, (listOf(person) + list).take(MAX_PENDING))
        return true
    }

    @Synchronized fun approve(chatId: String) {
        val person = read(PENDING).firstOrNull { it.chatId == chatId } ?: ChannelPerson(chatId)
        write(ALLOWED, read(ALLOWED).filterNot { it.chatId == chatId } + person)
        write(PENDING, read(PENDING).filterNot { it.chatId == chatId })
    }

    /** Declines a waiting person. They are forgotten, so a later message asks again. */
    @Synchronized fun dismiss(chatId: String) { write(PENDING, read(PENDING).filterNot { it.chatId == chatId }) }

    /** Takes approval away from someone. */
    @Synchronized fun revoke(chatId: String) { write(ALLOWED, read(ALLOWED).filterNot { it.chatId == chatId }) }

    /** Approves chats that already existed before approval was introduced, once, so an owner's own conversation keeps working. */
    @Synchronized fun grandfather(existing: List<ChannelPerson>) {
        if (kv.get(packId, GRANDFATHERED) != null) return
        existing.forEach { p -> if (read(ALLOWED).none { it.chatId == p.chatId }) write(ALLOWED, read(ALLOWED) + p) }
        kv.set(packId, GRANDFATHERED, "1")
    }

    companion object {
        const val MESSAGE_FOR_STRANGERS = "This assistant is private. The owner has been asked to approve you."
        private const val ALLOWED = "core.access.allowed"
        private const val PENDING = "core.access.pending"
        private const val GRANDFATHERED = "core.access.grandfathered"
        private const val MAX_PENDING = 20
    }
}
