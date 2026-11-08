plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

group = "com.playcrew.ludo"
version = "0.1.0"

application { mainClass.set("com.playcrew.ludo.server.ApplicationKt") }
kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":ludo-engine"))
    implementation(project(":ludo-ai"))
    implementation(project(":ludo-protocol"))
    implementation(libs.bundles.ktor.server)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    runtimeOnly(libs.logback.classic)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.websockets)
}
