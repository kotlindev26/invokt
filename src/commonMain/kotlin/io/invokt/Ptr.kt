package io.invokt

import kotlin.jvm.JvmInline

/**
 * A raw native memory address.
 *
 * This is the common currency for all platform implementations: on the JVM it wraps
 * the address of a `MemorySegment`, on Kotlin/Native the raw value of a `CPointer`.
 * A [Ptr] carries no size, type, or lifetime information — it is exactly as unsafe
 * as a C pointer.
 */
@JvmInline
value class Ptr(val address: Long) {

    val isNull: Boolean get() = address == 0L

    operator fun plus(offset: Long): Ptr = Ptr(address + offset)
    operator fun minus(offset: Long): Ptr = Ptr(address - offset)

    override fun toString(): String = "Ptr(0x${address.toString(16)})"

    companion object {
        val NULL = Ptr(0L)
    }
}
