import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

application { mainClass.set("com.filamentvision.backend.BackendApplicationKt") }
tasks.named<JavaExec>("run") { standardInput = System.`in` }
tasks.register<JavaExec>("runDiagnosticClient") {
    group = "application"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.filamentvision.backend.DiagnosticClient")
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
    implementation(project(":core"))
    implementation("io.ktor:ktor-server-core-jvm:3.3.1")
    implementation("io.ktor:ktor-server-netty-jvm:3.3.1")
    implementation("io.ktor:ktor-server-websockets-jvm:3.3.1")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:3.3.1")
    implementation("io.ktor:ktor-serialization-jackson-jvm:3.3.1")
    implementation("org.xerial:sqlite-jdbc:3.51.1.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.ktor:ktor-server-test-host-jvm:3.3.1")
}

tasks.test {
    useJUnit()
}
