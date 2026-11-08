package com.pocketnode.core

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes a one-sheet Excel workbook (.xlsx) from rows of text. Written from the file format's published structure, no library.
 * The first row is a bold header. Cells that look like plain numbers are stored as numbers so formulas and sorting work.
 */
object Xlsx {
    const val MAX_ROWS = 5_000
    const val MAX_COLUMNS = 50
    const val MAX_CELL = 2_000

    fun write(rows: List<List<String>>, sheetName: String = "Sheet1"): ByteArray {
        require(rows.isNotEmpty()) { "The spreadsheet has no rows." }
        require(rows.size <= MAX_ROWS) { "A spreadsheet can have up to $MAX_ROWS rows." }
        require(rows.all { it.size <= MAX_COLUMNS }) { "A spreadsheet can have up to $MAX_COLUMNS columns." }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun part(name: String, body: String) { zip.putNextEntry(ZipEntry(name)); zip.write(body.toByteArray(Charsets.UTF_8)); zip.closeEntry() }
            part("[Content_Types].xml", CONTENT_TYPES)
            part("_rels/.rels", ROOT_RELS)
            part("xl/workbook.xml", workbook(cleanSheetName(sheetName)))
            part("xl/_rels/workbook.xml.rels", WORKBOOK_RELS)
            part("xl/styles.xml", STYLES)
            part("xl/worksheets/sheet1.xml", sheet(rows))
        }
        return out.toByteArray()
    }

    /** Excel sheet names are at most 31 characters and cannot hold : \ / ? * [ ]. */
    fun cleanSheetName(raw: String): String = raw.replace(Regex("[:\\\\/?*\\[\\]]"), " ").replace(Regex("\\s+"), " ").trim().take(31).ifBlank { "Sheet1" }

    /** Column letters for a zero-based index: 0 is A, 25 is Z, 26 is AA. */
    fun column(index: Int): String {
        var n = index; val sb = StringBuilder()
        do { sb.append('A' + n % 26); n = n / 26 - 1 } while (n >= 0)
        return sb.reverse().toString()
    }

    private val NUMBER = Regex("^-?(0|[1-9]\\d{0,14})(\\.\\d{1,10})?$")

    private fun sheet(rows: List<List<String>>): String {
        val values = Formulas.Grid(rows)
        val sb = StringBuilder("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
        val widest = rows.maxOf { it.size }.coerceAtLeast(1)
        sb.append("<cols>")
        for (c in 0 until widest) {
            val longest = rows.take(200).maxOf { (it.getOrNull(c) ?: "").lineSequence().maxOfOrNull { l -> l.length } ?: 0 }
            sb.append("""<col min="${c + 1}" max="${c + 1}" width="${(longest + 2).coerceIn(8, 60)}" customWidth="1"/>""")
        }
        sb.append("</cols><sheetData>")
        rows.forEachIndexed { r, row ->
            sb.append("""<row r="${r + 1}">""")
            row.forEachIndexed { c, raw ->
                val text = raw.take(MAX_CELL)
                val ref = column(c) + (r + 1)
                val style = if (r == 0) """ s="1"""" else ""
                when {
                    text.isEmpty() -> {}
                    text.startsWith("=") -> sb.append(formulaCell(ref, style, text, values))
                    r > 0 && NUMBER.matches(text.trim()) -> sb.append("""<c r="$ref"$style><v>${text.trim()}</v></c>""")
                    else -> sb.append("""<c r="$ref"$style t="inlineStr"><is><t xml:space="preserve">${escape(text)}</t></is></c>""")
                }
            }
            sb.append("</row>")
        }
        sb.append("</sheetData></worksheet>")
        return sb.toString()
    }

    /** A formula cell. The phone works out the value as well so viewers that do not recalculate still show the right number. */
    private fun formulaCell(ref: String, style: String, text: String, grid: Formulas.Grid): String {
        val formula = Formulas.check(text.drop(1))
        val value = grid.valueOf(ref)
        return """<c r="$ref"$style><f>${escape(formula)}</f>${if (value != null) "<v>${Formulas.show(value)}</v>" else ""}</c>"""
    }

    /** Escapes text for XML and drops the control characters XML 1.0 cannot hold. */
    fun escape(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (ch in s) when {
            ch == '&' -> sb.append("&amp;")
            ch == '<' -> sb.append("&lt;")
            ch == '>' -> sb.append("&gt;")
            ch == '"' -> sb.append("&quot;")
            ch == '\n' || ch == '\t' -> sb.append(ch)
            ch < ' ' || ch == '\u007f' || ch == '￾' || ch == '￿' -> {}
            else -> sb.append(ch)
        }
        return sb.toString()
    }

    private fun workbook(name: String) =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="${escape(name)}" sheetId="1" r:id="rId1"/></sheets><calcPr fullCalcOnLoad="1"/></workbook>"""

    private const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>"""
    private const val ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>"""
    private const val WORKBOOK_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>"""
    private const val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts><fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/></cellXfs></styleSheet>"""
}

/** Plain CSV text from rows, quoted where a cell holds a comma, quote or line break. */
object Csv {
    fun write(rows: List<List<String>>): String = rows.joinToString("\r\n", postfix = "\r\n") { row ->
        row.joinToString(",") { cell ->
            val c = cell.replace("\r\n", "\n")
            if (c.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + c.replace("\"", "\"\"") + "\"" else c
        }
    }
}

/**
 * The small set of spreadsheet formulas the assistant may write: + - * / ( ), cell references like B2, ranges like B2:B6, and
 * SUM, AVERAGE, MIN, MAX, COUNT, ROUND, ABS. Anything else (links, external data, macros) is refused, so a formula can only calculate.
 */
object Formulas {
    private val FUNCTIONS = setOf("SUM", "AVERAGE", "MIN", "MAX", "COUNT", "ROUND", "ABS")
    private val CHARS = Regex("^[A-Za-z0-9 :,.+\\-*/()$]+$")
    private val NAME = Regex("[A-Za-z]+(?=\\s*\\()")

    /** Returns the formula (without the leading =) if it is allowed, otherwise throws. */
    fun check(formula: String): String {
        val f = formula.trim()
        require(f.isNotEmpty() && f.length <= 300 && CHARS.matches(f)) { "That formula is not allowed. Use numbers, cell references, + - * /, and SUM, AVERAGE, MIN, MAX, COUNT, ROUND or ABS." }
        NAME.findAll(f).forEach { require(it.value.uppercase() in FUNCTIONS) { "The function ${it.value} is not allowed. Use SUM, AVERAGE, MIN, MAX, COUNT, ROUND or ABS." } }
        return f.replace("$", "")
    }

    fun show(v: Double): String = if (v == Math.rint(v) && Math.abs(v) < 1e15) v.toLong().toString() else java.math.BigDecimal(v).round(java.math.MathContext(15)).stripTrailingZeros().toPlainString()

    class Grid(private val rows: List<List<String>>) {
        private val cache = HashMap<String, Double?>()
        private val busy = HashSet<String>()

        /** The number in a cell, or the result of its formula. Null if it is empty, text, or cannot be worked out. */
        fun valueOf(ref: String): Double? {
            val key = ref.uppercase()
            if (key in cache) return cache[key]
            if (!busy.add(key)) return null // a formula that refers back to itself
            val result = try { compute(key) } catch (e: Exception) { null } finally { busy.remove(key) }
            cache[key] = result
            return result
        }

        private fun compute(ref: String): Double? {
            val m = Regex("^([A-Z]+)(\\d+)$").matchEntire(ref) ?: return null
            var col = 0; for (ch in m.groupValues[1]) col = col * 26 + (ch - 'A' + 1)
            val cell = rows.getOrNull(m.groupValues[2].toInt() - 1)?.getOrNull(col - 1)?.trim() ?: return null
            return if (cell.startsWith("=")) Parser(check(cell.drop(1)), this).run() else cell.takeIf { NUMBER_TEXT.matches(it) }?.toDouble()
        }
    }

    private val NUMBER_TEXT = Regex("^-?(0|[1-9]\\d{0,14})(\\.\\d{1,10})?$")

    private class Parser(val src: String, val grid: Grid) {
        var i = 0
        fun run(): Double? { val v = expr(); skip(); require(i == src.length) { "unexpected text" }; return v }
        private fun skip() { while (i < src.length && src[i] == ' ') i++ }
        private fun peek(): Char { skip(); return if (i < src.length) src[i] else '\u0000' }
        private fun expr(): Double? {
            var v = term()
            while (true) { val c = peek(); if (c != '+' && c != '-') return v; i++; val r = term(); v = if (v == null || r == null) null else if (c == '+') v + r else v - r }
        }
        private fun term(): Double? {
            var v = factor()
            while (true) {
                val c = peek(); if (c != '*' && c != '/') return v; i++; val r = factor()
                v = if (v == null || r == null) null else if (c == '*') v * r else { if (r == 0.0) null else v / r }
            }
        }
        private fun factor(): Double? {
            val c = peek()
            if (c == '-') { i++; return factor()?.let { -it } }
            if (c == '(') { i++; val v = expr(); require(peek() == ')'); i++; return v }
            if (c.isDigit() || c == '.') { val s = i; while (i < src.length && (src[i].isDigit() || src[i] == '.')) i++; return src.substring(s, i).toDouble() }
            require(c.isLetter()) { "unexpected character" }
            val s = i; while (i < src.length && src[i].isLetter()) i++
            val word = src.substring(s, i).uppercase()
            if (peek() == '(') { i++; val args = args(); require(peek() == ')'); i++; return call(word, args) }
            val s2 = i; while (i < src.length && src[i].isDigit()) i++
            return grid.valueOf(word + src.substring(s2, i))
        }
        /** Arguments: each is a number-valued expression or a range, flattened to a list of values (null for empty or text cells). */
        private fun args(): List<Double?> {
            val out = mutableListOf<Double?>()
            if (peek() == ')') return out
            while (true) {
                skip(); val save = i
                val range = Regex("^\\$?([A-Za-z]+)\\$?(\\d+):\\$?([A-Za-z]+)\\$?(\\d+)").find(src.substring(i))
                if (range != null) { i += range.value.length; out += cells(range) } else { i = save; out += expr() }
                if (peek() == ',') { i++; continue }
                return out
            }
        }
        private fun cells(m: MatchResult): List<Double?> {
            fun col(s: String): Int { var n = 0; for (ch in s.uppercase()) n = n * 26 + (ch - 'A' + 1); return n }
            val c1 = col(m.groupValues[1]); val c2 = col(m.groupValues[3]); val r1 = m.groupValues[2].toInt(); val r2 = m.groupValues[4].toInt()
            require((Math.abs(c2 - c1) + 1) * (Math.abs(r2 - r1) + 1) <= 10_000) { "range too large" }
            val out = mutableListOf<Double?>()
            for (r in minOf(r1, r2)..maxOf(r1, r2)) for (c in minOf(c1, c2)..maxOf(c1, c2)) out += grid.valueOf(Xlsx.column(c - 1) + r)
            return out
        }
        private fun call(name: String, a: List<Double?>): Double? {
            val nums = a.filterNotNull()
            return when (name) {
                "SUM" -> nums.sum()
                "COUNT" -> nums.size.toDouble()
                "AVERAGE" -> if (nums.isEmpty()) null else nums.average()
                "MIN" -> nums.minOrNull()
                "MAX" -> nums.maxOrNull()
                "ABS" -> if (a.size == 1) a[0]?.let { Math.abs(it) } else null
                "ROUND" -> if (a.size == 2 && a[0] != null && a[1] != null) java.math.BigDecimal(a[0]!!).setScale(a[1]!!.toInt(), java.math.RoundingMode.HALF_UP).toDouble() else null
                else -> null
            }
        }
    }
}
