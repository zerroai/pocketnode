package com.pocketnode.core

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes a Word document (.docx) from a title and light Markdown text. Written from the file format's published structure, no
 * library. Headings, bullets, numbered lists, bold and italic come through; the page is A4 with ordinary margins.
 */
object Docx {
    const val MAX_CHARS = 200_000

    fun write(title: String, text: String): ByteArray {
        require(text.isNotBlank()) { "The document has no text." }
        require(text.length <= MAX_CHARS) { "That is too long for one document. Split it up." }
        val blocks = OfficeText.blocks(text)
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun part(name: String, body: String) { zip.putNextEntry(ZipEntry(name)); zip.write(body.toByteArray(Charsets.UTF_8)); zip.closeEntry() }
            part("[Content_Types].xml", CONTENT_TYPES)
            part("_rels/.rels", ROOT_RELS)
            part("docProps/core.xml", core(title))
            part("word/_rels/document.xml.rels", DOC_RELS)
            part("word/styles.xml", STYLES)
            part("word/document.xml", document(title, blocks))
        }
        return out.toByteArray()
    }

    private fun runs(runs: List<Seg>, extra: String = ""): String = runs.joinToString("") { s ->
        val props = buildString {
            if (s.code) append("""<w:rFonts w:ascii="Courier New" w:hAnsi="Courier New" w:cs="Courier New"/>""")
            if (s.bold) append("<w:b/>")
            if (s.italic) append("<w:i/>")
            append(extra)
        }
        "<w:r>${if (props.isEmpty()) "" else "<w:rPr>$props</w:rPr>"}<w:t xml:space=\"preserve\">${OfficeText.xml(s.text)}</w:t></w:r>"
    }

    private fun document(title: String, blocks: List<OfficeText.Block>): String {
        val body = StringBuilder()
        if (title.isNotBlank()) body.append("""<w:p><w:pPr><w:pStyle w:val="Title"/></w:pPr><w:r><w:t xml:space="preserve">${OfficeText.xml(title.trim().take(200))}</w:t></w:r></w:p>""")
        for (b in blocks) when (b) {
            is OfficeText.Block.Heading -> body.append("""<w:p><w:pPr><w:pStyle w:val="Heading${b.level}"/></w:pPr>${runs(b.runs)}</w:p>""")
            is OfficeText.Block.Para -> body.append("<w:p>${runs(b.runs)}</w:p>")
            is OfficeText.Block.Item -> body.append("""<w:p><w:pPr><w:pStyle w:val="ListParagraph"/></w:pPr><w:r><w:t xml:space="preserve">${OfficeText.xml(b.marker)}</w:t></w:r><w:r><w:tab/></w:r>${runs(b.runs)}</w:p>""")
        }
        // An empty body is not valid for Word, so a blank paragraph stands in when there is nothing else.
        if (body.isEmpty()) body.append("<w:p/>")
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$body<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1134" w:right="1134" w:bottom="1134" w:left="1134" w:header="708" w:footer="708" w:gutter="0"/></w:sectPr></w:body></w:document>"""
    }

    private fun core(title: String) = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"><dc:title>${OfficeText.xml(title.trim().take(200))}</dc:title><dc:creator>Pocketnode</dc:creator></cp:coreProperties>"""

    private const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/><Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/></Types>"""

    private const val ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/></Relationships>"""

    private const val DOC_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>"""

    private const val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:eastAsia="Calibri" w:cs="Calibri"/><w:sz w:val="22"/><w:szCs w:val="22"/><w:lang w:val="en-GB"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after="120" w:line="276" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults><w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:qFormat/></w:style><w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/><w:pPr><w:spacing w:after="240"/></w:pPr><w:rPr><w:b/><w:sz w:val="48"/><w:szCs w:val="48"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/><w:pPr><w:keepNext/><w:spacing w:before="240" w:after="120"/><w:outlineLvl w:val="0"/></w:pPr><w:rPr><w:b/><w:sz w:val="32"/><w:szCs w:val="32"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/><w:pPr><w:keepNext/><w:spacing w:before="200" w:after="80"/><w:outlineLvl w:val="1"/></w:pPr><w:rPr><w:b/><w:sz w:val="26"/><w:szCs w:val="26"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="ListParagraph"><w:name w:val="List Paragraph"/><w:basedOn w:val="Normal"/><w:qFormat/><w:pPr><w:spacing w:after="60"/><w:ind w:left="720" w:hanging="360"/></w:pPr></w:style></w:styles>"""
}
