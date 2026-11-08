package com.pocketnode.core

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.w3c.dom.Document
import org.w3c.dom.Element

/** Opens the files the writers make and checks them the way an office program would: every part is real XML and every link points at a part that exists. */
class OfficeFilesTest {
    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val parts = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z -> while (true) { val e = z.nextEntry ?: break; parts[e.name] = z.readBytes() } }
        return parts
    }

    private fun xml(bytes: ByteArray): Document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true; setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }.newDocumentBuilder().parse(ByteArrayInputStream(bytes))

    /** Every XML part parses, every relationship target exists, and every override in the content types names a part that is there. */
    private fun assertPackageIsSound(parts: Map<String, ByteArray>) {
        parts.filterKeys { it.endsWith(".xml") || it.endsWith(".rels") }.forEach { (name, b) -> xml(b) /* throws if broken */; assertTrue(b.isNotEmpty(), name) }
        for ((name, b) in parts.filterKeys { it.endsWith(".rels") }) {
            val base = name.substringBeforeLast("_rels/").trimEnd('/')
            val rels = xml(b).getElementsByTagName("Relationship")
            for (i in 0 until rels.length) {
                val r = rels.item(i) as Element
                if (r.getAttribute("TargetMode") == "External") continue
                val target = r.getAttribute("Target")
                val path = if (target.startsWith("/")) target.removePrefix("/") else normalise((if (base.isEmpty()) "" else "$base/") + target)
                assertTrue(path in parts, "$name points at $path, which is not in the file")
            }
        }
        val overrides = xml(parts.getValue("[Content_Types].xml")).getElementsByTagName("Override")
        for (i in 0 until overrides.length) assertTrue((overrides.item(i) as Element).getAttribute("PartName").removePrefix("/") in parts, "an override names a missing part")
    }

    private fun normalise(p: String): String {
        val out = ArrayDeque<String>()
        for (seg in p.split('/')) when (seg) { "", "." -> {}; ".." -> out.removeLastOrNull(); else -> out.addLast(seg) }
        return out.joinToString("/")
    }

    private fun wordText(parts: Map<String, ByteArray>): List<String> {
        val doc = xml(parts.getValue("word/document.xml"))
        val paras = doc.getElementsByTagNameNS("*", "p")
        return (0 until paras.length).map { i -> val p = paras.item(i) as Element; val ts = p.getElementsByTagNameNS("*", "t"); (0 until ts.length).joinToString("") { ts.item(it).textContent } }
    }

    @Test fun `a Word file is a sound package with the title, headings, lists and styled words`() {
        val parts = unzip(Docx.write("Weekend plan", "# Packing\nBring **two** shirts and *one* hat.\n- Phone & charger\n- <Sunscreen>\n1. First\n2. Second\n\n## Notes\nPlain line."))
        assertPackageIsSound(parts)
        assertEquals(setOf("[Content_Types].xml", "_rels/.rels", "docProps/core.xml", "word/_rels/document.xml.rels", "word/styles.xml", "word/document.xml"), parts.keys)
        val text = wordText(parts)
        assertEquals(listOf("Weekend plan", "Packing", "Bring two shirts and one hat.", "•Phone & charger", "•<Sunscreen>", "1.First", "2.Second", "Notes", "Plain line."), text)
        val raw = parts.getValue("word/document.xml").decodeToString()
        assertTrue("""<w:pStyle w:val="Title"/>""" in raw && """w:val="Heading1"""" in raw && """w:val="Heading2"""" in raw)
        assertTrue("<w:b/>" in raw && "<w:i/>" in raw)
        assertEquals(4, Regex("<w:tab/>").findAll(raw).count(), "each list item has a tab after its marker")
        assertTrue("Phone &amp; charger" in raw && "&lt;Sunscreen&gt;" in raw, "special characters are escaped")
    }

    @Test fun `control characters that XML forbids are dropped instead of breaking the file`() {
        val parts = unzip(Docx.write("T", "bad\u0001char and a normal one"))
        assertPackageIsSound(parts)
        assertEquals("badchar and a normal one", wordText(parts).last())
    }

    @Test fun `a Word file with no title still has a body`() {
        val parts = unzip(Docx.write("", "Just a line."))
        assertPackageIsSound(parts)
        assertEquals(listOf("Just a line."), wordText(parts))
    }

    @Test fun `empty or oversized text is refused in plain words`() {
        assertEquals("The document has no text.", assertFailsWith<IllegalArgumentException> { Docx.write("T", "   ") }.message)
        assertTrue(assertFailsWith<IllegalArgumentException> { Docx.write("T", "x".repeat(Docx.MAX_CHARS + 1)) }.message!!.contains("too long"))
    }

    @Test fun `the title goes into the file properties too`() {
        val parts = unzip(Docx.write("Budget & plans", "line"))
        assertEquals("Budget & plans", xml(parts.getValue("docProps/core.xml")).getElementsByTagNameNS("*", "title").item(0).textContent)
        assertFalse(parts.getValue("docProps/core.xml").decodeToString().contains("Budget & plans"), "escaped in the file text")
    }

    private fun slideTexts(parts: Map<String, ByteArray>, n: Int): List<String> {
        val ps = xml(parts.getValue("ppt/slides/slide$n.xml")).getElementsByTagNameNS("*", "p")
        return (0 until ps.length).map { i -> val p = ps.item(i) as Element; val ts = p.getElementsByTagNameNS("*", "t"); (0 until ts.length).joinToString("") { ts.item(it).textContent } }
    }

    @Test fun `a PowerPoint file is a sound package with a cover, one slide per heading and a body`() {
        val parts = unzip(Pptx.write("Trip plan", "# Day one\n- Train & bus\n- <Hotel>\n\n# Day two\n1. Museum\n2. Lunch\nA closing **line**."))
        assertPackageIsSound(parts)
        assertEquals(3, parts.keys.count { Regex("ppt/slides/slide\\d+\\.xml").matches(it) })
        assertEquals(listOf("Trip plan"), slideTexts(parts, 1))
        assertEquals(listOf("Day one", "Train & bus", "<Hotel>"), slideTexts(parts, 2))
        assertEquals(listOf("Day two", "Museum", "Lunch", "A closing line."), slideTexts(parts, 3))
        val raw = parts.getValue("ppt/slides/slide3.xml").decodeToString()
        assertTrue("buAutoNum" in raw && """b="1"""" in raw)
        assertTrue("&amp;" in parts.getValue("ppt/slides/slide2.xml").decodeToString())
        val ids = xml(parts.getValue("ppt/presentation.xml")).getElementsByTagNameNS("*", "sldId")
        assertEquals(3, ids.length)
    }

    @Test fun `a long slide continues on the next one and text with no heading still makes slides`() {
        val many = (1..9).joinToString("\n") { "- point $it" }
        val parts = unzip(Pptx.write("", "# Big list\n$many"))
        assertPackageIsSound(parts)
        assertEquals(listOf("Big list", "point 1", "point 2", "point 3", "point 4", "point 5", "point 6", "point 7"), slideTexts(parts, 1))
        assertEquals(listOf("Big list (continued)", "point 8", "point 9"), slideTexts(parts, 2))
        val plain = unzip(Pptx.write("Notes", "Just a line."))
        assertPackageIsSound(plain)
        assertEquals(listOf("Notes", "Notes", "Just a line."), slideTexts(plain, 1) + slideTexts(plain, 2))
    }

    @Test fun `a presentation refuses empty, oversized and too many slides`() {
        assertEquals("The presentation has no text.", assertFailsWith<IllegalArgumentException> { Pptx.write("T", " ") }.message)
        assertTrue(assertFailsWith<IllegalArgumentException> { Pptx.write("T", "x".repeat(Pptx.MAX_CHARS + 1)) }.message!!.contains("too long"))
        assertTrue(assertFailsWith<IllegalArgumentException> { Pptx.write("T", (1..Pptx.MAX_SLIDES + 1).joinToString("\n") { "# s$it" }) }.message!!.contains("slides"))
    }
}
