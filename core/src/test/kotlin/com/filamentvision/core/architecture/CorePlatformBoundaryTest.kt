package com.filamentvision.core.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class CorePlatformBoundaryTest {
    private val forbidden = listOf(
        "android.", "androidx.", "io.ktor.", "java.awt.", "javax.imageio.",
        "java.sql.", "org.sqlite.", "androidx.room.", "androidx.compose.",
        "com.fasterxml.jackson.", "kotlinx.serialization.",
    )

    @Test
    fun coreProductionSourcesDoNotImportPlatformPackages() {
        val sources = File("src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
        val violations = sources.flatMap { file ->
            productionImports(file.readText()).asSequence().map { file.path to it }
        }.filter { (_, imported) -> forbidden.any(imported::startsWith) }.toList()
        assertTrue("Forbidden Core imports: $violations", violations.isEmpty())
    }

    @Test
    fun commentsAndOrdinaryStringsAreNotImports() {
        val source = listOf(
            "package example",
            "/* import android.fake.FromComment */",
            "// import io.ktor.fake.FromComment",
            "import kotlin.String",
            "val text = \"\"\"",
            "import java.sql.FromString",
            "\"\"\"",
        ).joinToString("\n")
        assertTrue(productionImports(source) == listOf("kotlin.String"))
    }

    @Test
    fun importsAfterMultilineFileAnnotationsAreInspected() {
        val source = listOf(
            "@file:Suppress(",
            "    \"unused\"",
            ")",
            "package example",
            "import java.sql.Driver",
        ).joinToString("\n")
        assertTrue(productionImports(source) == listOf("java.sql.Driver"))
    }

    private fun productionImports(source: String): List<String> {
        val importPattern = Regex("^\\s*import\\s+(?:static\\s+)?([A-Za-z_][A-Za-z_0-9.]*)")
        var blockCommentDepth = 0
        var tripleQuoted = false
        val imports = mutableListOf<String>()
        for (line in source.lines()) {
            val clean = StringBuilder()
            var index = 0
            var quoted = false
            while (index < line.length) {
                when {
                    blockCommentDepth > 0 && line.startsWith("/*", index) -> { blockCommentDepth++; index += 2 }
                    blockCommentDepth > 0 && line.startsWith("*/", index) -> { blockCommentDepth--; index += 2 }
                    blockCommentDepth > 0 -> index++
                    tripleQuoted && line.startsWith("\"\"\"", index) -> { tripleQuoted = false; index += 3 }
                    tripleQuoted -> index++
                    quoted && line[index] == '\\' -> index += 2
                    quoted && line[index] == '"' -> { quoted = false; index++ }
                    quoted -> index++
                    line.startsWith("//", index) -> break
                    line.startsWith("/*", index) -> { blockCommentDepth++; index += 2 }
                    line.startsWith("\"\"\"", index) -> { tripleQuoted = true; index += 3 }
                    line[index] == '"' -> { quoted = true; index++ }
                    else -> { clean.append(line[index]); index++ }
                }
            }
            importPattern.find(clean)?.groupValues?.get(1)?.let(imports::add)
        }
        return imports
    }

    @Test
    fun coreDeclaresNoPlatformDependencies() {
        val buildFile = File("build.gradle.kts")
        val declared = buildFile.readLines()
            .map(String::trim)
            .filter { it.startsWith("implementation(") || it.startsWith("api(") ||
                it.startsWith("compileOnly(") || it.startsWith("runtimeOnly(") }
        val banned = listOf("android", "ktor", "sqlite", "jdbc", "room", "compose", "jackson", "serialization")
        val violations = declared.filter { declaration ->
            banned.any { declaration.contains(it, ignoreCase = true) }
        }
        assertTrue("Forbidden Core dependency declarations: $violations", violations.isEmpty())
    }
}
