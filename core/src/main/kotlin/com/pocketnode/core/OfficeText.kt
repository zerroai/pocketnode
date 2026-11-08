package com.pocketnode.core

/**
 * The light Markdown that the document tools accept (headings, bullets, numbered lists, paragraphs, bold and italic), read into
 * blocks that the Word and PowerPoint writers lay out. The rules match [DocumentLayout], so one text looks the same in a PDF,
 * a Word file and a slide deck.
 */
internal object OfficeText {
    sealed interface Block {
        data class Heading(val runs: List<Seg>, val level: Int) : Block
        data class Para(val runs: List<Seg>) : Block
        data class Item(val marker: String, val runs: List<Seg>) : Block
    }

    fun blocks(text: String): List<Block> {
        val out = mutableListOf<Block>()
        for (raw in text.replace("\r\n", "\n").lines()) {
            val line = raw.trimEnd()
            val runs = { s: String -> MarkdownLite.parse(s.trim()).filter { it.text != "\n" } }
            when {
                line.isBlank() -> {}
                Regex("^#\\s+.+").matches(line) -> out += Block.Heading(runs(line.removePrefix("#")), 1)
                Regex("^#{2,6}\\s+.+").matches(line) -> out += Block.Heading(runs(line.trimStart('#')), 2)
                Regex("^\\s*[-*•]\\s+.+").matches(line) -> out += Block.Item("•", runs(line.trimStart().drop(1)))
                Regex("^\\s*\\d{1,3}[.)]\\s+.+").matches(line) -> { val m = Regex("^\\s*(\\d{1,3})[.)]\\s+(.+)").find(line)!!; out += Block.Item(m.groupValues[1] + ".", runs(m.groupValues[2])) }
                else -> out += Block.Para(runs(line))
            }
        }
        return out
    }

    /** The words of a run list as one plain string. */
    fun plain(runs: List<Seg>): String = runs.joinToString("") { it.text }

    /** Escapes text for use inside an XML element, and drops the control characters XML does not allow. */
    fun xml(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (ch in s) {
            when {
                ch == '&' -> sb.append("&amp;")
                ch == '<' -> sb.append("&lt;")
                ch == '>' -> sb.append("&gt;")
                ch == '"' -> sb.append("&quot;")
                ch.code < 0x20 && ch != '\t' && ch != '\n' && ch != '\r' -> {}
                ch.code == 0xFFFE || ch.code == 0xFFFF -> {}
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}
