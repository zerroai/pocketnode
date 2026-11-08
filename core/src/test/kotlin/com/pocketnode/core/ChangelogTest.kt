package com.pocketnode.core

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ChangelogTest {
    @Test fun `every version has items and the text follows the house style`() {
        assertTrue(Changelog.ENTRIES.isNotEmpty() && Changelog.ENTRIES.all { it.items.isNotEmpty() })
        val t = Changelog.text()
        assertTrue('—' !in t && '–' !in t, "no dashes used as punctuation")
    }

    @Test fun `the version the app reports has an entry`() {
        val gradle = java.io.File("../app/build.gradle.kts").takeIf { it.exists() }?.readText() ?: return
        val v = Regex("versionName = \"([^\"]+)\"").find(gradle)!!.groupValues[1]
        assertNotNull(Changelog.forVersion(v), "write a Changelog entry for $v")
    }
}
