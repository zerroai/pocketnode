package com.pocketnode.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The app uses only our own icons (`brand/icons`, copied to `res/drawable/ic_mc_*.xml`, drawn through `Glyph`) and our own mark
 * (`ic_launcher_*`, `ic_splash`, `ic_stat_mark`). No stock Android icons, no Material icon set, no emoji or symbol characters as icons.
 * See `docs/design/ui-guidelines.md`, "Icons and brand".
 */
class BrandGuardTest {
    private val sources get() = File("../app/src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `no stock Android or Material icons in the app`() {
        val banned = listOf("android.R.drawable", "androidx.compose.material.icons", "Icons.Default", "Icons.Filled", "Icons.Outlined", "Icons.Rounded")
        val hits = sources.flatMap { f -> f.readLines().mapIndexedNotNull { i, l -> banned.firstOrNull { l.contains(it) && !l.trimStart().startsWith("//") }?.let { "${f.path}:${i + 1} uses $it" } } }
        assertTrue(hits.isEmpty(), "Use a brand icon (Glyph, or R.drawable.ic_stat_mark in notifications):\n" + hits.joinToString("\n"))
    }

    @Test
    fun `every drawable is a brand icon or the mark`() {
        val allowed = Regex("ic_mc_[a-z0-9_]+|ic_launcher_foreground|ic_launcher_monochrome|ic_splash|ic_stat_mark|widget_background")
        val bad = File("../app/src/main/res/drawable").listFiles().orEmpty().map { it.nameWithoutExtension }.filterNot { allowed.matches(it) }
        assertTrue(bad.isEmpty(), "Drawables that are not from brand/: $bad")
    }

    @Test
    fun `symbols are not used as icons in text`() {
        val symbols = Regex("\"[✓✕✗›‹→←▶⚠★☆]\"")
        val hits = sources.flatMap { f -> f.readLines().mapIndexedNotNull { i, l -> if (symbols.containsMatchIn(l)) "${f.path}:${i + 1}" else null } }
        assertTrue(hits.isEmpty(), "Use a Glyph instead of a symbol character:\n" + hits.joinToString("\n"))
    }
}
