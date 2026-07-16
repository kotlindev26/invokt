package io.invokt

import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.foreign.Arena as JavaArena

actual fun openLibrary(name: String): NativeLibrary {
    // The lookup's lifetime is tied to this arena; closing it unloads the library.
    val arena = JavaArena.ofShared()
    val lookup = try {
        SymbolLookup.libraryLookup(name, arena)
    } catch (e: IllegalArgumentException) {
        arena.close()
        throw LibraryLoadException(name, e.message)
    }
    return FfmLibrary(name, lookup, onClose = arena::close)
}

actual fun processLibrary(): NativeLibrary =
    FfmLibrary("<process>", LINKER.defaultLookup())

actual fun newArena(): Arena = FfmArenaAdapter(JavaArena.ofConfined())

private class FfmArenaAdapter(private val arena: JavaArena) : Arena {

    override fun allocate(byteSize: Long, alignment: Long): Ptr =
        Ptr(arena.allocate(byteSize, alignment).address())

    override fun allocateCString(value: String): Ptr =
        Ptr(arena.allocateFrom(value).address())

    override fun close() = arena.close()
}

/**
 * A view over the whole address space, so a raw [Ptr] can be dereferenced
 * without knowing which segment it came from. This is exactly as unsafe as
 * the C pointer semantics the common API promises.
 */
internal val MEMORY: MemorySegment = MemorySegment.NULL.reinterpret(Long.MAX_VALUE)

actual fun <R> functionAt(
    target: Ptr,
    returns: CType<R>,
    vararg params: CType<*>,
    fixedArgs: Int,
): NativeFunction<R> {
    require(!target.isNull) { "functionAt: target must not be NULL" }
    return ffmFunction(target, "<at 0x${target.address.toString(16)}>", returns, params.toList(), fixedArgs)
}

actual fun Ptr.readByte(offset: Long): Byte = MEMORY.get(ValueLayout.JAVA_BYTE, address + offset)
actual fun Ptr.readShort(offset: Long): Short = MEMORY.get(ValueLayout.JAVA_SHORT, address + offset)
actual fun Ptr.readInt(offset: Long): Int = MEMORY.get(ValueLayout.JAVA_INT, address + offset)
actual fun Ptr.readLong(offset: Long): Long = MEMORY.get(ValueLayout.JAVA_LONG, address + offset)
actual fun Ptr.readFloat(offset: Long): Float = MEMORY.get(ValueLayout.JAVA_FLOAT, address + offset)
actual fun Ptr.readDouble(offset: Long): Double = MEMORY.get(ValueLayout.JAVA_DOUBLE, address + offset)
actual fun Ptr.readPtr(offset: Long): Ptr = Ptr(MEMORY.get(ValueLayout.ADDRESS, address + offset).address())

actual fun Ptr.writeByte(value: Byte, offset: Long) { MEMORY.set(ValueLayout.JAVA_BYTE, address + offset, value) }
actual fun Ptr.writeShort(value: Short, offset: Long) { MEMORY.set(ValueLayout.JAVA_SHORT, address + offset, value) }
actual fun Ptr.writeInt(value: Int, offset: Long) { MEMORY.set(ValueLayout.JAVA_INT, address + offset, value) }
actual fun Ptr.writeLong(value: Long, offset: Long) { MEMORY.set(ValueLayout.JAVA_LONG, address + offset, value) }
actual fun Ptr.writeFloat(value: Float, offset: Long) { MEMORY.set(ValueLayout.JAVA_FLOAT, address + offset, value) }
actual fun Ptr.writeDouble(value: Double, offset: Long) { MEMORY.set(ValueLayout.JAVA_DOUBLE, address + offset, value) }
actual fun Ptr.writePtr(value: Ptr, offset: Long) { MEMORY.set(ValueLayout.ADDRESS, address + offset, MemorySegment.ofAddress(value.address)) }

actual fun Ptr.readCString(): String = MEMORY.getString(address)
