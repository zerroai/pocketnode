package com.pocketnode.core

/**
 * Turns a chat reply into words that sound right when read aloud in a voice call: no markdown marks, no web addresses, no list dashes,
 * no emoji or symbols, and short pieces (one sentence each) so the voice can start early and be stopped between sentences.
 */
object SpokenText {
    private const val MAX_CHARS = 700

    /** The reply as sentences to speak, in order. Long replies are cut at a sentence and end with a pointer to the chat. */
    fun sentences(reply: String): List<String> {
        val flat = clean(reply)
        if (flat.isEmpty()) return emptyList()
        val all = split(flat)
        val out = mutableListOf<String>()
        var used = 0
        for (s in all) {
            if (used + s.length > MAX_CHARS && out.isNotEmpty()) { out += "The rest is in the chat."; break }
            out += s; used += s.length
        }
        return out
    }

    /** The finished sentences of an answer that is still being written: everything before the last full stop, question mark or exclamation mark. */
    fun finished(partial: String): List<String> {
        val flat = clean(partial)
        if (flat.isEmpty()) return emptyList()
        val all = split(flat)
        // A full stop right after a digit may be the start of "7.30": wait for the next letter.
        val ended = flat.last() in ".!?" && !(flat.length > 1 && flat.last() == '.' && flat[flat.length - 2].isDigit())
        return if (ended) all else all.dropLast(1)
    }

    fun clean(reply: String): String {
        var t = reply
        t = t.replace(Regex("```[\\s\\S]*?```"), " ")                                  // code blocks are not read out
        t = t.replace(Regex("!?\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")                       // [words](link) -> words
        t = t.replace(Regex("https?://\\S+"), "a link")
        t = t.replace(Regex("(?m)^\\s{0,3}#{1,6}\\s*"), "")                              // headings
        t = t.replace(Regex("(?m)^\\s*(?:[-*•]|\\d+[.)])\\s+"), "")                 // list marks
        t = t.replace(Regex("[*_`~]+"), "")                                              // emphasis and code marks
        t = t.replace(Regex("[\\u2190-\\u21FF\\u2600-\\u27BF\\u2B00-\\u2BFF\\uD83C-\\uDBFF\\uDC00-\\uDFFF\\uFE0F\\u200D]"), "")   // arrows, symbols, emoji
        t = t.replace(Regex("\\s*[\\u2013\\u2014]\\s*"), ", ")                          // dashes read as a pause
        t = t.replace(Regex("\\s+"), " ")
        return t.trim()
    }

    private fun split(text: String): List<String> {
        val parts = mutableListOf<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            sb.append(ch)
            val end = ch == '.' || ch == '!' || ch == '?'
            val next = text.getOrNull(i + 1)
            // A full stop inside a number or a time ("7.30", "3.5") does not end a sentence.
            val inNumber = ch == '.' && i > 0 && text[i - 1].isDigit() && next?.isDigit() == true
            if (end && !inNumber && (next == null || next == ' ')) { parts += sb.toString().trim(); sb.clear() }
            i++
        }
        if (sb.isNotBlank()) parts += sb.toString().trim()
        return parts.filter { it.any { c -> c.isLetterOrDigit() } }
    }
}
