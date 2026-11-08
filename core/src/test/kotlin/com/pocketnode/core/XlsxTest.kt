package com.pocketnode.core

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class XlsxTest {
    private fun unzip(bytes: ByteArray): Map<String, String> {
        val out = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) { val e = z.nextEntry ?: break; out[e.name] = z.readBytes().toString(Charsets.UTF_8) }
        }
        return out
    }

    private fun wellFormed(xml: String) { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream()) }

    @Test
    fun `a workbook has every required part and each part is well formed XML`() {
        val parts = unzip(Xlsx.write(listOf(listOf("Name", "Amount"), listOf("Ada", "3200.50"), listOf("Bo & Co <x>", "-12"))))
        assertEquals(setOf("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/_rels/workbook.xml.rels", "xl/styles.xml", "xl/worksheets/sheet1.xml"), parts.keys)
        parts.values.forEach(::wellFormed)
        val sheet = parts["xl/worksheets/sheet1.xml"]!!
        assertTrue("""<c r="B2"><v>3200.50</v></c>""" in sheet, "numbers are stored as numbers")
        assertTrue("""<c r="B3"><v>-12</v></c>""" in sheet)
        assertTrue("Bo &amp; Co &lt;x&gt;" in sheet, "text is escaped")
        assertTrue("""<c r="A1" s="1" t="inlineStr">""" in sheet, "the header row is styled")
    }

    @Test
    fun `the header row stays text even when it looks like a number, and odd numbers stay text`() {
        val sheet = unzip(Xlsx.write(listOf(listOf("2024", "Code"), listOf("007", "1e5"), listOf("1,000", "3.")))).getValue("xl/worksheets/sheet1.xml")
        assertTrue("""<c r="A1" s="1" t="inlineStr">""" in sheet)
        assertTrue("""<c r="A2" t="inlineStr">""" in sheet, "a leading zero is kept as text")
        assertTrue("""<c r="B2" t="inlineStr">""" in sheet)
        assertTrue("""<c r="A3" t="inlineStr">""" in sheet)
    }

    @Test
    fun `column letters and sheet names`() {
        assertEquals("A", Xlsx.column(0)); assertEquals("Z", Xlsx.column(25)); assertEquals("AA", Xlsx.column(26)); assertEquals("AZ", Xlsx.column(51)); assertEquals("BA", Xlsx.column(52))
        assertEquals("Q1 Sales x", Xlsx.cleanSheetName("Q1: Sales/[x]?"))
        assertEquals(31, Xlsx.cleanSheetName("x".repeat(80)).length)
        assertEquals("Sheet1", Xlsx.cleanSheetName("  "))
    }

    @Test
    fun `control characters are dropped so Excel will open the file`() {
        val parts = unzip(Xlsx.write(listOf(listOf("a\u0000b\u0008c\nd"))))
        wellFormed(parts.getValue("xl/worksheets/sheet1.xml"))
        assertTrue("abc\nd" in parts.getValue("xl/worksheets/sheet1.xml"))
    }

    @Test
    fun `limits are enforced`() {
        assertTrue(runCatching { Xlsx.write(emptyList()) }.isFailure)
        assertTrue(runCatching { Xlsx.write(List(Xlsx.MAX_ROWS + 1) { listOf("x") }) }.isFailure)
        assertTrue(runCatching { Xlsx.write(listOf(List(Xlsx.MAX_COLUMNS + 1) { "x" })) }.isFailure)
    }

    @Test
    fun `csv quotes what needs quoting`() {
        assertEquals("a,\"b,c\",\"say \"\"hi\"\"\"\r\n1,2,3\r\n", Csv.write(listOf(listOf("a", "b,c", "say \"hi\""), listOf("1", "2", "3"))))
        assertEquals("\"two\nlines\"\r\n", Csv.write(listOf(listOf("two\nlines"))))
    }

    @Test
    fun `a total is a real formula with the right cached value`() {
        val sheet = unzip(Xlsx.write(listOf(listOf("Category", "Amount"), listOf("Rent", "900"), listOf("Food", "350"), listOf("Transport", "120"), listOf("Phone", "40"), listOf("Savings", "200"), listOf("Total", "=SUM(B2:B6)")))).getValue("xl/worksheets/sheet1.xml")
        assertTrue("""<c r="B7"><f>SUM(B2:B6)</f><v>1610</v></c>""" in sheet, sheet)
        wellFormed(sheet)
        assertTrue("fullCalcOnLoad" in unzip(Xlsx.write(listOf(listOf("a")))).getValue("xl/workbook.xml"))
    }

    @Test
    fun `formulas calculate, chain, and fail safely`() {
        val g = Formulas.Grid(listOf(listOf("x", "y"), listOf("10", "4"), listOf("=A2*B2", "=A2/B2"), listOf("=SUM(A2:B3)", "=ROUND(B3,0)"), listOf("=AVERAGE(A2:A3)", "=MAX(A2:B2)-MIN(A2:B2)")))
        assertEquals(40.0, g.valueOf("A3")); assertEquals(2.5, g.valueOf("B3"))
        assertEquals(56.5, g.valueOf("A4")); assertEquals(3.0, g.valueOf("B4")) // 2.5 rounds half up
        assertEquals(25.0, g.valueOf("A5")); assertEquals(6.0, g.valueOf("B5"))
        assertEquals(null, Formulas.Grid(listOf(listOf("=1/0"))).valueOf("A1"), "division by zero has no value")
        assertEquals(null, Formulas.Grid(listOf(listOf("=A1+1"))).valueOf("A1"), "a cell that refers to itself has no value")
        assertEquals("-3", Formulas.show(-3.0)); assertEquals("0.1", Formulas.show(0.1)); assertEquals("2.5", Formulas.show(2.5))
    }

    @Test
    fun `formulas that could do more than calculate are refused`() {
        for (bad in listOf("=HYPERLINK(\"http://x\",\"a\")", "=WEBSERVICE(A1)", "=cmd|' /c calc'!A1", "=SUM(A1:A2)+EXEC(B1)", "=@SUM(A1)", "=1;2", "=" + "1+".repeat(200) + "1")) {
            assertTrue(runCatching { Xlsx.write(listOf(listOf("h"), listOf(bad))) }.isFailure, "should refuse $bad")
        }
        assertTrue(runCatching { Xlsx.write(listOf(listOf("h"), listOf("=sum(A1:A1)+(2*3)"))) }.isSuccess, "lower case and brackets are fine")
    }
}
