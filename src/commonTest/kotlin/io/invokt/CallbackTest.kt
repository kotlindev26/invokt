package io.invokt

import kotlin.test.Test
import kotlin.test.assertEquals

class CallbackTest {

    private val libc: NativeLibrary get() = processLibrary()

    @Test
    fun qsortWithKotlinComparator() = withArena { arena ->
        val values = intArrayOf(5, 1, 4, 2, 3)
        val buf = arena.allocate(4L * values.size, alignment = 4)
        values.forEachIndexed { i, v -> buf.writeInt(v, i * 4L) }

        // C: void qsort(void *base, size_t nmemb, size_t size,
        //              int (*compar)(const void *, const void *));
        nativeCallback(CType.I32, CType.Pointer, CType.Pointer) { (a, b) ->
            (a as Ptr).readInt().compareTo((b as Ptr).readInt())
        }.use { comparator ->
            val qsort = libc.function("qsort", CType.Void, CType.Pointer, CType.U64, CType.U64, CType.Pointer)
            qsort(buf, values.size.toULong(), 4uL, comparator.ptr)
        }

        assertEquals(listOf(1, 2, 3, 4, 5), (0 until values.size).map { buf.readInt(it * 4L) })
    }

    // functionAt(callback.ptr, ...) sends values through the real C ABI in
    // both directions — a self-contained round trip without any C library.

    @Test
    fun boolAndUnsignedRoundTrip() {
        nativeCallback(CType.Bool, CType.U32) { (x) ->
            (x as UInt) > 100u
        }.use { cb ->
            val isBig = functionAt(cb.ptr, CType.Bool, CType.U32)
            assertEquals(true, isBig(150u))
            assertEquals(false, isBig(50u))
        }
    }

    @Test
    fun stringRoundTrip() {
        nativeCallback(CType.I64, CType.Str) { (s) ->
            (s as String).length.toLong()
        }.use { cb ->
            val lengthOf = functionAt(cb.ptr, CType.I64, CType.Str)
            // Non-ASCII: proves the UTF-8 encode/decode round trip, 5 chars = 6 bytes.
            assertEquals(5L, lengthOf("héllo"))
        }
    }

    @Test
    fun voidCallback() {
        var received = 0
        nativeCallback(CType.Void, CType.I32) { (x) ->
            received = x as Int
            null
        }.use { cb ->
            val poke = functionAt(cb.ptr, CType.Void, CType.I32)
            poke(42)
            assertEquals(42, received)
        }
    }

    @Test
    fun floatingPointRoundTrip() {
        nativeCallback(CType.F64, CType.F64, CType.F32) { (a, b) ->
            (a as Double) + (b as Float)
        }.use { cb ->
            val add = functionAt(cb.ptr, CType.F64, CType.F64, CType.F32)
            assertEquals(3.5, add(1.5, 2.0f))
        }
    }
}
