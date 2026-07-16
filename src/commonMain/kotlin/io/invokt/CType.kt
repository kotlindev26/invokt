package io.invokt

/**
 * Describes a native value type in a function signature.
 *
 * The type parameter [T] is the Kotlin type used to represent the native value
 * on the API surface, so signatures stay type-safe in common code.
 *
 * Deliberately uses explicit-width names (I32, I64, ...) instead of C names
 * (`int`, `long`, ...) because C integer widths are platform-dependent.
 * Mapping C's `long` to [I32] or [I64] is the caller's (or later, the code
 * generator's) responsibility.
 */
sealed interface CType<T> {

    /** No value; only valid as a return type. */
    data object Void : CType<Unit>

    /** Signed 8-bit integer (`int8_t`, `char`). */
    data object I8 : CType<Byte>

    /** Signed 16-bit integer (`int16_t`, `short`). */
    data object I16 : CType<Short>

    /** Signed 32-bit integer (`int32_t`, `int`). */
    data object I32 : CType<Int>

    /** Signed 64-bit integer (`int64_t`, `long long`, `long` on LP64). */
    data object I64 : CType<Long>

    /** 32-bit IEEE 754 float (`float`). */
    data object F32 : CType<Float>

    /** 64-bit IEEE 754 float (`double`). */
    data object F64 : CType<Double>

    /** An untyped pointer (`void*`, `char*`, any `T*`). */
    data object Pointer : CType<Ptr>

    /** C `bool` / `_Bool` (one byte, 0 or 1). */
    data object Bool : CType<Boolean>

    /** Unsigned 8-bit integer (`uint8_t`, `unsigned char`). */
    data object U8 : CType<UByte>

    /** Unsigned 16-bit integer (`uint16_t`, `unsigned short`). */
    data object U16 : CType<UShort>

    /** Unsigned 32-bit integer (`uint32_t`, `unsigned int`). */
    data object U32 : CType<UInt>

    /** Unsigned 64-bit integer (`uint64_t`, `unsigned long long`, `size_t` on LP64/LLP64). */
    data object U64 : CType<ULong>

    /**
     * A `const char*` marshalled as [String]: arguments are copied into a
     * temporary NUL-terminated UTF-8 buffer that only lives for the call;
     * returned pointers are read as UTF-8 (must not be NULL). Use [Pointer]
     * when the callee keeps the pointer or NULL must be representable.
     */
    data object Str : CType<String>
}

/** Size in bytes of a value of this type — fixed, since invokt targets 64-bit ABIs. */
val CType<*>.byteSize: Long
    get() = when (this) {
        CType.Void -> throw IllegalArgumentException("Void has no size")
        CType.I8, CType.U8, CType.Bool -> 1L
        CType.I16, CType.U16 -> 2L
        CType.I32, CType.U32, CType.F32 -> 4L
        CType.I64, CType.U64, CType.F64, CType.Pointer, CType.Str -> 8L
    }
