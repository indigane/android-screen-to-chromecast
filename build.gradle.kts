plugins {
    id("com.android.application") version "9.1.0" apply false
    // AGP 9 ships Kotlin support built in for Android modules, so `org.jetbrains.kotlin.android`
    // is intentionally not declared here. The pure-JVM `:core` module still needs the standalone
    // Kotlin JVM plugin.
    id("org.jetbrains.kotlin.jvm") version "2.3.20" apply false
}

tasks.register("clean", Delete::class) {
    delete(layout.buildDirectory)
}
