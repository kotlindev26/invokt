package io.invokt

/**
 * A Kotlin function exposed as a native function pointer (an *upcall*),
 * e.g. a comparator for `qsort` or an event handler for a C library.
 *
 * The pointer stays valid until [close] is called. Closing while native code
 * can still call the pointer is undefined behavior — deregister it first.
 */
interface Callback : AutoCloseable {

    /** The C-callable function pointer. Pass it wherever a `Pointer` is expected. */
    val ptr: Ptr

    override fun close()
}

/**
 * Creates a native function pointer with the given signature that dispatches
 * to [body].
 *
 * [body] receives the call's arguments in declaration order with the Kotlin
 * representation of each [CType] ([CType.I32] -> [Int], [CType.Pointer] ->
 * [Ptr], ...) and must return a value matching [returns] (`null`/anything
 * for [CType.Void]). [CType.Str] is allowed for parameters (the `char*` is
 * copied into a [String] per call) but not as a return type — there is no
 * one who could own the returned buffer.
 *
 * Implementation: FFM `upcallStub` on the JVM, a libffi closure on
 * Kotlin/Native.
 */
expect fun nativeCallback(
    returns: CType<*>,
    vararg params: CType<*>,
    body: (args: List<Any?>) -> Any?,
): Callback

/**
 * Binds a function at a raw address — the counterpart to
 * [NativeLibrary.function] for pointers that did not come from a symbol
 * lookup (function pointers returned by C code, [Callback.ptr], vtable
 * entries, ...).
 *
 * Note: on Kotlin/Native the binding allocates a small signature descriptor
 * that lives until the process exits; prefer binding once and reusing.
 */
expect fun <R> functionAt(
    target: Ptr,
    returns: CType<R>,
    vararg params: CType<*>,
    fixedArgs: Int = -1,
): NativeFunction<R>

internal fun requireValidCallbackSignature(returns: CType<*>, params: List<CType<*>>) {
    requireValidSignature(returns, params, fixedArgs = -1, where = "callback")
    require(returns != CType.Str) {
        "callback: Str is not a valid return type (no owner for the returned buffer); return a Pointer instead"
    }
}
