package com.pocketnode.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Thrown instead of calling the model when the day's allowance is used up. Its message is shown to the owner as written. */
class UsageLimitException(message: String) : RuntimeException(message)

/**
 * A daily allowance for the model, counted in estimated tokens (about four characters each). The model provider charges by
 * tokens, so a cap here keeps a runaway chat, a loop, or a busy Telegram group from costing more than the owner chose.
 * The count restarts each morning in the phone's time zone. A limit of zero means no limit.
 */
class UsageMeter(private val kv: PackKv, private val today: () -> String = { SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()) }) {
    private val lock = Any()

    var limit: Long
        get() = synchronized(lock) { kv.get(NS, LIMIT)?.toLongOrNull()?.coerceAtLeast(0) ?: 0L }
        set(value) { synchronized(lock) { kv.set(NS, LIMIT, value.coerceAtLeast(0).toString()) } }

    fun usedToday(): Long = synchronized(lock) { if (kv.get(NS, DAY) == today()) kv.get(NS, TOKENS)?.toLongOrNull() ?: 0L else 0L }

    fun add(tokens: Long) {
        if (tokens <= 0) return
        synchronized(lock) {
            val used = usedToday()
            kv.set(NS, DAY, today()); kv.set(NS, TOKENS, (used + tokens).toString())
        }
    }

    /** Adds what the provider itself reported for a request: to the day's allowance and to running totals that show what the app really uses. */
    fun addReal(t: ModelUsage.Tokens) {
        add(t.input + t.output)
        synchronized(lock) {
            fun bump(key: String, by: Long) { kv.set(NS, key, ((kv.get(NS, key)?.toLongOrNull() ?: 0L) + by).toString()) }
            bump(R_IN, t.input); bump(R_OUT, t.output); bump(R_CACHED, t.cached); bump(R_CALLS, 1)
        }
    }

    /** Totals of what providers reported since the app was installed: tokens in, tokens out, tokens read from a cache, number of requests. */
    fun realTotals(): List<Long> = synchronized(lock) { listOf(R_IN, R_OUT, R_CACHED, R_CALLS).map { kv.get(NS, it)?.toLongOrNull() ?: 0L } }

    /** A sentence to show instead of calling the model, or null when there is room. */
    fun blockedReason(): String? {
        val max = limit
        if (max <= 0 || usedToday() < max) return null
        return "Today's limit of ${describe(max)} is used up. Raise it in Settings, or wait until tomorrow."
    }

    companion object {
        private const val NS = "core"
        private const val LIMIT = "usage.limit"
        private const val DAY = "usage.day"
        private const val TOKENS = "usage.tokens"
        private const val R_IN = "usage.real.in"
        private const val R_OUT = "usage.real.out"
        private const val R_CACHED = "usage.real.cached"
        private const val R_CALLS = "usage.real.calls"

        /** The usual rough count: four characters to a token. */
        fun estimate(chars: Int): Long = ((chars + 3) / 4).toLong()

        /** The choices the owner is offered, in estimated tokens. */
        val CHOICES = listOf(0L, 100_000L, 300_000L, 1_000_000L, 3_000_000L)

        fun describe(tokens: Long): String = when {
            tokens <= 0 -> "no limit"
            tokens >= 1_000_000 -> "${tokens / 1_000_000} million tokens"
            tokens >= 1_000 -> "${tokens / 1_000} thousand tokens"
            else -> "$tokens tokens"
        }
    }
}
