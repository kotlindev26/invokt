package io.invokt

import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.lang.foreign.Arena as JavaArena

/*
 * JVM backend built on the FFM API (java.lang.foreign, final since JDK 22).
 *
 * Uses restricted methods (libraryLookup, downcallHandle, reinterpret), so the
 * JVM must run with --enable-native-access=ALL-UNNAMED.
 */

internal val LINKER: Linker = Linker.nativeLinker()

internal class FfmLibrary(
    override val name: String,
    private val lookup: SymbolLookup,
    private val onClose: () -> Unit = {},
) : NativeLibrary {

    override fun symbolOrNull(name: String): Ptr? =
        lookup.find(name).map { Ptr(it.address()) }.orElse(null)

    override fun <R> function(
        name: String,
        returns: CType<R>,
        vararg params: CType<*>,
        fixedArgs: Int,
    ): NativeFunction<R> = ffmFunction(symbol(name), name, returns, params.toList(), fixedArgs)

    override fun close() = onClose()
}

internal fun <R> ffmFunction(
    target: Ptr,
    name: String,
    returns: CType<R>,
    params: List<CType<*>>,
    fixedArgs: Int,
): NativeFunction<R> {
    requireValidSignature(returns, params, fixedArgs, where = "'$name'")
    val descriptor = ffmDescriptor(returns, params)
    val options =
        if (fixedArgs in 0..params.size) arrayOf(Linker.Option.firstVariadicArg(fixedArgs))
        else emptyArray()
    val handle = LINKER.downcallHandle(MemorySegment.ofAddress(target.address), descriptor, *options)
    return FfmFunction(name, returns, params, handle)
}

internal fun ffmDescriptor(returns: CType<*>, params: List<CType<*>>): FunctionDescriptor {
    val paramLayouts = params.map { it.asLayout() }.toTypedArray()
    return if (returns == CType.Void) FunctionDescriptor.ofVoid(*paramLayouts)
    else FunctionDescriptor.of(returns.asLayout(), *paramLayouts)
}

internal class FfmFunction<R>(
    override val name: String,
    override val returns: CType<R>,
    override val params: List<CType<*>>,
    private val handle: MethodHandle,
) : NativeFunction<R> {

    override fun invoke(vararg args: Any?): R {
        requireMatchingArgs(name, params, args)
        // Str arguments live in a temporary arena that only spans the call.
        var tempArena: JavaArena? = null
        try {
            val marshalled = args.mapIndexed { i, arg ->
                when (params[i]) {
                    CType.Str -> {
                        val arena = tempArena ?: JavaArena.ofConfined().also { tempArena = it }
                        arena.allocateFrom(arg as String)
                    }
                    else -> toFfm(arg)
                }
            }
            return unmarshal(handle.invokeWithArguments(marshalled))
        } finally {
            tempArena?.close()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun unmarshal(value: Any?): R = when (returns) {
        CType.Void -> Unit
        CType.Pointer -> Ptr((value as MemorySegment).address())
        CType.U8 -> (value as Byte).toUByte()
        CType.U16 -> (value as Short).toUShort()
        CType.U32 -> (value as Int).toUInt()
        CType.U64 -> (value as Long).toULong()
        CType.Str -> readReturnedCString((value as MemorySegment).address())
        else -> value // I8..F64, Bool: FFM already returns the exact Kotlin type
    } as R
}

/** FFM representation of a marshallable value (except Str, which needs an arena). */
internal fun toFfm(arg: Any?): Any? = when (arg) {
    is Ptr -> MemorySegment.ofAddress(arg.address)
    is UByte -> arg.toByte()
    is UShort -> arg.toShort()
    is UInt -> arg.toInt()
    is ULong -> arg.toLong()
    else -> arg // primitives and Boolean pass through unchanged
}

internal fun readReturnedCString(address: Long): String {
    check(address != 0L) { "native function returned a NULL char*; bind it as Pointer if NULL is expected" }
    return MEMORY.getString(address)
}

internal fun CType<*>.asLayout(): ValueLayout = when (this) {
    CType.Void -> throw IllegalArgumentException("Void is only valid as a return type")
    CType.I8 -> ValueLayout.JAVA_BYTE
    CType.I16 -> ValueLayout.JAVA_SHORT
    CType.I32 -> ValueLayout.JAVA_INT
    CType.I64 -> ValueLayout.JAVA_LONG
    CType.F32 -> ValueLayout.JAVA_FLOAT
    CType.F64 -> ValueLayout.JAVA_DOUBLE
    CType.Bool -> ValueLayout.JAVA_BOOLEAN
    CType.U8 -> ValueLayout.JAVA_BYTE
    CType.U16 -> ValueLayout.JAVA_SHORT
    CType.U32 -> ValueLayout.JAVA_INT
    CType.U64 -> ValueLayout.JAVA_LONG
    CType.Pointer, CType.Str -> ValueLayout.ADDRESS
}
