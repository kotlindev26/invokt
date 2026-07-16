@file:OptIn(ExperimentalForeignApi::class)

package io.invokt

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.DoubleVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cstr
import kotlinx.cinterop.free
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toKString
import kotlinx.cinterop.toLong
import kotlinx.cinterop.value
import libffi.FFI_OK
import libffi.ffi_call
import libffi.ffi_cif
import libffi.ffi_prep_cif
import libffi.ffi_prep_cif_var
import libffi.ffi_type
import libffi.ffi_type_double
import libffi.ffi_type_float
import libffi.ffi_type_pointer
import libffi.ffi_type_sint16
import libffi.ffi_type_sint32
import libffi.ffi_type_sint64
import libffi.ffi_type_sint8
import libffi.ffi_type_uint16
import libffi.ffi_type_uint32
import libffi.ffi_type_uint64
import libffi.ffi_type_uint8
import libffi.ffi_type_void

/*
 * Kotlin/Native call engine, shared by all native targets: symbol resolution
 * is platform-specific (dlopen/dlsym on POSIX, LoadLibrary/GetProcAddress on
 * Windows — see posixMain/mingwMain), the calls themselves go through libffi.
 * Kotlin/Native cannot construct call signatures at runtime — libffi closes
 * exactly that gap: we describe the signature as data (ffi_cif) and libffi
 * assembles the ABI-correct call for us. libffi itself has a fixed API, which
 * is why *it* can be bound statically via cinterop.
 */

/**
 * Base for platform libraries: subclasses provide symbol lookup and handle
 * lifecycle, function binding and disposal bookkeeping live here.
 */
internal abstract class FfiBackedLibrary : NativeLibrary {

    private val boundFunctions = mutableListOf<FfiFunction<*>>()

    final override fun <R> function(
        name: String,
        returns: CType<R>,
        vararg params: CType<*>,
        fixedArgs: Int,
    ): NativeFunction<R> {
        val target = symbol(name).address.toCPointer<CFunction<() -> Unit>>()
            ?: throw MissingSymbolException(this.name, name)
        val fn = FfiFunction(name, returns, params.toList(), fixedArgs, target)
        boundFunctions += fn
        return fn
    }

    /** Frees the ffi_cif resources of all bound functions; call from [close]. */
    protected fun disposeBoundFunctions() {
        boundFunctions.forEach { it.dispose() }
        boundFunctions.clear()
    }
}

/**
 * A prepared libffi call descriptor (ffi_cif plus its argument type array),
 * allocated on the native heap because it must outlive every call — used by
 * both call directions (downcalls via [FfiFunction], upcalls via closures).
 */
internal class PreparedCif(returns: CType<*>, params: List<CType<*>>, fixedArgs: Int, where: String) {

    private val argTypes: CPointer<CPointerVar<ffi_type>>? =
        if (params.isEmpty()) null
        else nativeHeap.allocArray<CPointerVar<ffi_type>>(params.size).also { arr ->
            params.forEachIndexed { i, p -> arr[i] = p.ffiType() }
        }

    val cif: ffi_cif = nativeHeap.alloc<ffi_cif>().also {
        val status =
            if (fixedArgs in 0..params.size) ffi_prep_cif_var(
                it.ptr, libffi.FFI_DEFAULT_ABI,
                fixedArgs.toUInt(), params.size.toUInt(), returns.ffiType(), argTypes,
            )
            else ffi_prep_cif(
                it.ptr, libffi.FFI_DEFAULT_ABI,
                params.size.toUInt(), returns.ffiType(), argTypes,
            )
        check(status == FFI_OK) { "ffi_prep_cif failed for $where (status $status)" }
    }

    fun dispose() {
        nativeHeap.free(cif.ptr)
        argTypes?.let { nativeHeap.free(it) }
    }
}

internal class FfiFunction<R>(
    override val name: String,
    override val returns: CType<R>,
    override val params: List<CType<*>>,
    fixedArgs: Int,
    private val target: CPointer<CFunction<() -> Unit>>,
) : NativeFunction<R> {

    init {
        requireValidSignature(returns, params, fixedArgs, where = "'$name'")
    }

    private val prepared = PreparedCif(returns, params, fixedArgs, where = "'$name'")

    override fun invoke(vararg args: Any?): R {
        requireMatchingArgs(name, params, args)
        memScoped {
            val avalues =
                if (params.isEmpty()) null
                else allocArray<COpaquePointerVar>(params.size).also { arr ->
                    params.forEachIndexed { i, p ->
                        arr[i] = when (p) {
                            CType.Void -> error("unreachable: rejected by requireMatchingArgs")
                            CType.I8 -> alloc<ByteVar> { value = args[i] as Byte }.ptr
                            CType.I16 -> alloc<ShortVar> { value = args[i] as Short }.ptr
                            CType.I32 -> alloc<IntVar> { value = args[i] as Int }.ptr
                            CType.I64 -> alloc<LongVar> { value = args[i] as Long }.ptr
                            CType.F32 -> alloc<FloatVar> { value = args[i] as Float }.ptr
                            CType.F64 -> alloc<DoubleVar> { value = args[i] as Double }.ptr
                            CType.Pointer -> alloc<LongVar> { value = (args[i] as Ptr).address }.ptr
                            CType.Bool -> alloc<UByteVar> { value = if (args[i] as Boolean) 1u else 0u }.ptr
                            CType.U8 -> alloc<UByteVar> { value = args[i] as UByte }.ptr
                            CType.U16 -> alloc<UShortVar> { value = args[i] as UShort }.ptr
                            CType.U32 -> alloc<UIntVar> { value = args[i] as UInt }.ptr
                            CType.U64 -> alloc<ULongVar> { value = args[i] as ULong }.ptr
                            // The temporary UTF-8 copy lives in this memScoped, i.e. exactly for the call.
                            CType.Str -> alloc<LongVar> { value = (args[i] as String).cstr.ptr.toLong() }.ptr
                        }
                    }
                }
            // libffi widens integral returns narrower than a word to ffi_arg,
            // so 8 aligned bytes cover every return type we support.
            val rvalue = alloc<LongVar>()
            ffi_call(prepared.cif.ptr, target, rvalue.ptr, avalues)
            return unmarshalReturn(rvalue)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun unmarshalReturn(rvalue: LongVar): R = when (returns) {
        CType.Void -> Unit
        CType.I8 -> rvalue.value.toByte()
        CType.I16 -> rvalue.value.toShort()
        CType.I32 -> rvalue.value.toInt()
        CType.I64 -> rvalue.value
        CType.F32 -> rvalue.ptr.reinterpret<FloatVar>().pointed.value
        CType.F64 -> rvalue.ptr.reinterpret<DoubleVar>().pointed.value
        CType.Pointer -> Ptr(rvalue.value)
        CType.Bool -> (rvalue.value and 0xFF) != 0L
        CType.U8 -> rvalue.value.toUByte()
        CType.U16 -> rvalue.value.toUShort()
        CType.U32 -> rvalue.value.toUInt()
        CType.U64 -> rvalue.value.toULong()
        CType.Str -> readReturnedCString(rvalue.value)
    } as R

    fun dispose() = prepared.dispose()
}

internal fun readReturnedCString(address: Long): String {
    check(address != 0L) { "native function returned a NULL char*; bind it as Pointer if NULL is expected" }
    return address.toCPointer<ByteVar>()!!.toKString()
}

internal fun CType<*>.ffiType(): CPointer<ffi_type> = when (this) {
    CType.Void -> ffi_type_void.ptr
    CType.I8 -> ffi_type_sint8.ptr
    CType.I16 -> ffi_type_sint16.ptr
    CType.I32 -> ffi_type_sint32.ptr
    CType.I64 -> ffi_type_sint64.ptr
    CType.F32 -> ffi_type_float.ptr
    CType.F64 -> ffi_type_double.ptr
    CType.Bool -> ffi_type_uint8.ptr
    CType.U8 -> ffi_type_uint8.ptr
    CType.U16 -> ffi_type_uint16.ptr
    CType.U32 -> ffi_type_uint32.ptr
    CType.U64 -> ffi_type_uint64.ptr
    CType.Pointer, CType.Str -> ffi_type_pointer.ptr
}
