plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

group = "com.yogesh.callbreak"
version = "0.1.0"

application {
    mainClass.set("com.yogesh.callbreak.server.ApplicationKt")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":engine"))
    implementation(project(":ai"))
    implementation(project(":protocol"))

    implementation(libs.bundles.ktor.server)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    runtimeOnly(libs.logback.classic)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.websockets)
}
