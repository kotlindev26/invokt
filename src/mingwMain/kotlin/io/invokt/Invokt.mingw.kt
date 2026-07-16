@file:OptIn(ExperimentalForeignApi::class)

package io.invokt

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toLong
import kotlinx.cinterop.value
import psapi.K32EnumProcessModules
import platform.windows.DWORDVar
import platform.windows.FreeLibrary
import platform.windows.GetCurrentProcess
import platform.windows.GetLastError
import platform.windows.GetProcAddress
import platform.windows.HMODULE
import platform.windows.HMODULEVar
import platform.windows.LoadLibraryW

actual fun openLibrary(name: String): NativeLibrary {
    val handle = LoadLibraryW(name)
        ?: throw LibraryLoadException(name, "Win32 error ${GetLastError()}")
    return WinLibrary(name, handle, ownsHandle = true)
}

actual fun processLibrary(): NativeLibrary = WinProcessLibrary()

internal class WinLibrary(
    override val name: String,
    private val handle: HMODULE,
    private val ownsHandle: Boolean,
) : FfiBackedLibrary() {

    override fun symbolOrNull(name: String): Ptr? =
        GetProcAddress(handle, name)?.let { Ptr(it.toLong()) }

    override fun close() {
        disposeBoundFunctions()
        if (ownsHandle) FreeLibrary(handle)
    }
}

/**
 * Windows has no dlopen(NULL) equivalent, so "the process's symbols" means:
 * search every module currently loaded into the process, in load order.
 * That includes the C runtime the binary links against.
 */
internal class WinProcessLibrary : FfiBackedLibrary() {

    override val name: String get() = "<process>"

    override fun symbolOrNull(name: String): Ptr? = memScoped {
        val process = GetCurrentProcess()
        val needed = alloc<DWORDVar>()
        if (K32EnumProcessModules(process, null, 0u, needed.ptr) == 0) return@memScoped null
        val count = (needed.value.toLong() / sizeOf<HMODULEVar>()).toInt()
        if (count == 0) return@memScoped null
        val modules = allocArray<HMODULEVar>(count)
        if (K32EnumProcessModules(process, modules, needed.value, needed.ptr) == 0) return@memScoped null
        for (i in 0 until count) {
            val module = modules[i] ?: continue
            GetProcAddress(module, name)?.let { return@memScoped Ptr(it.toLong()) }
        }
        null
    }

    override fun close() {
        disposeBoundFunctions()
    }
}
