pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        kotlin("jvm") version "2.3.10"
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "invokt-compiler"

include(":invokt-compiler-plugin", ":invokt-gradle-plugin")
project(":invokt-compiler-plugin").projectDir = file("compiler-plugin")
project(":invokt-gradle-plugin").projectDir = file("gradle-plugin")
