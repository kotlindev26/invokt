package io.invokt

import java.lang.foreign.MemorySegment
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.foreign.Arena as JavaArena

actual fun nativeCallback(
    returns: CType<*>,
    vararg params: CType<*>,
    body: (args: List<Any?>) -> Any?,
): Callback = FfmCallback(returns, params.toList(), body)

/**
 * FFM upcall: a MethodHandle onto [call] is adapted to the exact native
 * signature (`asType` boxes/unboxes at the boundary) and turned into a
 * function pointer via `Linker.upcallStub`.
 */
internal class FfmCallback(
    private val returns: CType<*>,
    private val params: List<CType<*>>,
    private val body: (args: List<Any?>) -> Any?,
) : Callback {

    private val arena = JavaArena.ofShared()
    override val ptr: Ptr

    init {
        requireValidCallbackSignature(returns, params)
        val descriptor = ffmDescriptor(returns, params)
        val handle = MethodHandles.lookup()
            .findVirtual(FfmCallback::class.java, "call", MethodType.methodType(Any::class.java, Array<Any?>::class.java))
            .bindTo(this)
            .asCollector(Array<Any?>::class.java, params.size)
            .asType(descriptor.toMethodType())
        ptr = Ptr(LINKER.upcallStub(handle, descriptor, arena).address())
    }

    // Invoked from native code through the stub; must stay public for findVirtual.
    fun call(rawArgs: Array<Any?>): Any? {
        val args = rawArgs.mapIndexed { i, raw ->
            when (params[i]) {
                CType.Pointer -> Ptr((raw as MemorySegment).address())
                CType.Str -> readReturnedCString((raw as MemorySegment).address())
                CType.U8 -> (raw as Byte).toUByte()
                CType.U16 -> (raw as Short).toUShort()
                CType.U32 -> (raw as Int).toUInt()
                CType.U64 -> (raw as Long).toULong()
                else -> raw
            }
        }
        val result = body(args)
        if (returns == CType.Void) return null
        requireMatchingArgs("<callback>", listOf(returns), arrayOf(result))
        return toFfm(result)
    }

    override fun close() = arena.close()
}
