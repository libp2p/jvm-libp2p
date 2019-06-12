package io.libp2p.core.types

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.IllegalStateException
import java.nio.charset.StandardCharsets

class UvarintTest {

    @Test
    fun testEncodeDecode() {
        val buf = Unpooled.buffer()
        buf.writeUvarint(42L)
        assertEquals(42L, buf.readUvarint())
    }

    @Test
    fun testIterateIntegers() {
        for (i in 0 .. 10000L) {
            val buf = Unpooled.buffer()
            buf.writeUvarint(i);
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
    fun testDecodeInvalid() {
        val buf = Unpooled.buffer()
        buf.writeBytes(ByteArray(11) { 0x81.toByte()})

        val exception = assertThrows<IllegalStateException> {buf.readUvarint()}
        assertEquals("uvarint too long", exception.message)
    }
}