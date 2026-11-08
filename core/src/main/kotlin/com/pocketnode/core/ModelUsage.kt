package com.pocketnode.core

/**
 * What the model provider says a request used, read from the raw reply so it works for every provider the same way
 * (OpenAI format and OpenRouter: prompt_tokens, completion_tokens; Anthropic: input_tokens, output_tokens and cache reads;
 * Ollama: prompt_eval_count, eval_count). Providers bill by these numbers, so they are the true size of a request,
 * unlike our own estimate of four characters to a token.
 */
object ModelUsage {
    /** [input] is what was sent (cached part included for OpenAI style, added for Anthropic style), [output] what came back, [cached] the part of the input read from the provider's cache. */
    data class Tokens(val input: Long, val output: Long, val cached: Long) {
        operator fun plus(o: Tokens) = Tokens(input + o.input, output + o.output, cached + o.cached)
    }

    private val inKeys = Regex("\"(prompt_tokens|input_tokens|prompt_eval_count)\"\\s*:\\s*(\\d+)")
    private val outKeys = Regex("\"(completion_tokens|output_tokens|eval_count)\"\\s*:\\s*(\\d+)")
    private val cacheRead = Regex("\"(cached_tokens|cache_read_input_tokens)\"\\s*:\\s*(\\d+)")
    private val cacheWrite = Regex("\"cache_creation_input_tokens\"\\s*:\\s*(\\d+)")

    /** Collects the numbers over all the lines of one reply. The largest figure seen for each kind wins, because streams repeat running totals. */
    class Scanner {
        private var input = -1L; private var output = -1L; private var cached = 0L; private var written = 0L; private var anthropic = false
        fun add(text: String) {
            if (text.isEmpty() || (!text.contains("tokens") && !text.contains("_count"))) return
            inKeys.findAll(text).forEach { input = maxOf(input, it.groupValues[2].toLong()); if (it.groupValues[1] == "input_tokens") anthropic = true }
            outKeys.findAll(text).forEach { output = maxOf(output, it.groupValues[2].toLong()) }
            cacheRead.findAll(text).forEach { cached = maxOf(cached, it.groupValues[2].toLong()) }
            cacheWrite.findAll(text).forEach { written = maxOf(written, it.groupValues[1].toLong()) }
        }
        fun result(): Tokens? {
            if (input < 0 && output < 0) return null
            // Anthropic's input_tokens leaves out what was read from or written to the cache; those are input too.
            val total = maxOf(input, 0L) + if (anthropic) cached + written else 0L
            return Tokens(total, maxOf(output, 0L), cached)
        }
    }

    private val perThread = ThreadLocal<Tokens?>()

    /** Called by the HTTP layer when a reply finished. Adds to what this thread has used since [take]. */
    internal fun report(t: Tokens) { perThread.set(perThread.get()?.plus(t) ?: t); log?.invoke(t) }

    /** What this thread's model calls reported since the last call to [take], then forgets it. Null when no provider gave numbers. */
    fun take(): Tokens? = perThread.get().also { perThread.remove() }

    /** For logging each reply as it arrives. */
    @Volatile var log: ((Tokens) -> Unit)? = null
}
