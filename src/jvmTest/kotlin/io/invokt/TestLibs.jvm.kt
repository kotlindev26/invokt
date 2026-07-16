package io.invokt

private val os = System.getProperty("os.name").lowercase()

actual val zlibName: String = when {
    "mac" in os -> "libz.dylib"
    "windows" in os -> "zlib1.dll"
    else -> "libz.so.1"
}

actual val missingLibName: String = when {
    "mac" in os -> "libdoesnotexist42.dylib"
    "windows" in os -> "doesnotexist42.dll"
    else -> "libdoesnotexist42.so"
}
