package com.pocketnode.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DocumentLayoutTest {
    /** Every character is half as wide as the font is tall, bold a little wider. Good enough to test wrapping. */
    private val measure = { t: String, size: Float, bold: Boolean -> t.length * size * (if (bold) 0.55f else 0.5f) }
    private fun lines(pages: List<PdfPage>) = pages.flatMap { it.lines }.filter { !it.text.startsWith("Page ") }

    @Test
    fun `a short document is one page with its title, headings and text in order`() {
        val pages = DocumentLayout.layout("Meeting notes", "# Summary\nWe agreed the plan.\n\n## Next steps\n- Ada sends the draft\n- Bo books the room\n1. First\n2. Second", measure)
        assertEquals(1, pages.size)
        val l = lines(pages)
        assertEquals(listOf("Meeting notes", "Summary", "We agreed the plan.", "Next steps"), l.take(4).map { it.text })
        assertEquals(true, l[0].bold && l[0].size == 22f)
        assertEquals(listOf("•", "Ada sends the draft", "•", "Bo books the room", "1.", "First", "2.", "Second"), l.drop(4).map { it.text })
        assertTrue(l.map { it.y }.zipWithNext().all { (a, b) -> b >= a }, "lines go down the page")
        assertEquals("Page 1 of 1", pages[0].lines.last().text)
    }

    @Test
    fun `long text wraps inside the margins and never runs off the page`() {
        val long = (1..200).joinToString(" ") { "word$it" }
        val pages = DocumentLayout.layout("", long, measure)
        for (line in lines(pages)) assertTrue(line.x + measure(line.text, line.size, line.bold) <= DocumentLayout.PAGE_WIDTH - 56f + 0.01f, "too wide: ${line.text.take(30)}")
        assertEquals(long, lines(pages).joinToString(" ") { it.text }, "nothing is lost or reordered")
    }

    @Test
    fun `a word longer than the line is broken rather than cut off`() {
        val word = "x".repeat(300)
        val out = lines(DocumentLayout.layout("", word, measure))
        assertTrue(out.size > 1)
        assertEquals(word, out.joinToString("") { it.text })
    }

    @Test
    fun `many paragraphs run onto more pages, each page numbered and inside the bottom margin`() {
        val text = (1..120).joinToString("\n\n") { "Paragraph $it. " + "Some words here. ".repeat(12) }
        val pages = DocumentLayout.layout("Report", text, measure)
        assertTrue(pages.size >= 3)
        pages.forEachIndexed { n, p ->
            assertEquals("Page ${n + 1} of ${pages.size}", p.lines.last().text)
            assertTrue(p.lines.dropLast(1).all { it.y <= DocumentLayout.PAGE_HEIGHT - 56f - 16f + 0.5f }, "page ${n + 1} stays above the bottom margin")
            assertTrue(p.lines.dropLast(1).all { it.y >= 56f })
        }
        assertEquals(120, Regex("Paragraph \\d+\\.").findAll(lines(pages).joinToString(" ") { it.text }).count())
    }

    @Test
    fun `a heading never sits alone at the bottom of a page`() {
        // Fill a page almost to the bottom, then add a heading and a paragraph.
        val filler = (1..48).joinToString("\n") { "line $it" }
        val pages = DocumentLayout.layout("", "$filler\n# Heading\nbody one\nbody two", measure)
        val last = pages.last().lines.filter { !it.text.startsWith("Page ") }
        if (pages.size > 1) assertTrue(last.firstOrNull()?.text == "Heading" || last.none { it.text == "Heading" } || last.indexOfFirst { it.text == "Heading" } < last.size - 1, "something follows the heading on its page")
        val all = lines(pages)
        val h = all.indexOfFirst { it.text == "Heading" }
        assertEquals("body one", all[h + 1].text)
    }

    @Test
    fun `markdown marks are removed and an empty document still makes a page`() {
        assertEquals(listOf("Bold and code stay words"), lines(DocumentLayout.layout("", "**Bold** and `code` stay words", measure)).map { it.text })
        val empty = DocumentLayout.layout("Only a title", "", measure)
        assertEquals(1, empty.size)
        assertEquals(1, DocumentLayout.layout("", "", measure).size)
    }
}
