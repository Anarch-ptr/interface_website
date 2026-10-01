# Task 02 — standalone module boundary

Task 2 ran in `C:\Users\asus\Documents\ChatGPT\5105interface\webiste2.0`. The temporary `:reference-characterization` module remains registered and unchanged. No Android source or E: reference repository was edited.

## RED and environment recovery

Before creating either new module, `.\gradlew.bat :core:test` exited 1 with:

```text
Cannot locate tasks that match ':core:test' as project 'core' not found in root project 'FilamentVisionBackend'.
```

The first GREEN attempt stopped before tests while resolving the Kotlin/JVM 2.2.10 plugin marker. An `--info` retry showed `java.net.SocketException: Permission denied: getsockopt` for `https://plugins.gradle.org:443`. After the fixed dependencies were downloaded in an approved network context, Gradle stopped on the missing physical JDK 17 toolchain:

```text
Cannot find a Java installation on your machine ... matching: {languageVersion=17 ...}.
```

The approved local adjustment is a JDK 21 toolchain with Kotlin `JvmTarget.JVM_17` and Java `options.release=17` in both modules. Inspection of `CoreMarker` and `BackendSmokeTest` bytecode with `javap -verbose` showed major version 61 (Java 17). CI should also compile and test with a physical JDK 17.

## GREEN

With `GRADLE_USER_HOME` at this repository's `.gradle` and `JAVA_HOME` at the installed Adoptium 21 JDK:

```powershell
.\gradlew.bat :core:test :core:verifyCorePlatformBoundary :backend:test :reference-characterization:test --offline --rerun-tasks
```

Result: `BUILD SUCCESSFUL`; Core 4 tests, Backend 1 test, reference characterization 9 tests, 0 failures and 0 errors. The Backend smoke test uses a direct `CoreMarker` type reference. The Core test checks production imports and dependency declarations; `:core:test` depends on `verifyCorePlatformBoundary`.

## Resolved dependency boundary

```powershell
.\gradlew.bat :core:dependencies --configuration runtimeClasspath --offline
.\gradlew.bat :backend:dependencies --configuration runtimeClasspath --offline
```

Core runtime contains Kotlin stdlib 2.2.10, coroutines core/JVM 1.9.0, JetBrains annotations 23.0.0, and the coroutines BOM constraint. It contains no Ktor, SQLite/JDBC, AndroidX, Jackson, serialization, Compose, or desktop image dependency. Backend runtime contains `project :core`, Ktor server core/Netty/WebSockets/content-negotiation/Jackson 3.3.1, and SQLite JDBC 3.51.1.0.

The firewall task scans only Core production `.kt`/`.java` imports, excluding comments and ordinary strings, then checks all resolved Core runtime component identifiers. Temporary mutation checks proved it failed on `import java.sql.Driver`, including after a multiline file annotation, and on `runtimeOnly("org.xerial:sqlite-jdbc:3.51.1.0")`. It passed with forbidden-looking text in a comment and ordinary string. All mutations were removed.

Only root/module build configuration, `CoreMarker`, tests, and this note were added or changed. There is no Vision, runtime, repository, route, simulator, Pi protocol, or React implementation in this task.
