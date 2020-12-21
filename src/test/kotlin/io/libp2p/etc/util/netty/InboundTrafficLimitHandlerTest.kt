package io.libp2p.etc.util.netty

import io.libp2p.core.ProtocolViolationException
import io.libp2p.etc.types.toByteArray
import io.libp2p.etc.types.toByteBuf
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.embedded.EmbeddedChannel
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Index.atIndex
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class InboundTrafficLimitHandlerTest {

    class ExceptionHandler : ChannelInboundHandlerAdapter() {
        val exceptions = mutableListOf<Throwable>()
        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            exceptions += cause
        }
    }

    class ConsumingHandler : ChannelInboundHandlerAdapter() {
        val messages = mutableListOf<ByteArray>()
        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            msg as ByteBuf
            messages += msg.toByteArray()
            msg.release()
        }
    }

    val exceptionHandler = ExceptionHandler()
    val messageHandler = ConsumingHandler()

    val maxSize = 100
    val channel = EmbeddedChannel(InboundTrafficLimitHandler(maxSize.toLong()), messageHandler, exceptionHandler)
    val byteBufs = mutableListOf<ByteBuf>()

    fun buf(arr: ByteArray) = arr.toByteBuf().also { byteBufs += it }

    @AfterEach
    fun check() {
        byteBufs.forEach {
            assertThat(it.refCnt()).isEqualTo(0)
        }
    }

    @Test
    fun `test zero limit`() {
        val exceptionHandler = ExceptionHandler()
        val ch = EmbeddedChannel(InboundTrafficLimitHandler(0), exceptionHandler)
        assertThat(ch.isOpen).isTrue()
        ch.writeInbound(buf(byteArrayOf(1)))
        assertThat(ch.inboundMessages()).isEmpty()
        assertThat(ch.isOpen).isFalse()
        assertThat(exceptionHandler.exceptions)
            .hasSize(1)
            .hasOnlyElementsOfType(ProtocolViolationException::class.java)
    }

    @Test
    fun `test no limit exceed`() {
        channel.writeInbound(buf(ByteArray(3) { 1 }))
        channel.writeInbound(buf(ByteArray(3) { 2 }))
        channel.writeInbound(buf(ByteArray(3) { 3 }))
        channel.close()

        assertThat(exceptionHandler.exceptions).isEmpty()
        assertThat(messageHandler.messages.flatMap { it.asList() })
            .hasSize(9)
            .contains(1, 1, 1, 2, 2, 2, 3, 3, 3)
    }

    @Test
    fun `test limit exceed with first message`() {
        channel.writeInbound(buf(ByteArray(200) { 1 }))

        assertThat(channel.isOpen).isFalse()
        assertThat(exceptionHandler.exceptions)
            .hasSize(1)
            .hasOnlyElementsOfType(ProtocolViolationException::class.java)
        assertThat(messageHandler.messages).isEmpty()
    }

    @Test
    fun `test exact limit with one message`() {
        channel.writeInbound(buf(ByteArray(maxSize) { 1 }))

        assertThat(channel.isOpen).isTrue()
        assertThat(exceptionHandler.exceptions.isEmpty())
        assertThat(messageHandler.messages.flatMap { it.asList() })
            .contains(1, atIndex(0))
            .contains(1, atIndex(99))
    }

    @Test
    fun `test exact limit with two messages`() {
        channel.writeInbound(buf(ByteArray(maxSize - 1) { 1 }))
        channel.writeInbound(buf(ByteArray(1) { 2 }))

        assertThat(channel.isOpen).isTrue()
        assertThat(exceptionHandler.exceptions.isEmpty())
        assertThat(messageHandler.messages.flatMap { it.asList() })
            .contains(1, atIndex(0))
            .contains(1, atIndex(98))
            .contains(2, atIndex(99))
    }

    @Test
    fun `test limit exceed with second message`() {
        channel.writeInbound(buf(ByteArray(maxSize) { 1 }))

        assertThat(channel.isOpen).isTrue()
        assertThat(exceptionHandler.exceptions.isEmpty())
        assertThat(messageHandler.messages).isNotEmpty()

        channel.writeInbound(buf(ByteArray(10) { 2 }))

        assertThat(channel.isOpen).isFalse()
        assertThat(exceptionHandler.exceptions)
            .hasSize(1)
            .hasOnlyElementsOfType(ProtocolViolationException::class.java)
        assertThat(messageHandler.messages).hasSize(1)
    }
}
