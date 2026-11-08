package com.pocketnode.core

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals

class OfflineQueueTest {
    private val now = 10_000_000_000L
    private fun s(id: String, text: String, ageMs: Long = 1000, user: Boolean = false) = SessionSummary(id, id, text, user, now - ageMs)
    private val offline = MODEL_ERROR + ModelErrors.friendly(IOException("Unable to resolve host"))

    @Test fun `only a connection failure on a recent local chat waits for the network`() {
        val chats = listOf(
            s("local:a", offline), s("local:b", MODEL_ERROR + "The provider did not accept your API key. Check it in Settings."),
            s("local:c", "A normal answer"), s("local:old", offline, ageMs = OfflineQueue.MAX_AGE_MS + 1), s("telegram:1", offline), s("local:user", offline, user = true),
            s("local:timeout", MODEL_ERROR + ModelErrors.friendly(java.net.SocketTimeoutException())),
        )
        assertEquals(listOf("local:a"), OfflineQueue.waiting(chats, now).map { it.id })
    }

    @Test fun `the same chat is not tried again within the gap`() {
        val chats = listOf(s("local:a", offline))
        assertEquals(emptyList(), OfflineQueue.waiting(chats, now, mapOf("local:a" to now - OfflineQueue.MIN_GAP_MS + 1)))
        assertEquals(1, OfflineQueue.waiting(chats, now, mapOf("local:a" to now - OfflineQueue.MIN_GAP_MS)).size)
    }

    @Test fun `the stored sentence matches what the friendly error says for a lost connection`() {
        assertEquals(ModelErrors.NO_CONNECTION, ModelErrors.friendly(IOException("x")))
        assertEquals(true, OfflineQueue.isWaiting(offline))
    }
}
