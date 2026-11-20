// AGP 9 compiles Kotlin itself ("built-in Kotlin"), so the org.jetbrains.kotlin.android plugin is gone.
// This pins the Kotlin Gradle plugin to the version of the Compose compiler plugin below.
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    id("com.android.application") version "9.5.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
