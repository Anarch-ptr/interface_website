package com.filamentvision.backend.architecture

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionNetworkConstantTest {
    @Test
    fun productionSourcesDoNotContainDocumentationOrFixtureNetworkTargets() {
        val projectRoot = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.exists(it.resolve("settings.gradle.kts")) }
        val forbidden = listOf(
            "10.97.199.28",
            "10.97.199.42",
            "192.168.0.103",
            "filament-pi.local",
            "filamentvision-pc.local",
        )
        val productionRoots = listOf(
            projectRoot.resolve("backend/src/main"),
            projectRoot.resolve("frontend/src"),
            projectRoot.resolve("scripts"),
        ).filter(Files::exists)

        val violations = productionRoots.flatMap { root ->
            Files.walk(root).use { paths ->
                paths.filter(Files::isRegularFile)
                    .filter { it.fileName.toString().substringAfterLast('.', "") in TEXT_EXTENSIONS }
                    .flatMap { path ->
                        val text = Files.readString(path)
                        forbidden.filter(text::contains).map { value ->
                            "${projectRoot.relativize(path)} contains $value"
                        }.stream()
                    }
                    .toList()
            }
        }

        assertTrue("Production network constants must be configurable: $violations", violations.isEmpty())
    }

    private companion object {
        val TEXT_EXTENSIONS = setOf("kt", "kts", "ts", "tsx", "js", "jsx", "json", "bat", "vbs")
    }
}
