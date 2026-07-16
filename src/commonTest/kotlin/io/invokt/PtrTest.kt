package io.invokt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PtrTest {

    @Test
    fun nullPointer() {
        assertTrue(Ptr.NULL.isNull)
        assertFalse(Ptr(0x1000).isNull)
    }

    @Test
    fun pointerArithmetic() {
        val base = Ptr(0x1000)
        assertEquals(Ptr(0x1010), base + 0x10)
        assertEquals(Ptr(0x0FF0), base - 0x10)
    }

    @Test
    fun rendersAsHex() {
        assertEquals("Ptr(0xff)", Ptr(255).toString())
    }
}
