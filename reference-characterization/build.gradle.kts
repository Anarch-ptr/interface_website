plugins {
    java
}

// Temporary read-only Android reference source. No production module depends on this module.
val androidReferenceSource = file("E:/Study/Monash/Y2S2/ENG5105/interface/app/src/main/java")
val kotlinCompiler = file("E:/Study_Software/AndroidStudio/plugins/Kotlin/kotlinc/bin/kotlinc.bat")
val kotlinStdlib = file("E:/Study_Software/AndroidStudio/plugins/Kotlin/kotlinc/lib/kotlin-stdlib.jar")
val cachedArtifacts = file("E:/Study_Software/Android/.gradle/caches/modules-2/files-2.1")
val junitJar = fileTree(cachedArtifacts.resolve("junit/junit/4.13.2")) { include("**/junit-4.13.2.jar") }.singleFile
val hamcrestJar = fileTree(cachedArtifacts.resolve("org.hamcrest/hamcrest-core/1.3")) { include("**/hamcrest-core-1.3.jar") }.singleFile
require(androidReferenceSource.isDirectory && kotlinCompiler.isFile && kotlinStdlib.isFile) {
    "The temporary characterization module needs the read-only Android source and installed Kotlin compiler"
}

val referencePaths = listOf(
    "com/filamentvision/camera/model/CameraVisualModels.kt",
    "com/filamentvision/model/InputProfiles.kt",
    "com/filamentvision/model/MeasurementStatus.kt",
    "com/filamentvision/model/MonitoringSession.kt",
    "com/filamentvision/model/VisionMeasurement.kt",
    "com/filamentvision/vision/detection/DetectionPipeline.kt",
    "com/filamentvision/vision/fusion/FusionEngine.kt",
    "com/filamentvision/vision/geometry/ImageGeometryTransformer.kt",
    "com/filamentvision/vision/preprocessing/EdgeMapProcessor.kt",
    "com/filamentvision/vision/preprocessing/GrayscaleProcessor.kt",
    "com/filamentvision/vision/preprocessing/IntensityFrame.kt",
    "com/filamentvision/vision/preprocessing/ThresholdProcessor.kt",
    "com/filamentvision/vision/processing/VisionPreviewFactory.kt",
    "com/filamentvision/vision/processing/VisionProcessingResult.kt",
    "com/filamentvision/vision/processing/VisionProcessor.kt",
    "com/filamentvision/domain/alarm/AlarmEvaluator.kt",
    "com/filamentvision/domain/session/SessionStatisticsAccumulator.kt",
)
val referenceFiles = referencePaths.map(androidReferenceSource::resolve)
require(referenceFiles.all { it.isFile }) { "One or more selected Android reference sources are missing" }
val testClassesDir = layout.buildDirectory.dir("classes/kotlin/test")
val fixtureDir = file("../fixtures")
val testSources = fileTree("src/test/kotlin") { include("**/*.kt") }

val compileCharacterizationKotlin by tasks.registering(Exec::class) {
    inputs.files(referenceFiles, testSources)
    outputs.dir(testClassesDir)
    doFirst {
        testClassesDir.get().asFile.mkdirs()
        commandLine(
            listOf(kotlinCompiler.absolutePath) + referenceFiles.map { it.absolutePath } +
                testSources.files.map { it.absolutePath } +
                listOf("-jvm-target", "17", "-classpath", junitJar.absolutePath, "-d", testClassesDir.get().asFile.absolutePath),
        )
    }
}

dependencies {
    testImplementation(files(junitJar, hamcrestJar))
}

tasks.test {
    dependsOn(compileCharacterizationKotlin)
    testClassesDirs = files(testClassesDir)
    classpath = files(testClassesDir, fixtureDir, kotlinStdlib, junitJar, hamcrestJar)
    useJUnit()
}
