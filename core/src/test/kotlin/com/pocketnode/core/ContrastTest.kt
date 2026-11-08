package com.pocketnode.core

import java.io.File
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

/** Reads the two colour palettes from the app's theme file and checks that text and accent colours are readable (WCAG AA, 4.5 to 1). */
class ContrastTest {
    private val theme = File("../app/src/main/kotlin/com/pocketnode/app/ui/Theme.kt").readText()

    private fun palette(name: String): Map<String, String> {
        val block = theme.substringAfter("val $name = ").substringBefore("\n)\n")
        return Regex("(\\w+) = Color\\(0xFF([0-9A-Fa-f]{6})\\)").findAll(block).associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun luminance(hex: String): Double {
        val c = (0..2).map { hex.substring(it * 2, it * 2 + 2).toInt(16) / 255.0 }.map { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]
    }

    private fun ratio(a: String, b: String): Double { val la = luminance(a); val lb = luminance(b); return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05) }

    @Test fun `text and accent colours are readable on every background in both themes`() {
        val names = listOf("LightPalette", "DarkPalette")
        assertTrue(names.size >= 2, "found the palettes: $names")
        for (n in names.take(2)) {
            val p = palette(n)
            assertTrue(p.size >= 10, "palette $n parsed: ${p.keys}")
            for (fg in listOf("text", "textSecondary", "textTertiary", "accent", "success", "error")) for (bg in listOf("background", "grouped", "surface", "fill")) {
                val r = ratio(p.getValue(fg), p.getValue(bg))
                assertTrue(r >= 4.5, "$n: $fg on $bg is %.2f to 1, under 4.5".format(r))
            }
        }
    }
}
