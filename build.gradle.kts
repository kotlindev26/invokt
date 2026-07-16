import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.konan.target.KonanTarget

plugins {
    kotlin("multiplatform") version "2.3.10"
    id("io.invokt.compiler")
}

group = "io.invokt"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

// FFM restricted methods (downcallHandle, libraryLookup, reinterpret) need this.
tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

kotlin {
    jvmToolchain(25)

    jvm()
    macosArm64()
    linuxX64()
    mingwX64()

    // Every native target gets the libffi cinterop; the commonizer (see
    // gradle.properties) makes it visible to the shared nativeMain source set.
    // Apple targets use the system libffi (headers from the SDK, see libffi.def);
    // for the others we vendor headers + a static lib that gets embedded into
    // the klib, so consumers need no libffi at runtime.
    targets.withType<KotlinNativeTarget>().configureEach {
        val vendored = when (konanTarget) {
            KonanTarget.LINUX_X64 -> file("third_party/libffi/linux-x64")
            KonanTarget.MINGW_X64 -> file("third_party/libffi/mingw-x64")
            else -> null
        }
        compilations.getByName("main").cinterops.create("libffi") {
            if (vendored != null) {
                compilerOpts("-I$vendored/include", "-I$vendored/include/ffi")
                extraOpts("-staticLibrary", "libffi.a", "-libraryPath", "$vendored/lib")
            }
        }
        if (konanTarget == KonanTarget.MINGW_X64) {
            // platform.windows lacks psapi.h (EnumProcessModules), bind it ourselves.
            compilations.getByName("main").cinterops.create("psapi")
        }
    }

    // Manual hierarchy: nativeMain holds the libffi call engine (all native
    // targets), posixMain the dlopen-based loader (Apple + Linux), mingwMain
    // the LoadLibrary-based one (Windows).
    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        val nativeMain by creating { dependsOn(commonMain.get()) }
        val posixMain by creating { dependsOn(nativeMain) }
        val mingwMain by creating { dependsOn(nativeMain) }
        getByName("macosArm64Main").dependsOn(posixMain)
        getByName("linuxX64Main").dependsOn(posixMain)
        getByName("mingwX64Main").dependsOn(mingwMain)
    }
}
