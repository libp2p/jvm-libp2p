package io.libp2p.etc.types

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class ByteArrayExtTest {

    @Test
    fun numberEncodingTest() {
        val ushorts = listOf(0x0, 0x1, 0x7F, 0x80, 0xFF, 0x0100, 0x0101, 0x0102, 0x7FFF, 0x8000, 0xFFFF)
        ushorts.forEach {
            Assertions.assertEquals(it, it.uShortToBytesBigEndian().toUShortBigEndian())
        }
        val uints = ushorts + listOf(0x10000, 0x7FFFFFFF, 0x80000000.toInt(), 0xFFFFFFFF.toInt())
        uints.forEach {
            Assertions.assertEquals(it, it.toBytesBigEndian().toIntBigEndian())
        }
        val ulongs = uints.map { it.toLong() } + listOf(0x100000000, 0x7FFFFFFFFFFFFFFF, Long.MIN_VALUE, -1)
        ulongs.forEach {
            Assertions.assertEquals(it, it.toBytesBigEndian().toLongBigEndian())
        }
    }
}
