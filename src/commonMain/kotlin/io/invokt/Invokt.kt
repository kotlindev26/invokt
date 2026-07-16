package io.invokt

/*
 * Platform entry points. These are the only `expect` declarations besides the
 * raw memory accessors in Memory.kt — everything else is expressed against the
 * common interfaces.
 */

/**
 * Opens a native library by name or path.
 *
 * [name] is passed to the platform loader as-is (`dlopen` on Kotlin/Native,
 * `SymbolLookup.libraryLookup` on the JVM), so platform naming conventions
 * apply: `"libz.dylib"` vs. `"libz.so"` vs. an absolute path.
 *
 * @throws LibraryLoadException if the library cannot be loaded.
 */
expect fun openLibrary(name: String): NativeLibrary

/**
 * Opens a handle on the symbols already loaded into the current process
 * (the executable itself plus everything linked into it, e.g. libc).
 * Closing it is a no-op.
 */
expect fun processLibrary(): NativeLibrary

/**
 * Creates a new confined [Arena]. Prefer [withArena] for scoped use.
 */
expect fun newArena(): Arena
