plugins {
    kotlin("jvm")
    `java-gradle-plugin`
}

group = "io.invokt"
version = "0.1.0"

dependencies {
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin-api:2.3.10")
}

gradlePlugin {
    plugins {
        create("invokt") {
            id = "io.invokt.compiler"
            implementationClass = "io.invokt.gradle.InvoktGradlePlugin"
        }
    }
}
