@file:OptIn(ExperimentalForeignApi::class)

package io.invokt

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.DoubleVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toKString
import kotlinx.cinterop.toLong
import kotlinx.cinterop.value
import libffi.FFI_OK
import libffi.ffi_cif
import libffi.ffi_closure
import libffi.ffi_closure_alloc
import libffi.ffi_closure_free
import libffi.ffi_prep_closure_loc

actual fun nativeCallback(
    returns: CType<*>,
    vararg params: CType<*>,
    body: (args: List<Any?>) -> Any?,
): Callback = FfiClosureCallback(returns, params.toList(), body)

actual fun <R> functionAt(
    target: Ptr,
    returns: CType<R>,
    vararg params: CType<*>,
    fixedArgs: Int,
): NativeFunction<R> {
    require(!target.isNull) { "functionAt: target must not be NULL" }
    val name = "<at 0x${target.address.toString(16)}>"
    val fn = target.address.toCPointer<CFunction<() -> Unit>>()!!
    // Note: the FfiFunction's cif is never disposed — bindings made through
    // functionAt live until the process exits (documented on the expect).
    return FfiFunction(name, returns, params.toList(), fixedArgs, fn)
}

/**
 * A libffi closure: libffi allocates a fresh, executable trampoline whose
 * signature is described by our cif; every call lands in [handler] with the
 * arguments as an array of pointers, plus our user data (a [StableRef] to
 * this object), and we dispatch into the Kotlin [body].
 */
internal class FfiClosureCallback(
    private val returns: CType<*>,
    private val params: List<CType<*>>,
    private val body: (args: List<Any?>) -> Any?,
) : Callback {

    private val prepared: PreparedCif
    private val self = StableRef.create(this)
    private val closure: COpaquePointer
    override val ptr: Ptr

    init {
        requireValidCallbackSignature(returns, params)
        prepared = PreparedCif(returns, params, fixedArgs = -1, where = "<callback>")
        val code = memScoped {
            val codeSlot = alloc<COpaquePointerVar>()
            closure = ffi_closure_alloc(sizeOf<ffi_closure>().convert(), codeSlot.ptr)
                ?: throw RuntimeException("ffi_closure_alloc failed")
            codeSlot.value!!
        }
        val status = ffi_prep_closure_loc(
            closure.reinterpret(),
            prepared.cif.ptr,
            handler,
            self.asCPointer(),
            code,
        )
        check(status == FFI_OK) { "ffi_prep_closure_loc failed (status $status)" }
        ptr = Ptr(code.toLong())
    }

    internal fun dispatch(ret: COpaquePointer?, argv: CPointer<COpaquePointerVar>?) {
        val args = params.mapIndexed { i, p ->
            val slot = argv!![i]!!
            when (p) {
                CType.Void -> error("unreachable: rejected by requireValidCallbackSignature")
                CType.I8 -> slot.reinterpret<ByteVar>().pointed.value
                CType.I16 -> slot.reinterpret<ShortVar>().pointed.value
                CType.I32 -> slot.reinterpret<IntVar>().pointed.value
                CType.I64 -> slot.reinterpret<LongVar>().pointed.value
                CType.F32 -> slot.reinterpret<FloatVar>().pointed.value
                CType.F64 -> slot.reinterpret<DoubleVar>().pointed.value
                CType.Pointer -> Ptr(slot.reinterpret<LongVar>().pointed.value)
                CType.Bool -> slot.reinterpret<UByteVar>().pointed.value.toInt() != 0
                CType.U8 -> slot.reinterpret<UByteVar>().pointed.value
                CType.U16 -> slot.reinterpret<UShortVar>().pointed.value
                CType.U32 -> slot.reinterpret<UIntVar>().pointed.value
                CType.U64 -> slot.reinterpret<ULongVar>().pointed.value
                CType.Str -> slot.reinterpret<LongVar>().pointed.value
                    .toCPointer<ByteVar>()?.toKString() ?: ""
            }
        }
        val result = body(args)
        if (returns == CType.Void) return
        requireMatchingArgs("<callback>", listOf(returns), arrayOf(result))
        // libffi expects integral results narrower than a word widened to ffi_arg.
        when (returns) {
            CType.F32 -> ret!!.reinterpret<FloatVar>().pointed.value = result as Float
            CType.F64 -> ret!!.reinterpret<DoubleVar>().pointed.value = result as Double
            else -> ret!!.reinterpret<LongVar>().pointed.value = when (returns) {
                CType.I8 -> (result as Byte).toLong()
                CType.I16 -> (result as Short).toLong()
                CType.I32 -> (result as Int).toLong()
                CType.I64 -> result as Long
                CType.Pointer -> (result as Ptr).address
                CType.Bool -> if (result as Boolean) 1L else 0L
                CType.U8 -> (result as UByte).toLong()
                CType.U16 -> (result as UShort).toLong()
                CType.U32 -> (result as UInt).toLong()
                CType.U64 -> (result as ULong).toLong()
                else -> error("unreachable")
            }
        }
    }

    override fun close() {
        ffi_closure_free(closure.reinterpret<ffi_closure>())
        self.dispose()
        prepared.dispose()
    }

    private companion object {
        private val handler = staticCFunction {
                _: CPointer<ffi_cif>?,
                ret: COpaquePointer?,
                argv: CPointer<COpaquePointerVar>?,
                userData: COpaquePointer?,
            ->
            userData!!.asStableRef<FfiClosureCallback>().get().dispatch(ret, argv)
        }
    }
}
