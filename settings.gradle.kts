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

// Each server owns a pure-JVM engine, bot AI and wire protocol implementation. Keep the
// protocol contracts synchronized with the matching Android modules and cover them with
// round-trip and real-WebSocket tests.
include(":engine")
include(":ai")
include(":protocol")
include(":server")
include(":ludo-engine")
include(":ludo-ai")
include(":ludo-protocol")
include(":ludo-server")
