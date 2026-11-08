package com.pocketnode.core

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes a PowerPoint deck (.pptx) from light Markdown text, written from the file format's published structure, no library.
 * A line `# Heading` starts a slide; bullets, numbered lines and paragraphs under it are that slide's body. A title, when given,
 * becomes the first slide. A slide with more than [PER_SLIDE] lines continues on the next one. The slides are 16:9, plain and
 * dark on white, so they read well on a phone and print cleanly.
 */
object Pptx {
    const val MAX_CHARS = 100_000
    const val MAX_SLIDES = 60
    const val PER_SLIDE = 7

    private class Slide(val title: List<Seg>, val body: List<OfficeText.Block>, val cover: Boolean)

    fun write(title: String, text: String): ByteArray {
        require(text.isNotBlank()) { "The presentation has no text." }
        require(text.length <= MAX_CHARS) { "That is too long for one presentation. Split it up." }
        val slides = layout(title.trim().take(200), OfficeText.blocks(text))
        require(slides.size <= MAX_SLIDES) { "That would be more than $MAX_SLIDES slides. Split it up." }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun part(name: String, body: String) { zip.putNextEntry(ZipEntry(name)); zip.write(body.toByteArray(Charsets.UTF_8)); zip.closeEntry() }
            part("[Content_Types].xml", contentTypes(slides.size))
            part("_rels/.rels", ROOT_RELS)
            part("docProps/core.xml", core(title.ifBlank { OfficeText.plain(slides.first().title) }))
            part("ppt/presentation.xml", presentation(slides.size))
            part("ppt/_rels/presentation.xml.rels", presentationRels(slides.size))
            part("ppt/slideMasters/slideMaster1.xml", MASTER)
            part("ppt/slideMasters/_rels/slideMaster1.xml.rels", MASTER_RELS)
            part("ppt/slideLayouts/slideLayout1.xml", LAYOUT)
            part("ppt/slideLayouts/_rels/slideLayout1.xml.rels", LAYOUT_RELS)
            part("ppt/theme/theme1.xml", THEME)
            slides.forEachIndexed { i, s ->
                part("ppt/slides/slide${i + 1}.xml", slideXml(s))
                part("ppt/slides/_rels/slide${i + 1}.xml.rels", SLIDE_RELS)
            }
        }
        return out.toByteArray()
    }

    /** Turns the blocks into slides: a heading opens one, the rest fills it, and long ones continue. */
    private fun layout(title: String, blocks: List<OfficeText.Block>): List<Slide> {
        val slides = mutableListOf<Slide>()
        if (title.isNotEmpty()) slides += Slide(listOf(Seg(title, bold = true)), emptyList(), cover = true)
        var head: List<Seg>? = null
        var body = mutableListOf<OfficeText.Block>()
        var started = false
        fun flush() {
            if (!started) return
            val h = head ?: listOf(Seg(title.ifEmpty { "Slides" }, bold = true))
            val chunks = body.chunked(PER_SLIDE).ifEmpty { listOf(emptyList()) }
            chunks.forEachIndexed { i, c -> slides += Slide(if (i == 0) h else h + Seg(" (continued)"), c, cover = false) }
        }
        for (b in blocks) {
            if (b is OfficeText.Block.Heading && b.level == 1) { flush(); head = b.runs; body = mutableListOf(); started = true }
            else { started = true; body += b }
        }
        flush()
        return slides
    }

    private fun runs(runs: List<Seg>, size: Int, forceBold: Boolean = false): String = runs.joinToString("") { s ->
        val bold = if (s.bold || forceBold) " b=\"1\"" else ""
        val italic = if (s.italic) " i=\"1\"" else ""
        val font = if (s.code) "<a:latin typeface=\"Courier New\"/>" else ""
        "<a:r><a:rPr lang=\"en-GB\" sz=\"$size\"$bold$italic dirty=\"0\"><a:solidFill><a:srgbClr val=\"1F2937\"/></a:solidFill>$font</a:rPr><a:t>${OfficeText.xml(s.text)}</a:t></a:r>"
    }

    private fun box(id: Int, name: String, x: Int, y: Int, w: Int, h: Int, paragraphs: String, anchor: String = "t") =
        """<p:sp><p:nvSpPr><p:cNvPr id="$id" name="$name"/><p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x="$x" y="$y"/><a:ext cx="$w" cy="$h"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:noFill/></p:spPr><p:txBody><a:bodyPr wrap="square" lIns="91440" tIns="45720" rIns="91440" bIns="45720" anchor="$anchor"><a:normAutofit/></a:bodyPr><a:lstStyle/>$paragraphs</p:txBody></p:sp>"""

    private fun slideXml(s: Slide): String {
        val shapes = StringBuilder()
        if (s.cover) {
            shapes.append(box(2, "Title", 914400, 2057400, 10363200, 1371600, """<a:p><a:pPr algn="ctr"/>${runs(s.title, 4400, forceBold = true)}</a:p>""", anchor = "ctr"))
        } else {
            shapes.append(box(2, "Title", 609600, 365760, 10972800, 1005840, "<a:p>${runs(s.title, 3200, forceBold = true)}</a:p>", anchor = "ctr"))
            val size = when { s.body.size > 6 -> 2000; s.body.size > 4 -> 2200; else -> 2400 }
            val paras = StringBuilder()
            for (b in s.body) when (b) {
                is OfficeText.Block.Item -> {
                    val bullet = if (b.marker == "•") """<a:buFont typeface="Arial"/><a:buChar char="&#8226;"/>""" else """<a:buFont typeface="+mj-lt"/><a:buAutoNum type="arabicPeriod" startAt="${b.marker.trimEnd('.').toIntOrNull() ?: 1}"/>"""
                    paras.append("""<a:p><a:pPr marL="342900" indent="-342900"><a:spcBef><a:spcPts val="600"/></a:spcBef>$bullet</a:pPr>${runs(b.runs, size)}</a:p>""")
                }
                is OfficeText.Block.Heading -> paras.append("""<a:p><a:pPr marL="0" indent="0"><a:spcBef><a:spcPts val="900"/></a:spcBef><a:buNone/></a:pPr>${runs(b.runs, size, forceBold = true)}</a:p>""")
                is OfficeText.Block.Para -> paras.append("""<a:p><a:pPr marL="0" indent="0"><a:spcBef><a:spcPts val="600"/></a:spcBef><a:buNone/></a:pPr>${runs(b.runs, size)}</a:p>""")
            }
            if (paras.isEmpty()) paras.append("<a:p><a:endParaRPr lang=\"en-GB\"/></a:p>")
            shapes.append(box(3, "Body", 609600, 1554480, 10972800, 4572000, paras.toString()))
        }
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"><p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>$shapes</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>"""
    }

    private fun presentation(n: Int): String {
        val ids = (1..n).joinToString("") { """<p:sldId id="${255 + it}" r:id="rId${it + 1}"/>""" }
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:presentation xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"><p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rId1"/></p:sldMasterIdLst><p:sldIdLst>$ids</p:sldIdLst><p:sldSz cx="12192000" cy="6858000"/><p:notesSz cx="6858000" cy="9144000"/></p:presentation>"""
    }

    private fun presentationRels(n: Int): String {
        val slides = (1..n).joinToString("") { """<Relationship Id="rId${it + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide$it.xml"/>""" }
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster" Target="slideMasters/slideMaster1.xml"/>$slides<Relationship Id="rId${n + 2}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme" Target="theme/theme1.xml"/></Relationships>"""
    }

    private fun contentTypes(n: Int): String {
        val slides = (1..n).joinToString("") { """<Override PartName="/ppt/slides/slide$it.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>""" }
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/><Override PartName="/ppt/slideMasters/slideMaster1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml"/><Override PartName="/ppt/slideLayouts/slideLayout1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml"/><Override PartName="/ppt/theme/theme1.xml" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/><Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>$slides</Types>"""
    }

    private fun core(title: String) = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"><dc:title>${OfficeText.xml(title.trim().take(200))}</dc:title><dc:creator>Pocketnode</dc:creator></cp:coreProperties>"""

    private const val ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="ppt/presentation.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/></Relationships>"""

    private const val SLIDE_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout1.xml"/></Relationships>"""

    private const val LAYOUT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster" Target="../slideMasters/slideMaster1.xml"/></Relationships>"""

    private const val MASTER_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme" Target="../theme/theme1.xml"/></Relationships>"""

    private const val TREE = """<p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr></p:spTree>"""

    private const val NS = """xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main""""

    private const val MASTER = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sldMaster $NS><p:cSld><p:bg><p:bgPr><a:solidFill><a:srgbClr val="FFFFFF"/></a:solidFill><a:effectLst/></p:bgPr></p:bg>$TREE</p:cSld><p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink"/><p:sldLayoutIdLst><p:sldLayoutId id="2147483649" r:id="rId1"/></p:sldLayoutIdLst></p:sldMaster>"""

    private const val LAYOUT = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sldLayout $NS type="blank" preserve="1"><p:cSld name="Blank">$TREE</p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>"""

    private const val THEME = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" name="Pocketnode"><a:themeElements><a:clrScheme name="Plain"><a:dk1><a:srgbClr val="1F2937"/></a:dk1><a:lt1><a:srgbClr val="FFFFFF"/></a:lt1><a:dk2><a:srgbClr val="374151"/></a:dk2><a:lt2><a:srgbClr val="F3F4F6"/></a:lt2><a:accent1><a:srgbClr val="2563EB"/></a:accent1><a:accent2><a:srgbClr val="059669"/></a:accent2><a:accent3><a:srgbClr val="D97706"/></a:accent3><a:accent4><a:srgbClr val="7C3AED"/></a:accent4><a:accent5><a:srgbClr val="DC2626"/></a:accent5><a:accent6><a:srgbClr val="0891B2"/></a:accent6><a:hlink><a:srgbClr val="2563EB"/></a:hlink><a:folHlink><a:srgbClr val="7C3AED"/></a:folHlink></a:clrScheme><a:fontScheme name="Plain"><a:majorFont><a:latin typeface="Calibri"/><a:ea typeface=""/><a:cs typeface=""/></a:majorFont><a:minorFont><a:latin typeface="Calibri"/><a:ea typeface=""/><a:cs typeface=""/></a:minorFont></a:fontScheme><a:fmtScheme name="Plain"><a:fillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:fillStyleLst><a:lnStyleLst><a:ln w="9525"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln><a:ln w="19050"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln><a:ln w="28575"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln></a:lnStyleLst><a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst><a:bgFillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:bgFillStyleLst></a:fmtScheme></a:themeElements></a:theme>"""
}
