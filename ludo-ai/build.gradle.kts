plugins { kotlin("jvm") }

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":ludo-engine"))
    testImplementation(libs.kotlin.test)
}
