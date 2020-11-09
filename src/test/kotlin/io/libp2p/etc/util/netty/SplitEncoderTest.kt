package io.libp2p.etc.util.netty

import io.libp2p.etc.types.toByteArray
import io.libp2p.etc.types.toByteBuf
import io.netty.buffer.ByteBuf
import io.netty.channel.embedded.EmbeddedChannel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets.UTF_8

class SplitEncoderTest {

    @Test
    fun test1() {
        val buf = "0123456".toByteArray().toByteBuf()
        val channel = EmbeddedChannel(SplitEncoder(3))
        assert(channel.writeOutbound(buf))
        assertEquals("012", channel.readOutbound<ByteBuf>().toByteArray().toString(UTF_8))
        assertEquals("345", channel.readOutbound<ByteBuf>().toByteArray().toString(UTF_8))
        assertEquals("6", channel.readOutbound<ByteBuf>().toByteArray().toString(UTF_8))
        assertNull(channel.readOutbound<ByteBuf>())
    }

    @Test
    fun test2() {
        val buf = "012345".toByteArray().toByteBuf()
        val channel = EmbeddedChannel(SplitEncoder(3))
        assert(channel.writeOutbound(buf))
        assertEquals("012", channel.readOutbound<ByteBuf>().toByteArray().toString(UTF_8))
        assertEquals("345", channel.readOutbound<ByteBuf>().toByteArray().toString(UTF_8))
        assertNull(channel.readOutbound<ByteBuf>())
    }

    @Test
    fun test3() {
        val buf = "012".toByteArray().toByteBuf()
        val channel = EmbeddedChannel(SplitEncoder(3))
        assert(channel.writeOutbound(buf))
        assertEquals("012", channel.readOutbound<ByteBuf>().toByteArray().toString(UTF_8))
        assertNull(channel.readOutbound<ByteBuf>())
    }

    @Test
    fun test4() {
        val buf = "01".toByteArray().toByteBuf()
        val channel = EmbeddedChannel(SplitEncoder(3))
        assert(channel.writeOutbound(buf))
        assertEquals("01", channel.readOutbound<ByteBuf>().toByteArray().toString(UTF_8))
        assertNull(channel.readOutbound<ByteBuf>())
    }

    @Test
    fun testSemiFilledBuf() {
        val buf = "012345".toByteArray().toByteBuf()
        buf.readByte()
        val channel = EmbeddedChannel(SplitEncoder(3))
        assert(channel.writeOutbound(buf))
        assertEquals("123", channel.readOutbound<ByteBuf>().toByteArray().toString(UTF_8))
        assertEquals("45", channel.readOutbound<ByteBuf>().toByteArray().toString(UTF_8))
        assertNull(channel.readOutbound<ByteBuf>())
    }
}
