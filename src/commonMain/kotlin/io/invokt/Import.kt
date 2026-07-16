package io.invokt

/**
 * Declares a statically bound native function, the P/Invoke-style counterpart
 * to the dynamic [NativeLibrary.function] API:
 *
 * ```
 * @Import(library = "libz.dylib")
 * fun zlibVersion(): Ptr = imported()
 * ```
 *
 * The invokt compiler plugin replaces the [imported] marker body: on
 * Kotlin/Native with a direct C call through a cached function pointer
 * (resolved once via dlsym — no libffi involved), on the JVM with a cached
 * FFM downcall handle.
 *
 * Supported parameter types: [Byte], [Short], [Int], [Long], [Float],
 * [Double], [Ptr]. Return types additionally allow [Unit].
 *
 * @param library library name or path as understood by [openLibrary];
 *   empty means the process's own symbols, like [processLibrary].
 * @param symbol the native symbol name; empty means the function's name.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class Import(val library: String = "", val symbol: String = "")

/**
 * Marker body for [Import]-annotated functions, replaced at compile time.
 * Reaching this at runtime means the invokt compiler plugin is not applied.
 */
fun <T> imported(): T = throw IllegalStateException(
    "invokt: marker body was not replaced — is the invokt compiler plugin applied to this compilation?"
)

/**
 * Binding helpers the compiler plugin generates calls to. Public only for
 * that reason — not intended to be called from user code.
 */
object InvoktRuntime {

    private val libraries = mutableMapOf<String, NativeLibrary>()

    private fun library(name: String): NativeLibrary = libraries.getOrPut(name) {
        if (name.isEmpty()) processLibrary() else openLibrary(name)
    }

    /** JVM path: binds a function once; the result is cached at the call site. */
    fun bind(library: String, symbol: String, returns: CType<*>, vararg params: CType<*>): NativeFunction<*> =
        library(library).function(symbol, returns, *params)

    /** Native path: resolves a symbol address once; cast and cached at the call site. */
    fun resolve(library: String, symbol: String): Long =
        library(library).symbol(symbol).address
}
