plugins {
    id("org.jetbrains.kotlin.jvm")
}

/**
 * The protocol core is a plain JVM library: no Android APIs, no third-party dependencies, and no
 * test framework. `core/src/test/kotlin` is a `main()` with a hand-rolled assertion harness rather
 * than JUnit, which is what lets `core/build.sh` run the whole suite with nothing but `kotlinc`
 * and a JDK. Gradle therefore runs it through `JavaExec` instead of the `test` task.
 */
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// src/main/kotlin and src/test/kotlin are already the Kotlin JVM plugin's defaults, which is why
// the layout was chosen; no source-set configuration is needed.

// The `test` task expects a JUnit-style framework and would fail on an empty candidate set.
tasks.named<Test>("test") { enabled = false }

val coreTest = tasks.register<JavaExec>("coreTest") {
    group = "verification"
    description = "Runs the protocol core's self-contained test suite."
    mainClass.set("app.castmirror.core.TestMainKt")
    classpath = sourceSets["test"].runtimeClasspath
}

tasks.named("check") { dependsOn(coreTest) }
