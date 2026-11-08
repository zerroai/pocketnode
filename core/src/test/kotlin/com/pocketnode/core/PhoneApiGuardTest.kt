package com.pocketnode.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `:core` is plain JVM, so the compiler does not warn when it calls a Java method that older Android phones lack
 * (minSdk is 24). That crashed the app on a real Android 10 phone once. This lists the usual offenders.
 */
class PhoneApiGuardTest {
    private val banned = listOf(
        "readNBytes", "readAllBytes", "transferTo", "Files.readString", "Files.writeString", "List.of(", "Set.of(", "Map.of(",
        "java.net.http", "HexFormat", "java.util.Base64", "Optional<", "String.strip(", "java.util.concurrent.Flow",
    )

    @Test
    fun `phone-side code avoids Java methods that old Android versions lack`() {
        val roots = listOf(File("src/main"), File("../app/src/main"))
        val hits = roots.filter { it.isDirectory }.flatMap { r -> r.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "Io.kt" }.toList() }
            .flatMap { f -> f.readLines().mapIndexedNotNull { i, line -> banned.firstOrNull { line.contains(it) && !line.trimStart().startsWith("//") && !line.trimStart().startsWith("*") && !line.trimStart().startsWith("/**") }?.let { "${f.path}:${i + 1} uses $it" } } }
        assertTrue(hits.isEmpty(), "These need a replacement that works on Android 7 to 12:\n" + hits.joinToString("\n"))
    }

    /**
     * Java accepts a bare `}` in a regular expression; Android's engine throws "Syntax error in regexp pattern". Every `}` in a
     * pattern must close a `{n}` or `{n,m}` count or be escaped as `\\}`. Found when reading mail on a real phone.
     */
    @Test
    fun `regular expressions escape a literal closing brace`() {
        val roots = listOf(File("src/main"), File("../app/src/main"))
        val literal = Regex("Regex\\(\"((?:[^\"\\\\]|\\\\.)*)\"")
        val bad = roots.filter { it.isDirectory }.flatMap { r -> r.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                literal.findAll(line).firstNotNullOfOrNull { m ->
                    val pattern = m.groupValues[1].replace("\\\\\\\\", "").replace("\\\\}", "").replace("\\\\{", "")   // drop escaped braces
                    if (Regex("(?<!\\{[0-9,]{0,6})\\}").containsMatchIn(pattern.replace(Regex("\\{[0-9]+(,[0-9]*)?\\}"), ""))) "${f.path}:${i + 1}" else null
                }
            }
        }
        assertTrue(bad.isEmpty(), "These patterns have an unescaped } (Android rejects them):\n" + bad.joinToString("\n"))
    }
}
