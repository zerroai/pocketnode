package com.pocketnode.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpokenTextTest {
    @Test fun `marks, links and list dashes are removed`() {
        val r = "## Plan\n- **Buy** milk\n- See [the page](https://example.com/x) or https://example.org/y\n1. Done."
        val s = SpokenText.clean(r)
        assertEquals("Plan Buy milk See the page or a link Done.", s)
    }

    @Test fun `sentences split at full stops but not inside numbers`() {
        assertEquals(listOf("It is 7.30 now.", "Is that fine?", "Good!"), SpokenText.sentences("It is 7.30 now. Is that fine? Good!"))
    }

    @Test fun `emoji and symbols are dropped and dashes become a pause`() {
        assertEquals("Sure, I can do that", SpokenText.clean("Sure — I can do that 😀 ✅"))
    }

    @Test fun `a long reply is cut at a sentence and points to the chat`() {
        val long = (1..40).joinToString(" ") { "This is sentence number $it of a long answer." }
        val out = SpokenText.sentences(long)
        assertEquals("The rest is in the chat.", out.last())
        assertTrue(out.sumOf { it.length } < 800 && out.size > 3)
    }

    @Test fun `nothing to say gives nothing`() {
        assertEquals(emptyList(), SpokenText.sentences("  😀  "))
    }

    @Test fun `only finished sentences are spoken while an answer is still coming`() {
        assertEquals(emptyList(), SpokenText.finished("Nairobi is the"))
        assertEquals(listOf("Nairobi is the capital."), SpokenText.finished("Nairobi is the capital. It is also"))
        assertEquals(listOf("Nairobi is the capital.", "It is big."), SpokenText.finished("Nairobi is the capital. It is big."))
        assertEquals(emptyList(), SpokenText.finished("It is 7."))   // a number may still be growing: 7.30
    }
}
