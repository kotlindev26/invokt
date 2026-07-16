package io.invokt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end usage examples against libc — the acceptance tests for both
 * platform implementations. Everything here must eventually pass unchanged
 * on Kotlin/JVM (FFM) *and* Kotlin/Native (dlopen + libffi).
 */
class LibcExampleTest {

    // libc is linked into every process, so no dlopen naming games needed.
    private val libc: NativeLibrary get() = processLibrary()

    @Test
    fun callFunctionWithoutArguments() {
        // C: pid_t getpid(void);
        val getpid = libc.function("getpid", CType.I32)

        val pid = getpid()

        assertTrue(pid > 0, "expected a real PID, got $pid")
    }

    @Test
    fun callFunctionWithPrimitiveArgument() {
        // C: int abs(int);
        val abs = libc.function("abs", CType.I32, CType.I32)

        assertEquals(42, abs(-42))
        assertEquals(0, abs(0))
    }

    @Test
    fun callFunctionWithFloatingPointValues() {
        // Floats travel through different registers than integers in most
        // calling conventions — worth exercising explicitly.
        // C: double sqrt(double);
        val sqrt = libc.function("sqrt", CType.F64, CType.F64)

        assertEquals(3.0, sqrt(9.0))
        assertEquals(1.4142135623730951, sqrt(2.0))
    }

    @Test
    fun callVoidFunction() {
        // C: void srand(unsigned); int rand(void);
        val srand = libc.function("srand", CType.Void, CType.I32)
        val rand = libc.function("rand", CType.I32)

        srand(123)
        val first = rand()
        srand(123)
        val second = rand()

        // Same seed, same sequence — proves the void call actually happened.
        assertEquals(first, second)
    }

    @Test
    fun passNullPointer() {
        // C: time_t time(time_t *);
        val time = libc.function("time", CType.I64, CType.Pointer)

        val now = time(Ptr.NULL)

        assertTrue(now > 1_500_000_000L, "expected a current unix timestamp, got $now")
    }

    @Test
    fun passStringToNativeFunction() = withArena { arena ->
        // C: size_t strlen(const char *);
        val strlen = libc.function("strlen", CType.I64, CType.Pointer)

        val length = strlen(arena.allocateCString("Hello invokt!"))

        assertEquals(13L, length)
    }

    @Test
    fun cStringRoundTrip() = withArena { arena ->
        val original = "invokt über alles" // non-ASCII to prove UTF-8 handling

        val ptr = arena.allocateCString(original)

        assertEquals(original, ptr.readCString())
    }

    @Test
    fun rawMemoryRoundTrip() = withArena { arena ->
        val buffer = arena.allocate(byteSize = 32)

        buffer.writeInt(0xCAFE)
        buffer.writeLong(Long.MIN_VALUE, offset = 8)
        buffer.writeDouble(3.5, offset = 16)
        buffer.writePtr(buffer, offset = 24)

        assertEquals(0xCAFE, buffer.readInt())
        assertEquals(Long.MIN_VALUE, buffer.readLong(offset = 8))
        assertEquals(3.5, buffer.readDouble(offset = 16))
        assertEquals(buffer, buffer.readPtr(offset = 24))
    }

    @Test
    fun allocatedMemoryIsZeroInitialized() = withArena { arena ->
        val buffer = arena.allocate(byteSize = 16)

        assertEquals(0L, buffer.readLong())
        assertEquals(0L, buffer.readLong(offset = 8))
    }

    @Test
    fun nativeFunctionWritesIntoOurBuffer() = withArena { arena ->
        // C: char *strcpy(char *dst, const char *src);
        // Note: variadic functions (printf & friends) are deliberately not part
        // of these examples — on macOS arm64 varargs are passed on the stack,
        // so they need explicit API support (a later milestone).
        val strcpy = libc.function("strcpy", CType.Pointer, CType.Pointer, CType.Pointer)

        val dst = arena.allocate(32)
        val returned = strcpy(dst, arena.allocateCString("written by libc"))

        assertEquals("written by libc", dst.readCString())
        assertEquals(dst, returned)
    }

    @Test
    fun missingSymbolThrows() {
        assertFailsWith<MissingSymbolException> {
            libc.function("definitely_not_a_real_symbol_42", CType.Void)
        }
    }

    @Test
    fun stringMarshalling() {
        // C: int atoi(const char *); size_t strlen(const char *);
        val atoi = libc.function("atoi", CType.I32, CType.Str)
        assertEquals(42, atoi("42"))

        val strlen = libc.function("strlen", CType.I64, CType.Str)
        assertEquals(12L, strlen("hello invokt"))
    }

    @Test
    fun stringReturn() {
        // C: char *strerror(int);
        val strerror = libc.function("strerror", CType.Str, CType.I32)

        assertTrue(strerror(0).isNotEmpty())
    }

    @Test
    fun unsignedTypes() {
        // C: unsigned long strtoul(const char *, char **, int);
        val strtoul = libc.function("strtoul", CType.U64, CType.Str, CType.Pointer, CType.I32)

        assertEquals(4294967295uL, strtoul("4294967295", Ptr.NULL, 10))
        assertEquals(255uL, strtoul("ff", Ptr.NULL, 16))
    }

    @Test
    fun variadicFunction() = withArena { arena ->
        // C: int snprintf(char *, size_t, const char *, ...);
        // fixedArgs = 3 is essential: macOS arm64 passes varargs on the stack,
        // so the binding must know where the fixed parameters end.
        val snprintf = libc.function(
            "snprintf", CType.I32,
            CType.Pointer, CType.U64, CType.Str, CType.I32, CType.Str,
            fixedArgs = 3,
        )

        val buf = arena.allocate(64)
        val written = snprintf(buf, 64uL, "%d-%s", 7, "invokt")

        assertEquals("7-invokt", buf.readCString())
        assertEquals(8, written)
    }

    @Test
    fun wrongArgumentCountThrows() {
        val abs = libc.function("abs", CType.I32, CType.I32)

        assertFailsWith<IllegalArgumentException> { abs() }
        assertFailsWith<IllegalArgumentException> { abs(1, 2) }
    }

    @Test
    fun wrongArgumentTypeThrows() {
        val abs = libc.function("abs", CType.I32, CType.I32)

        assertFailsWith<IllegalArgumentException> { abs("42") }
        assertFailsWith<IllegalArgumentException> { abs(42L) } // no implicit narrowing
        assertFailsWith<IllegalArgumentException> { abs(null) }
    }

    @Test
    fun symbolLookup() {
        val strlen = libc.symbolOrNull("strlen")
        assertNotNull(strlen)
        assertFalse(strlen.isNull)

        assertNull(libc.symbolOrNull("definitely_not_a_real_symbol_42"))
    }

    @Test
    fun openLibraryByName() {
        openLibrary(zlibName).use { zlib ->
            // C: const char *zlibVersion(void);
            val zlibVersion = zlib.function("zlibVersion", CType.Pointer)

            val version = zlibVersion().readCString()

            assertTrue(version.isNotEmpty())
        }
    }

    @Test
    fun openingMissingLibraryThrows() {
        assertFailsWith<LibraryLoadException> {
            openLibrary(missingLibName)
        }
    }
}
