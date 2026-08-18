// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "9.1.1" apply false
    // AGP 9.1.1 provides built-in Kotlin; only the Compose compiler plugin is needed.
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}
