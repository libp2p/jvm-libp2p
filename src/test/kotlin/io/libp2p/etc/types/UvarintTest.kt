package io.libp2p.etc.types

import io.netty.buffer.Unpooled
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class UvarintTest {

    @Test
    fun testEncodeDecode() {
        val buf = Unpooled.buffer()
        buf.writeUvarint(42L)
        assertEquals(42L, buf.readUvarint())
    }

    @Test
    fun testIterateIntegers() {
        for (i in 0..10000L) {
            val buf = Unpooled.buffer()
            buf.writeUvarint(i)
            assertEquals(i, buf.readUvarint())
            assertFalse(buf.isReadable)
        }
    }

    @Test
    fun testEncodeDecodeMaxLong() {
        val buf = Unpooled.buffer()
        buf.writeUvarint(Long.MAX_VALUE)
        assertEquals(Long.MAX_VALUE, buf.readUvarint())
    }

    @Test
    fun testDecodeTooManyBytes() {
        // Helper function for creating uvarint buffer.
        fun byteArrayOfInts(vararg ints: Int) = ByteArray(ints.size) {
            pos ->
            ints[pos].toByte()
        }

        // Try to decode a uvarint with 10 bytes.
        val arr: ByteArray = byteArrayOfInts(
            Integer.parseInt("10000000", 2), // 1
            Integer.parseInt("10000000", 2), // 2
            Integer.parseInt("10000000", 2), // 3
            Integer.parseInt("10000000", 2), // 4
            Integer.parseInt("10000000", 2), // 5
            Integer.parseInt("10000000", 2), // 6
            Integer.parseInt("10000000", 2), // 7
            Integer.parseInt("10000000", 2), // 8
            Integer.parseInt("10000000", 2), // 9
            Integer.parseInt("00000001", 2) // 10
        )
        val buf = Unpooled.copiedBuffer(arr)
        val exception = assertThrows<IllegalStateException> { buf.readUvarint() }
        assertEquals("uvarint too long", exception.message)
    }

    @Test
    fun testEncodeNegativeValue() {
        val buf = Unpooled.buffer()
        val exception = assertThrows<IllegalArgumentException> { buf.writeUvarint(-1) }
        assertEquals("uvarint value must be positive", exception.message)
    }
}
