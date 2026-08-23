pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    // No google() repository — this is a pure Kotlin/JVM backend with no Android
    // dependencies, so it opens and builds in IntelliJ IDEA without the Android SDK.
    repositories {
        mavenCentral()
    }
}

rootProject.name = "CallBreakServer"

// The rules engine, bot AI, and wire protocol are compiled here as pure-JVM modules from
// the SAME source files the Android app uses (see each module's build.gradle.kts srcDir),
// keeping one source of truth. Only :server physically lives in this project.
include(":engine")
include(":ai")
include(":protocol")
include(":server")
