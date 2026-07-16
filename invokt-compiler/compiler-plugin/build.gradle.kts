plugins {
    kotlin("jvm")
}

group = "io.invokt"
version = "0.1.0"

dependencies {
    compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.3.10")
}

kotlin {
    compilerOptions {
        // Symbol.owner access during IR construction — fine in an IrGenerationExtension,
        // which runs after linkage.
        freeCompilerArgs.add("-opt-in=org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI")
    }
}
