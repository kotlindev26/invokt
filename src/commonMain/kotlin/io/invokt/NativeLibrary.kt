package io.invokt

/**
 * A loaded native library (a `.dylib`, `.so`, or `.dll`).
 *
 * Obtain instances via [openLibrary]. Closing the library invalidates all
 * [NativeFunction]s and symbol pointers obtained from it.
 */
interface NativeLibrary : AutoCloseable {

    /** The name or path this library was opened with. */
    val name: String

    /**
     * Looks up an exported symbol, or returns `null` if the library does not
     * export it. The returned pointer stays valid until [close] is called.
     */
    fun symbolOrNull(name: String): Ptr?

    /** Like [symbolOrNull], but throws [MissingSymbolException] when absent. */
    fun symbol(name: String): Ptr =
        symbolOrNull(name) ?: throw MissingSymbolException(this.name, name)

    /**
     * Binds an exported function with the given signature.
     *
     * The signature is *trusted*: it must match the actual C declaration, exactly
     * like a wrong P/Invoke signature in C#, a mismatch is undefined behavior.
     *
     * Variadic functions (`printf` & friends) are bound per *instantiation*:
     * declare the full concrete signature in [params] and pass the number of
     * fixed (non-vararg) parameters as [fixedArgs] — e.g. `snprintf` called
     * with one `%d` is `(Pointer, U64, Str, I32)` with `fixedArgs = 3`.
     * This matters: some ABIs (macOS arm64) pass varargs differently than
     * regular arguments. The default `-1` means "not variadic".
     */
    fun <R> function(
        name: String,
        returns: CType<R>,
        vararg params: CType<*>,
        fixedArgs: Int = -1,
    ): NativeFunction<R>

    override fun close()
}

/**
 * A callable native function bound from a [NativeLibrary].
 */
interface NativeFunction<R> {

    /** The symbol name this function was bound to. */
    val name: String

    val returns: CType<R>
    val params: List<CType<*>>

    /**
     * Calls the native function.
     *
     * Arguments must match [params] in count and Kotlin representation
     * ([CType.I32] -> [Int], [CType.Pointer] -> [Ptr], ...), otherwise an
     * [IllegalArgumentException] is thrown. This entry point boxes primitives;
     * a zero-overhead typed layer comes later via code generation.
     */
    operator fun invoke(vararg args: Any?): R
}

class MissingSymbolException(library: String, symbol: String) :
    RuntimeException("Symbol '$symbol' not found in library '$library'")

class LibraryLoadException(library: String, cause: String?) :
    RuntimeException("Could not load native library '$library'" + (cause?.let { ": $it" } ?: ""))
