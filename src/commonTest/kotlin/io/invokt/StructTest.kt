package io.invokt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StructTest {

    @Test
    fun layoutFollowsCRules() {
        // struct { int32_t a; char b; double c; }
        val s = CStruct(CType.I32, CType.I8, CType.F64)

        assertEquals(0L, s.offset(0))
        assertEquals(4L, s.offset(1))
        assertEquals(8L, s.offset(2)) // 3 padding bytes before the double
        assertEquals(16L, s.size)
        assertEquals(8L, s.alignment)
    }

    @Test
    fun trailingPaddingRoundsUpToAlignment() {
        // struct { double a; char b; } -> 7 trailing padding bytes
        val s = CStruct(CType.F64, CType.I8)

        assertEquals(16L, s.size)
    }

    @Test
    fun pointerBoolAndUnsignedFields() {
        // struct { bool a; void *p; uint16_t u; }
        val s = CStruct(CType.Bool, CType.Pointer, CType.U16)

        assertEquals(0L, s.offset(0))
        assertEquals(8L, s.offset(1))
        assertEquals(16L, s.offset(2))
        assertEquals(24L, s.size)
    }

    @Test
    fun fieldRoundTripInMemory() = withArena { arena ->
        val s = CStruct(CType.I32, CType.I8, CType.F64)
        val p = arena.allocate(s)

        p.writeInt(7, s.offset(0))
        p.writeByte(1, s.offset(1))
        p.writeDouble(2.5, s.offset(2))

        assertEquals(7, p.readInt(s.offset(0)))
        assertEquals(1, p.readByte(s.offset(1)))
        assertEquals(2.5, p.readDouble(s.offset(2)))
    }

    @Test
    fun arrayStrideEqualsSize() = withArena { arena ->
        val s = CStruct(CType.I32, CType.I8) // size 8
        val p = arena.allocateArray(s, count = 3)

        for (i in 0 until 3) p.writeInt(i * 10, i * s.size + s.offset(0))

        assertEquals(listOf(0, 10, 20), (0 until 3).map { p.readInt(it * s.size + s.offset(0)) })
    }

    @Test
    fun readStructReturnedByLibc() = withArena { arena ->
        // struct tm: the first nine fields are ints in this order on glibc,
        // Darwin and the Windows CRT (de-facto layout, asserted by this test):
        // tm_sec, tm_min, tm_hour, tm_mday, tm_mon, tm_year, tm_wday, tm_yday, tm_isdst
        val tm = CStruct(
            CType.I32, CType.I32, CType.I32, CType.I32, CType.I32,
            CType.I32, CType.I32, CType.I32, CType.I32,
        )
        val libc = processLibrary()

        val timePtr = arena.allocate(8)
        timePtr.writeLong(libc.function("time", CType.I64, CType.Pointer)(Ptr.NULL))
        // C: struct tm *localtime(const time_t *);
        val tmPtr = libc.function("localtime", CType.Pointer, CType.Pointer)(timePtr)

        val year = tmPtr.readInt(tm.offset(5)) + 1900
        val month = tmPtr.readInt(tm.offset(4))
        assertTrue(year >= 2026, "expected a current year, got $year")
        assertTrue(month in 0..11, "expected a month index, got $month")
    }
}
