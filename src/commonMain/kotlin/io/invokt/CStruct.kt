package io.invokt

/**
 * Describes the memory layout of a C struct from its field types, following
 * the standard C rules for 64-bit ABIs: every field is aligned to its natural
 * alignment, the struct size is padded to a multiple of the largest alignment.
 *
 * ```
 * // struct { int32_t a; char b; double c; }
 * val layout = CStruct(CType.I32, CType.I8, CType.F64)
 * val p = arena.allocate(layout)
 * p.writeInt(1, layout.offset(0))
 * p.writeDouble(2.5, layout.offset(2))
 * ```
 *
 * This is a descriptor for pointer-based access ([Ptr.readInt] & friends) —
 * passing structs *by value* is not supported yet. Nested structs can be
 * modelled by inlining their fields.
 */
class CStruct(vararg fields: CType<*>) {

    val fields: List<CType<*>> = fields.toList()

    private val offsets: LongArray

    /** Total size in bytes, including trailing padding. */
    val size: Long

    /** Alignment requirement in bytes (that of the widest field). */
    val alignment: Long

    init {
        require(fields.isNotEmpty()) { "a struct needs at least one field" }
        offsets = LongArray(fields.size)
        var cursor = 0L
        var maxAlignment = 1L
        fields.forEachIndexed { i, field ->
            require(field != CType.Void) { "field $i: Void is not a value type" }
            val fieldSize = field.byteSize
            val fieldAlignment = fieldSize // natural alignment == size for all scalar types
            cursor = cursor.alignUp(fieldAlignment)
            offsets[i] = cursor
            cursor += fieldSize
            maxAlignment = maxOf(maxAlignment, fieldAlignment)
        }
        alignment = maxAlignment
        size = cursor.alignUp(maxAlignment)
    }

    /** Byte offset of the field at [index] (declaration order). */
    fun offset(index: Int): Long = offsets[index]

    private fun Long.alignUp(alignment: Long): Long = (this + alignment - 1) and (alignment - 1).inv()
}

/** Allocates zeroed memory for one instance of [struct] in this arena. */
fun Arena.allocate(struct: CStruct): Ptr = allocate(struct.size, struct.alignment)

/** Allocates zeroed memory for [count] consecutive instances of [struct]. */
fun Arena.allocateArray(struct: CStruct, count: Int): Ptr {
    require(count > 0) { "count must be positive, was $count" }
    // Element stride equals size: C array elements are laid out back to back,
    // and size is already padded to the struct's alignment.
    return allocate(struct.size * count, struct.alignment)
}
