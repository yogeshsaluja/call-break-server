plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

kotlin {
    jvmToolchain(21)
}

// Single source of truth: compile the rules engine straight from the Android monorepo's
// commonMain, so the server always runs the identical rules as the app. No files are copied.
sourceSets.main {
    kotlin.srcDir("../../Call-Break/shared/core/engine/src/commonMain/kotlin")
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
}
