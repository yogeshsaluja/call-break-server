// Standalone Call Break backend — pure Kotlin/JVM, no Android, no KMP. Each module applies
// these plugins without a version (declared once here). Unlike the monorepo, there is no
// build-logic on the classpath, so the normal versioned plugin declaration works directly.
plugins {
    kotlin("jvm") version "2.2.20" apply false
    kotlin("plugin.serialization") version "2.2.20" apply false
}
