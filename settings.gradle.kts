pluginManagement {
    includeBuild("invokt-compiler")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "invokt"

// Substitutes the io.invokt:invokt-compiler-plugin artifact on the compiler
// plugin classpath with the local build.
includeBuild("invokt-compiler")
