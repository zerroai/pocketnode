package com.pocketnode.core

/**
 * Chats whose last reply failed only because the phone could not reach the model. When a connection comes back they are tried
 * again by themselves, so a message written on a bad network is answered later without the owner doing anything. Only the
 * owner's own chats on this phone, only recent ones, and not more often than [MIN_GAP_MS] for the same chat.
 */
object OfflineQueue {
    const val MAX_AGE_MS = 6L * 60 * 60 * 1000
    const val MIN_GAP_MS = 30_000L
    private const val LOCAL = "local:"

    fun isWaiting(lastText: String): Boolean = lastText.startsWith(MODEL_ERROR + ModelErrors.NO_CONNECTION)

    fun waiting(sessions: List<SessionSummary>, nowMillis: Long, lastTried: Map<String, Long> = emptyMap()): List<SessionSummary> =
        sessions.filter { s ->
            s.id.startsWith(LOCAL) && !s.lastIsUser && isWaiting(s.lastText) &&
                nowMillis - s.lastActivityMillis <= MAX_AGE_MS && nowMillis - (lastTried[s.id] ?: Long.MIN_VALUE / 2) >= MIN_GAP_MS
        }
}
