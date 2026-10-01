plugins {
    id("org.jetbrains.kotlin.jvm") version "2.2.10" apply false
}

tasks.register("verifyStandaloneIndependence") {
    group = "verification"
    description = "Reject temporary reference modules and external Android build/source coupling."
    doLast {
        val temporaryModule = "reference-" + "characterization"
        val forbidden = listOf(
            Regex(Regex.escape(temporaryModule)),
            Regex("(?i)[A-Z]:[/\\\\][^\"\\r\\n]*[/\\\\]interface(?:[/\\\\]|\")"),
            Regex("(?i)(?:project|include|includeBuild)\\s*\\([^)]*[\"']:?(?:app|android)[\"']"),
            Regex("(?i)(?:id|implementation|api)\\s*\\(\\s*[\"'](?:com\\.android\\.|androidx\\.)")
        )
        val roots = listOf("settings.gradle.kts", "build.gradle.kts", "gradle.properties", "gradle", "core", "backend")
        val files = roots.flatMap { root -> file(root).walkTopDown().onEnter {
            it.name !in setOf("build", ".gradle", ".kotlin", "test")
        }.filter { it.isFile && (it.extension in setOf("kts", "kt", "java", "properties", "conf", "xml", "json", "yaml", "yml")) }.toList() }
        val violations = files.flatMap { source -> source.readLines().mapIndexedNotNull { index, line ->
            if (forbidden.any { it.containsMatchIn(line) }) "${source.relativeTo(rootDir)}:${index + 1}" else null
        } }
        check(violations.isEmpty()) { "Standalone independence violations: ${violations.joinToString()}" }
        check(subprojects.map { it.path }.toSet() == setOf(":core", ":backend")) { "Unexpected module" }
        println("STANDALONE_INDEPENDENCE PASS scanned=${files.size} modules=core,backend")
    }
}
subprojects { tasks.matching { it.name == "check" }.configureEach { dependsOn(rootProject.tasks.named("verifyStandaloneIndependence")) } }
