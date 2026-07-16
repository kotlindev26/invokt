package io.invokt

/*
 * Raw, unchecked access to native memory.
 *
 * These are the FFI equivalent of C pointer dereferences: no bounds checks,
 * no lifetime checks. Multi-byte accesses use native byte order and require
 * naturally aligned addresses.
 */

expect fun Ptr.readByte(offset: Long = 0L): Byte
expect fun Ptr.readShort(offset: Long = 0L): Short
expect fun Ptr.readInt(offset: Long = 0L): Int
expect fun Ptr.readLong(offset: Long = 0L): Long
expect fun Ptr.readFloat(offset: Long = 0L): Float
expect fun Ptr.readDouble(offset: Long = 0L): Double
expect fun Ptr.readPtr(offset: Long = 0L): Ptr

expect fun Ptr.writeByte(value: Byte, offset: Long = 0L)
expect fun Ptr.writeShort(value: Short, offset: Long = 0L)
expect fun Ptr.writeInt(value: Int, offset: Long = 0L)
expect fun Ptr.writeLong(value: Long, offset: Long = 0L)
expect fun Ptr.writeFloat(value: Float, offset: Long = 0L)
expect fun Ptr.writeDouble(value: Double, offset: Long = 0L)
expect fun Ptr.writePtr(value: Ptr, offset: Long = 0L)

/**
 * Reads a NUL-terminated, UTF-8 encoded C string starting at this address.
 */
expect fun Ptr.readCString(): String
