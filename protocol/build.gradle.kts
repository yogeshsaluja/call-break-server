plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

kotlin {
    jvmToolchain(21)
}

// Wire protocol compiled from the monorepo's commonMain (see :engine for the rationale).
sourceSets.main {
    kotlin.srcDir("../../Call-Break/shared/core/protocol/src/commonMain/kotlin")
}

dependencies {
    implementation(project(":engine"))
    implementation(libs.kotlinx.serialization.json)
}
