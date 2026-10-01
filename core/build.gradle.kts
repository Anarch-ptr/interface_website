import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

tasks.test {
    useJUnit()
    dependsOn("verifyCorePlatformBoundary")
}

val forbiddenImports = listOf(
    "android.", "androidx.", "io.ktor.", "java.awt.", "javax.imageio.",
    "java.sql.", "org.sqlite.", "androidx.room.", "androidx.compose.",
    "com.fasterxml.jackson.", "kotlinx.serialization.",
)
val forbiddenComponents = listOf(
    "android", "ktor", "sqlite", "jdbc", "room", "compose", "jackson",
    "serialization", "awt", "swing", "javafx", "imageio",
)

tasks.register("verifyCorePlatformBoundary") {
    group = "verification"
    description = "Rejects platform imports and resolved runtime dependencies in Core."

    doLast {
        val importPattern = Regex("^\\s*import\\s+(?:static\\s+)?([A-Za-z_][A-Za-z_0-9.]*)", RegexOption.MULTILINE)
        val sourceViolations = fileTree("src/main") {
            include("**/*.kt", "**/*.java")
        }.flatMap { source ->
            var blockCommentDepth = 0
            var tripleQuoted = false
            val clean = StringBuilder()
            source.readLines().forEach { line ->
                var index = 0
                var quoted = false
                while (index < line.length) {
                    when {
                        blockCommentDepth > 0 && line.startsWith("/*", index) -> { blockCommentDepth++; clean.append(' '); index += 2 }
                        blockCommentDepth > 0 && line.startsWith("*/", index) -> { blockCommentDepth--; index += 2 }
                        blockCommentDepth > 0 -> index++
                        tripleQuoted && line.startsWith("\"\"\"", index) -> { tripleQuoted = false; index += 3 }
                        tripleQuoted -> index++
                        quoted && line[index] == '\\' -> index += 2
                        quoted && line[index] == '"' -> { quoted = false; index++ }
                        quoted -> index++
                        line.startsWith("//", index) -> break
                        line.startsWith("/*", index) -> { blockCommentDepth++; clean.append(' '); index += 2 }
                        line.startsWith("\"\"\"", index) -> { tripleQuoted = true; clean.append(' '); index += 3 }
                        line[index] == '"' -> { quoted = true; clean.append(' '); index++ }
                        else -> { clean.append(line[index]); index++ }
                    }
                }
                clean.append('\n')
            }
            importPattern.findAll(clean).mapNotNull { match ->
                match.groupValues[1].let { imported ->
                    if (forbiddenImports.any(imported::startsWith)) "${source.path}: $imported" else null
                }
            }.toList()
        }
        check(sourceViolations.isEmpty()) {
            (listOf("Core production imports forbidden platform packages:") + sourceViolations).joinToString("\n")
        }

        val resolvedComponents = configurations.getByName("runtimeClasspath")
            .incoming.resolutionResult.allComponents
            .mapNotNull { it.id as? ModuleComponentIdentifier }
        val dependencyViolations = resolvedComponents.filter { component ->
            val coordinate = "${component.group}:${component.module}".lowercase()
            forbiddenComponents.any(coordinate::contains)
        }
        check(dependencyViolations.isEmpty()) {
            (listOf("Core runtime resolves forbidden components:") + dependencyViolations).joinToString("\n")
        }
    }
}
