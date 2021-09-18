package io.libp2p.etc.util.netty

import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.DecoderException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class StringSuffixCodecTest {
    val channel = EmbeddedChannel(StringSuffixCodec('\n'))

    @Test
    fun encodeAppendTrailingChar() {
        channel.writeOutbound("theMessage")
        val result = channel.readOutbound<String>()
        assertEquals(result, "theMessage\n")
    }

    @Test
    fun decodeStripsTrailingChar() {
        channel.writeInbound("theMessage\n")
        val result = channel.readInbound<String>()
        assertEquals(result, "theMessage")
    }

    @Test
    fun decodeOnlyStripsSingleTrailingChar() {
        channel.writeInbound("theMessage\n\n")
        val result = channel.readInbound<String>()
        assertEquals(result, "theMessage\n")
    }

    @Test
    fun decodeThrowsWhenTrailingCharMissing() {
        assertThrows<DecoderException> { channel.writeInbound("theMessage") }
    }
}
