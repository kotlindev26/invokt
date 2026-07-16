@file:OptIn(ExperimentalForeignApi::class)

package io.invokt

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.cinterop.toLong
import platform.posix.RTLD_NOW
import platform.posix.dlclose
import platform.posix.dlerror
import platform.posix.dlopen
import platform.posix.dlsym

actual fun openLibrary(name: String): NativeLibrary {
    val handle = dlopen(name, RTLD_NOW)
        ?: throw LibraryLoadException(name, dlerror()?.toKString())
    return DlLibrary(name, handle, ownsHandle = true)
}

actual fun processLibrary(): NativeLibrary {
    // dlopen(NULL) yields a handle searching the whole process image,
    // including libc — the same view the JVM's defaultLookup provides.
    val handle = dlopen(null, RTLD_NOW)
        ?: throw LibraryLoadException("<process>", dlerror()?.toKString())
    return DlLibrary("<process>", handle, ownsHandle = false)
}

internal class DlLibrary(
    override val name: String,
    private val handle: COpaquePointer,
    private val ownsHandle: Boolean,
) : FfiBackedLibrary() {

    override fun symbolOrNull(name: String): Ptr? =
        dlsym(handle, name)?.let { Ptr(it.toLong()) }

    override fun close() {
        disposeBoundFunctions()
        if (ownsHandle) dlclose(handle)
    }
}
