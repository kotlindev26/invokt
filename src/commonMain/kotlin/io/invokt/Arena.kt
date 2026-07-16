package io.invokt

/**
 * An allocation scope for native memory.
 *
 * All allocations made through an arena are freed together when the arena is
 * closed — mirroring `java.lang.foreign.Arena` on the JVM and `memScoped` on
 * Kotlin/Native. Accessing a [Ptr] after its arena was closed is undefined
 * behavior.
 */
interface Arena : AutoCloseable {

    /**
     * Allocates [byteSize] bytes of zero-initialized native memory with the
     * given [alignment] (must be a power of two).
     */
    fun allocate(byteSize: Long, alignment: Long = 8L): Ptr

    /**
     * Allocates and fills a NUL-terminated, UTF-8 encoded C string.
     */
    fun allocateCString(value: String): Ptr

    override fun close()
}

/**
 * Runs [block] with a fresh [Arena] and closes it afterwards, freeing all
 * memory allocated inside.
 */
inline fun <T> withArena(block: (Arena) -> T): T = newArena().use(block)
