package io.invokt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * Statically imported libc functions — bound by the invokt compiler plugin:
 * direct C calls on Kotlin/Native (no libffi), cached FFM handles on the JVM.
 * Symbol names mirror LibcExampleTest so results can be cross-checked against
 * the dynamic path.
 */

@Import
private fun getpid(): Int = imported()

@Import(symbol = "abs")
private fun cAbs(value: Int): Int = imported()

@Import(symbol = "sqrt")
private fun cSqrt(x: Double): Double = imported()

@Import(symbol = "time")
private fun cTime(t: Ptr): Long = imported()

@Import(symbol = "strlen")
private fun cStrlen(s: Ptr): Long = imported()

@Import(symbol = "srand")
private fun cSrand(seed: Int): Unit = imported()

@Import(symbol = "rand")
private fun cRand(): Int = imported()

@Import(symbol = "atoi")
private fun cAtoi(s: String): Int = imported()

@Import(symbol = "strtoul")
private fun cStrtoul(s: String, end: Ptr, base: Int): ULong = imported()

@Import(symbol = "strerror")
private fun cStrerror(code: Int): String = imported()

class StaticImportTest {

    @Test
    fun staticCallWithoutArguments() {
        val pid = getpid()

        assertTrue(pid > 0, "expected a real PID, got $pid")
        // Must agree with the dynamic path — same process, same function.
        assertEquals(processLibrary().function("getpid", CType.I32)(), pid)
    }

    @Test
    fun staticCallWithPrimitiveArgument() {
        assertEquals(42, cAbs(-42))
        assertEquals(0, cAbs(0))
    }

    @Test
    fun staticCallWithFloatingPoint() {
        assertEquals(3.0, cSqrt(9.0))
        assertEquals(1.4142135623730951, cSqrt(2.0))
    }

    @Test
    fun staticCallWithNullPointer() {
        val now = cTime(Ptr.NULL)

        assertTrue(now > 1_500_000_000L, "expected a current unix timestamp, got $now")
    }

    @Test
    fun staticCallWithPointerArgument() = withArena { arena ->
        assertEquals(13L, cStrlen(arena.allocateCString("Hello invokt!")))
    }

    @Test
    fun staticCallWithStringArgument() {
        assertEquals(42, cAtoi("42"))
        // String + Ptr + Int arguments with an unsigned return, all statically bound.
        assertEquals(4294967295uL, cStrtoul("4294967295", Ptr.NULL, 10))
    }

    @Test
    fun staticCallWithStringReturn() {
        assertTrue(cStrerror(0).isNotEmpty())
    }

    @Test
    fun staticVoidCall() {
        cSrand(123)
        val first = cRand()
        cSrand(123)
        val second = cRand()

        assertEquals(first, second)
    }
}
