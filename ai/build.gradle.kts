plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

// Bot AI compiled from the monorepo's commonMain (see :engine for the shared-source rationale).
sourceSets.main {
    kotlin.srcDir("../../Call-Break/shared/core/ai/src/commonMain/kotlin")
}

dependencies {
    implementation(project(":engine"))
}
