package com.pocketnode.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ModelUsageTest {
    private fun read(vararg lines: String) = ModelUsage.Scanner().also { s -> lines.forEach(s::add) }.result()

    @Test fun openAiStyleBody() {
        val t = read("""{"id":"x","choices":[],"usage":{"prompt_tokens":120,"completion_tokens":35,"total_tokens":155,"prompt_tokens_details":{"cached_tokens":64}}}""")
        assertEquals(ModelUsage.Tokens(120, 35, 64), t)
    }

    @Test fun anthropicStreamAddsCacheReadsToInputAndKeepsTheLastOutputTotal() {
        val t = read(
            """data: {"type":"message_start","message":{"usage":{"input_tokens":25,"cache_creation_input_tokens":0,"cache_read_input_tokens":100,"output_tokens":1}}}""",
            """data: {"type":"content_block_delta","delta":{"text":"Hi"}}""",
            """data: {"type":"message_delta","usage":{"output_tokens":15}}""",
        )
        assertEquals(ModelUsage.Tokens(125, 15, 100), t)
    }

    @Test fun ollamaFinalChunk() {
        val t = read("""{"message":{"content":"ok"},"done":false}""", """{"done":true,"prompt_eval_count":42,"eval_count":9}""")
        assertEquals(ModelUsage.Tokens(42, 9, 0), t)
    }

    @Test fun nothingReportedGivesNull() {
        assertNull(read("""{"choices":[{"message":{"content":"hello, 3 tokens of text"}}]}"""))
    }

    @Test fun usageIsKeptPerThreadUntilTaken() {
        ModelUsage.take()
        ModelUsage.report(ModelUsage.Tokens(10, 2, 0)); ModelUsage.report(ModelUsage.Tokens(5, 1, 0))
        assertEquals(ModelUsage.Tokens(15, 3, 0), ModelUsage.take())
        assertNull(ModelUsage.take())
    }

    @Test fun realTotalsAddUp() {
        val kv = InMemoryPackKv()
        val u = UsageMeter(kv)
        u.addReal(ModelUsage.Tokens(100, 20, 10)); u.addReal(ModelUsage.Tokens(50, 5, 0))
        assertEquals(listOf(150L, 25L, 10L, 2L), u.realTotals())
        assertEquals(175L, u.usedToday())
    }
}
