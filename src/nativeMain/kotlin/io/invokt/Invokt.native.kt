@file:OptIn(ExperimentalForeignApi::class)

package io.invokt

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.DoubleVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.pointed
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toKString
import kotlinx.cinterop.toLong
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.free
import platform.posix.malloc
import platform.posix.memcpy
import platform.posix.memset

actual fun newArena(): Arena = MallocArena()

private class MallocArena : Arena {

    private val allocations = mutableListOf<Ptr>()
    private var closed = false

    override fun allocate(byteSize: Long, alignment: Long): Ptr {
        check(!closed) { "arena is already closed" }
        require(byteSize >= 0) { "byteSize must be >= 0, was $byteSize" }
        require(alignment > 0 && alignment and (alignment - 1) == 0L) {
            "alignment must be a power of two, was $alignment"
        }
        // Over-allocate and align manually — portable across POSIX and Windows,
        // whose aligned-allocation APIs differ.
        val raw = malloc((byteSize + alignment).convert())
            ?: throw RuntimeException("native allocation of $byteSize bytes failed")
        allocations += Ptr(raw.toLong())
        val aligned = Ptr((raw.toLong() + alignment - 1) and (alignment - 1).inv())
        memset(aligned.address.toCPointer<ByteVar>(), 0, byteSize.convert())
        return aligned
    }

    override fun allocateCString(value: String): Ptr {
        val bytes = value.encodeToByteArray()
        // +1 for the NUL terminator, which the zeroed allocation provides.
        val ptr = allocate(bytes.size + 1L, alignment = 1L)
        if (bytes.isNotEmpty()) {
            bytes.usePinned { pinned ->
                memcpy(ptr.address.toCPointer<ByteVar>(), pinned.addressOf(0), bytes.size.convert())
            }
        }
        return ptr
    }

    override fun close() {
        if (closed) return
        closed = true
        allocations.forEach { free(it.address.toCPointer<ByteVar>()) }
        allocations.clear()
    }
}

/*
 * Compiler-plugin runtime helpers for statically imported functions with
 * String parameters/returns. Public only so generated code can call them.
 */

/** Allocates a NUL-terminated UTF-8 copy of [value]; free with [invoktFreeCString]. */
fun invoktAllocCString(value: String): Long {
    val bytes = value.encodeToByteArray()
    val mem = malloc((bytes.size + 1).convert())
        ?: throw RuntimeException("native allocation of ${bytes.size + 1} bytes failed")
    if (bytes.isNotEmpty()) {
        bytes.usePinned { pinned ->
            memcpy(mem, pinned.addressOf(0), bytes.size.convert())
        }
    }
    mem.reinterpret<ByteVar>()[bytes.size] = 0
    return mem.toLong()
}

fun invoktFreeCString(address: Long) {
    free(address.toCPointer<ByteVar>())
}

fun invoktReadCString(address: Long): String = readReturnedCString(address)

actual fun Ptr.readByte(offset: Long): Byte = (address + offset).toCPointer<ByteVar>()!!.pointed.value
actual fun Ptr.readShort(offset: Long): Short = (address + offset).toCPointer<ShortVar>()!!.pointed.value
actual fun Ptr.readInt(offset: Long): Int = (address + offset).toCPointer<IntVar>()!!.pointed.value
actual fun Ptr.readLong(offset: Long): Long = (address + offset).toCPointer<LongVar>()!!.pointed.value
actual fun Ptr.readFloat(offset: Long): Float = (address + offset).toCPointer<FloatVar>()!!.pointed.value
actual fun Ptr.readDouble(offset: Long): Double = (address + offset).toCPointer<DoubleVar>()!!.pointed.value
actual fun Ptr.readPtr(offset: Long): Ptr = Ptr((address + offset).toCPointer<LongVar>()!!.pointed.value)

actual fun Ptr.writeByte(value: Byte, offset: Long) { (address + offset).toCPointer<ByteVar>()!!.pointed.value = value }
actual fun Ptr.writeShort(value: Short, offset: Long) { (address + offset).toCPointer<ShortVar>()!!.pointed.value = value }
actual fun Ptr.writeInt(value: Int, offset: Long) { (address + offset).toCPointer<IntVar>()!!.pointed.value = value }
actual fun Ptr.writeLong(value: Long, offset: Long) { (address + offset).toCPointer<LongVar>()!!.pointed.value = value }
actual fun Ptr.writeFloat(value: Float, offset: Long) { (address + offset).toCPointer<FloatVar>()!!.pointed.value = value }
actual fun Ptr.writeDouble(value: Double, offset: Long) { (address + offset).toCPointer<DoubleVar>()!!.pointed.value = value }
actual fun Ptr.writePtr(value: Ptr, offset: Long) { (address + offset).toCPointer<LongVar>()!!.pointed.value = value.address }

actual fun Ptr.readCString(): String = address.toCPointer<ByteVar>()!!.toKString()
