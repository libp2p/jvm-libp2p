package io.libp2p.etc.encode

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.math.BigInteger

class Base58Test {

    companion object {
        @JvmStatic
        fun params() = listOf(
            Arguments.of("hello world", "Hello World".toByteArray(), "JxF12TrwUP45BMd"),
            Arguments.of("number", BigInteger.valueOf(3471844090L).toByteArray(), "16Ho7Hs"),
            Arguments.of("zero 1 lengthBits", ByteArray(1), "1"),
            Arguments.of("zero 7 lengthBits", ByteArray(7), "1111111"),
            Arguments.of("nil", ByteArray(0), "")
        )
    }

    @ParameterizedTest
    @MethodSource("params")
    fun `Base58 circular encoding - decoding works`(
        @Suppress("UNUSED_PARAMETER")name: String,
        bytes: ByteArray,
        encoded: String
    ) {
        val (enc, dec) = Pair(Base58.encode(bytes), Base58.decode(encoded))
        assertArrayEquals(bytes, dec, "expected decoded value to parameter")
        assertEquals(encoded, enc, "expected encoded value to match parameter")
        assertEquals(encoded, Base58.encode(dec), "expected circular test to succeed") // re-encode.
    }
}