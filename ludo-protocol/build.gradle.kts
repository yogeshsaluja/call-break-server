plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":ludo-engine"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlin.test)
}
