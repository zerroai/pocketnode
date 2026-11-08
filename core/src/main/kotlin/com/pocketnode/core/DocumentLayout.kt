package com.pocketnode.core

/** One line of text placed on a page: [y] is the baseline, measured from the top. */
data class PdfLine(val x: Float, val y: Float, val text: String, val size: Float, val bold: Boolean)

data class PdfPage(val lines: List<PdfLine>)

/**
 * Turns plain text with light Markdown (headings, bullets, numbered lists, paragraphs) into pages of positioned lines. It knows
 * nothing about fonts or PDF: [measure] says how wide a piece of text is, and the platform draws the result. That keeps the
 * layout (wrapping, page breaks, headings that never sit alone at the bottom of a page) testable without a phone.
 */
object DocumentLayout {
    const val PAGE_WIDTH = 595f   // A4 in points
    const val PAGE_HEIGHT = 842f
    private const val MARGIN = 56f
    private const val BODY = 11f
    private const val LEADING = 1.4f

    private sealed interface Block {
        data class Heading(val text: String, val level: Int) : Block
        data class Para(val text: String) : Block
        data class Item(val marker: String, val text: String) : Block
        data object Gap : Block
    }

    private fun parse(text: String): List<Block> {
        val out = mutableListOf<Block>()
        for (raw in text.replace("\r\n", "\n").lines()) {
            val line = raw.trimEnd()
            val plain = { s: String -> MarkdownLite.parse(s).joinToString("") { it.text }.trim() }
            when {
                line.isBlank() -> if (out.lastOrNull() !is Block.Gap) out += Block.Gap
                Regex("^#{1}\\s+.+").matches(line) -> out += Block.Heading(plain(line.removePrefix("#").trim()), 1)
                Regex("^#{2,6}\\s+.+").matches(line) -> out += Block.Heading(plain(line.trimStart('#').trim()), 2)
                Regex("^\\s*[-*•]\\s+.+").matches(line) -> out += Block.Item("•", plain(line.trimStart().drop(1)))
                Regex("^\\s*\\d{1,3}[.)]\\s+.+").matches(line) -> { val m = Regex("^\\s*(\\d{1,3})[.)]\\s+(.+)").find(line)!!; out += Block.Item(m.groupValues[1] + ".", plain(m.groupValues[2])) }
                else -> out += Block.Para(plain(line))
            }
        }
        return out
    }

    /** Breaks [text] into lines no wider than [width], on spaces where it can and in the middle of a word where it must. */
    internal fun wrap(text: String, width: Float, size: Float, bold: Boolean, measure: (String, Float, Boolean) -> Float): List<String> {
        val lines = mutableListOf<String>()
        var current = ""
        for (word in text.split(' ').filter { it.isNotEmpty() }) {
            val tryLine = if (current.isEmpty()) word else "$current $word"
            if (measure(tryLine, size, bold) <= width) { current = tryLine; continue }
            if (current.isNotEmpty()) { lines += current; current = "" }
            var rest = word
            while (measure(rest, size, bold) > width && rest.length > 1) {
                var n = rest.length - 1
                while (n > 1 && measure(rest.substring(0, n), size, bold) > width) n--
                lines += rest.substring(0, n); rest = rest.substring(n)
            }
            current = rest
        }
        if (current.isNotEmpty()) lines += current
        return lines
    }

    fun layout(title: String, text: String, measure: (text: String, size: Float, bold: Boolean) -> Float): List<PdfPage> {
        val width = PAGE_WIDTH - 2 * MARGIN
        val bottom = PAGE_HEIGHT - MARGIN - 16f // leave room for the page number
        val pages = mutableListOf<MutableList<PdfLine>>()
        var page = mutableListOf<PdfLine>().also { pages += it }
        var y = MARGIN
        fun newPage() { page = mutableListOf<PdfLine>().also { pages += it }; y = MARGIN }
        fun need(h: Float) { if (y + h > bottom && page.isNotEmpty()) newPage() }

        if (title.isNotBlank()) {
            for (l in wrap(title.trim(), width, 22f, true, measure)) { y += 22f * 1.25f; page += PdfLine(MARGIN, y, l, 22f, true) }
            y += 14f
        }
        val blocks = parse(text)
        blocks.forEachIndexed { i, b ->
            when (b) {
                is Block.Gap -> y += BODY * 0.7f
                is Block.Heading -> {
                    val size = if (b.level == 1) 16f else 13f
                    val lines = wrap(b.text, width, size, true, measure)
                    // A heading is kept with at least two lines of what follows it.
                    need(size * LEADING * lines.size + BODY * LEADING * 2 + 10f)
                    y += if (page.size > 0) 8f else 0f
                    for (l in lines) { y += size * LEADING; page += PdfLine(MARGIN, y, l, size, true) }
                    y += 3f
                }
                is Block.Para -> for (l in wrap(b.text, width, BODY, false, measure)) { need(BODY * LEADING); y += BODY * LEADING; page += PdfLine(MARGIN, y, l, BODY, false) }
                is Block.Item -> {
                    val indent = 18f
                    wrap(b.text, width - indent, BODY, false, measure).forEachIndexed { n, l ->
                        need(BODY * LEADING); y += BODY * LEADING
                        if (n == 0) page += PdfLine(MARGIN + 2f, y, b.marker, BODY, false)
                        page += PdfLine(MARGIN + indent, y, l, BODY, false)
                    }
                    if (blocks.getOrNull(i + 1) !is Block.Item) y += 2f
                }
            }
        }
        val total = pages.size
        return pages.mapIndexed { n, lines ->
            val label = "Page ${n + 1} of $total"
            PdfPage(lines + PdfLine((PAGE_WIDTH - measure(label, 9f, false)) / 2f, PAGE_HEIGHT - MARGIN + 6f, label, 9f, false))
        }
    }
}
