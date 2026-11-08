package com.pocketnode.core

/** A run of text with one look. */
data class Seg(val text: String, val bold: Boolean = false, val italic: Boolean = false, val code: Boolean = false)

/**
 * The small part of Markdown that models actually use in chat: headings, bold, italic, inline and fenced code, and
 * bullet lists. Everything else is shown as written. Markers that are not closed stay as typed, so a stray asterisk
 * never swallows a paragraph.
 */
object MarkdownLite {
    private val INTERNAL_LINK = Regex("\\[[^\\]]*]\\((?:sandbox:|mc-doc:|mc-file:|mc-image:|file:)[^)]*\\)")
    private val INTERNAL_REF = Regex("(?:sandbox:/?)?mc-(?:doc|file|image):[0-9a-f]{16}")

    /** Models sometimes write a download link to a file the app already shows as a card. Those links go nowhere, so point at the card instead. */
    fun hideInternalLinks(text: String): String = text.replace(INTERNAL_LINK, "above").replace(INTERNAL_REF, "above")

    fun parse(text: String): List<Seg> {
        val out = mutableListOf<Seg>()
        var inFence = false
        val lines = text.replace("\r\n", "\n").split('\n')
        lines.forEachIndexed { i, raw ->
            if (raw.trimStart().startsWith("```")) { inFence = !inFence; return@forEachIndexed }
            if (inFence) {
                out += Seg(raw, code = true)
            } else {
                val heading = HEADING.matchEntire(raw)
                val bullet = BULLET.matchEntire(raw)
                when {
                    heading != null -> out += inline(heading.groupValues[1]).map { it.copy(bold = true) }
                    bullet != null -> { out += Seg(bullet.groupValues[1].replace("\t", "  ") + "• "); out += inline(bullet.groupValues[2]) }
                    else -> out += inline(raw)
                }
            }
            if (i < lines.lastIndex) out += Seg("\n")
        }
        return merge(out)
    }

    private val HEADING = Regex("^\\s{0,3}#{1,6}\\s+(.*?)\\s*#*\\s*$")
    private val BULLET = Regex("^(\\s*)[-*+]\\s+(.*)$")

    private fun inline(s: String): List<Seg> {
        val out = mutableListOf<Seg>()
        val plain = StringBuilder()
        fun flush() { if (plain.isNotEmpty()) { out += Seg(plain.toString()); plain.clear() } }
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '`') {
                val end = s.indexOf('`', i + 1)
                if (end > i + 1) { flush(); out += Seg(s.substring(i + 1, end), code = true); i = end + 1; continue }
            }
            if ((s.startsWith("**", i) || s.startsWith("__", i)) && i + 2 < s.length) {
                val mark = s.substring(i, i + 2)
                val end = s.indexOf(mark, i + 2)
                if (end > i + 2 && !s[i + 2].isWhitespace()) { flush(); out += inline(s.substring(i + 2, end)).map { it.copy(bold = true) }; i = end + 2; continue }
            }
            if ((c == '*' || (c == '_' && (i == 0 || !s[i - 1].isLetterOrDigit()))) && i + 1 < s.length && !s[i + 1].isWhitespace() && s[i + 1] != c) {
                val end = closing(s, c, i + 1)
                if (end > i + 1) { flush(); out += inline(s.substring(i + 1, end)).map { it.copy(italic = true) }; i = end + 1; continue }
            }
            plain.append(c); i++
        }
        flush()
        return out
    }

    /** The next [mark] that can close an emphasis: not after a space, not inside a word for underscores. */
    private fun closing(s: String, mark: Char, from: Int): Int {
        var j = from
        while (j < s.length) {
            if (s[j] == mark && !s[j - 1].isWhitespace() && (mark == '*' || j + 1 >= s.length || !s[j + 1].isLetterOrDigit()) && (j + 1 >= s.length || s[j + 1] != mark)) return j
            j++
        }
        return -1
    }

    private fun merge(segs: List<Seg>): List<Seg> {
        val out = mutableListOf<Seg>()
        for (s in segs) {
            val last = out.lastOrNull()
            if (last != null && last.bold == s.bold && last.italic == s.italic && last.code == s.code) out[out.lastIndex] = last.copy(text = last.text + s.text) else out += s
        }
        return out.filter { it.text.isNotEmpty() }
    }
}
