package io.libp2p.mux

import io.libp2p.core.ConnectionClosedException
import io.libp2p.core.Libp2pException
import io.libp2p.core.Stream
import io.libp2p.core.StreamHandler
import io.libp2p.core.multistream.MultistreamProtocolV1
import io.libp2p.etc.types.fromHex
import io.libp2p.etc.types.getX
import io.libp2p.etc.types.toByteArray
import io.libp2p.etc.types.toByteBuf
import io.libp2p.etc.types.toHex
import io.libp2p.etc.util.netty.mux.MuxId
import io.libp2p.etc.util.netty.nettyInitializer
import io.libp2p.mux.yamux.YamuxFlags
import io.libp2p.mux.yamux.YamuxFrame
import io.libp2p.mux.yamux.YamuxHandler
import io.libp2p.mux.yamux.YamuxType
import io.libp2p.tools.TestChannel
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.DefaultChannelId
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

class YamuxHandlerTest {
    val DEFAULT_MAX_YAMUX_FRAME_DATA_LENGTH = 1 shl 20
    val dummyParentChannelId = DefaultChannelId.newInstance()
    val childHandlers = mutableListOf<TestHandler>()
    lateinit var multistreamHandler: YamuxHandler
    lateinit var ech: TestChannel

    @BeforeEach
    fun startMultiplexor() {
        childHandlers.clear()
        val streamHandler = createStreamHandler(
            nettyInitializer {
                println("New child channel created")
                val handler = TestHandler()
                it.addLastLocal(handler)
                childHandlers += handler
            }
        )
        multistreamHandler = object : YamuxHandler(
            MultistreamProtocolV1, DEFAULT_MAX_YAMUX_FRAME_DATA_LENGTH, null, streamHandler, true
        ) {
            // MuxHandler consumes the exception. Override this behaviour for testing
            override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                ctx.fireExceptionCaught(cause)
            }
        }

        ech = TestChannel("test", true, LoggingHandler(LogLevel.ERROR), multistreamHandler)
    }

    @Test
    fun singleStream() {
        openStream(12)
        assertHandlerCount(1)

        writeStream(12, "22")
        assertHandlerCount(1)
        assertEquals(1, childHandlers[0].inboundMessages.size)
        assertEquals("22", childHandlers[0].inboundMessages.last())

        writeStream(12, "44")
        assertHandlerCount(1)
        assertEquals(2, childHandlers[0].inboundMessages.size)
        assertEquals("44", childHandlers[0].inboundMessages.last())

        writeStream(12, "66")
        assertHandlerCount(1)
        assertEquals(3, childHandlers[0].inboundMessages.size)
        assertEquals("66", childHandlers[0].inboundMessages.last())
    }

    @Test
    fun `test that readComplete event is fired to child channel`() {
        openStream(12)

        assertThat(childHandlers[0].readCompleteEventCount).isZero()

        writeStream(12, "22")

        assertThat(childHandlers[0].readCompleteEventCount).isEqualTo(1)

        writeStream(12, "23")

        assertThat(childHandlers[0].readCompleteEventCount).isEqualTo(2)
    }

    @Test
    fun `test that readComplete event is fired to reading channels only`() {
        openStream(12)
        openStream(13)

        assertThat(childHandlers[0].readCompleteEventCount).isZero()
        assertThat(childHandlers[1].readCompleteEventCount).isZero()

        writeStream(12, "22")

        assertThat(childHandlers[0].readCompleteEventCount).isEqualTo(1)
        assertThat(childHandlers[1].readCompleteEventCount).isEqualTo(0)

        writeStream(13, "23")

        assertThat(childHandlers[0].readCompleteEventCount).isEqualTo(1)
        assertThat(childHandlers[1].readCompleteEventCount).isEqualTo(1)
    }

    @Test
    fun twoStreamsInterleaved() {
        openStream(12)
        writeStream(12, "22")

        assertHandlerCount(1)
        assertLastMessage(0, 1, "22")

        writeStream(12, "23")
        assertHandlerCount(1)
        assertLastMessage(0, 2, "23")

        openStream(22)
        writeStream(22, "33")
        assertHandlerCount(2)
        assertLastMessage(1, 1, "33")

        writeStream(12, "24")
        assertHandlerCount(2)
        assertLastMessage(0, 3, "24")

        writeStream(22, "34")
        assertHandlerCount(2)
        assertLastMessage(1, 2, "34")
    }

    @Test
    fun twoStreamsSequential() {
        openStream(12)
        writeStream(12, "22")

        assertHandlerCount(1)
        assertLastMessage(0, 1, "22")

        writeStream(12, "23")
        assertHandlerCount(1)
        assertLastMessage(0, 2, "23")

        writeStream(12, "24")
        assertHandlerCount(1)
        assertLastMessage(0, 3, "24")

        writeStream(12, "25")
        assertHandlerCount(1)
        assertLastMessage(0, 4, "25")

        resetStream(12)
        assertHandlerCount(1)

        openStream(22)
        writeStream(22, "33")
        assertHandlerCount(2)
        assertLastMessage(1, 1, "33")

        writeStream(22, "34")
        assertHandlerCount(2)
        assertLastMessage(1, 2, "34")

        resetStream(12)
        assertHandlerCount(2)
    }

    @Test
    fun streamIsReset() {
        openStream(22)
        assertFalse(childHandlers[0].ctx!!.channel().closeFuture().isDone)

        resetStream(22)
        assertTrue(childHandlers[0].ctx!!.channel().closeFuture().isDone)
    }

    @Test
    fun streamIsResetWhenChannelIsClosed() {
        openStream(22)
        assertFalse(childHandlers[0].ctx!!.channel().closeFuture().isDone)

        ech.close().await()

        assertTrue(childHandlers[0].ctx!!.channel().closeFuture().isDone)
    }

    @Test
    fun cantWriteToResetStream() {
        openStream(18)
        resetStream(18)

        assertThrows(Libp2pException::class.java) {
            writeStream(18, "35")
        }
    }

    @Test
    fun cantWriteToNonExistentStream() {
        assertThrows(Libp2pException::class.java) {
            writeStream(92, "35")
        }
    }

    @Test
    fun canResetNonExistentStream() {
        resetStream(99)
    }

    @Test
    fun cantOpenStreamOnClosedChannel() {
        ech.close().await()

        val staleStream =
            multistreamHandler.createStream {
                println("This shouldn't be displayed: parent stream is closed")
                CompletableFuture.completedFuture(Unit)
            }

        assertThrows(ConnectionClosedException::class.java) { staleStream.stream.getX(3.0) }
    }

    fun assertHandlerCount(count: Int) = assertEquals(count, childHandlers.size)
    fun assertLastMessage(handler: Int, msgCount: Int, msg: String) {
        val messages = childHandlers[handler].inboundMessages
        assertEquals(msgCount, messages.size)
        assertEquals(msg, messages.last())
    }

    fun openStream(id: Long) =
        ech.writeInbound(YamuxFrame(MuxId(dummyParentChannelId, id, true), YamuxType.DATA, YamuxFlags.SYN, 0))
    fun writeStream(id: Long, msg: String) =
        ech.writeInbound(YamuxFrame(MuxId(dummyParentChannelId, id, true), YamuxType.DATA, 0, msg.fromHex().size, msg.fromHex().toByteBuf()))
    fun resetStream(id: Long) =
        ech.writeInbound(YamuxFrame(MuxId(dummyParentChannelId, id, true), YamuxType.GO_AWAY, 0, 0))

    fun createStreamHandler(channelInitializer: ChannelHandler) = object : StreamHandler<Unit> {
        override fun handleStream(stream: Stream): CompletableFuture<Unit> {
            stream.pushHandler(channelInitializer)
            return CompletableFuture.completedFuture(Unit)
        }
    }

    class TestHandler : ChannelInboundHandlerAdapter() {
        val inboundMessages = mutableListOf<String>()
        var ctx: ChannelHandlerContext? = null
        var readCompleteEventCount = 0

        override fun channelInactive(ctx: ChannelHandlerContext?) {
            println("MultiplexHandlerTest.channelInactive")
        }

        override fun channelRead(ctx: ChannelHandlerContext?, msg: Any?) {
            println("MultiplexHandlerTest.channelRead")
            msg as ByteBuf
            inboundMessages += msg.toByteArray().toHex()
        }

        override fun channelUnregistered(ctx: ChannelHandlerContext?) {
            println("MultiplexHandlerTest.channelUnregistered")
        }

        override fun channelActive(ctx: ChannelHandlerContext?) {
            println("MultiplexHandlerTest.channelActive")
        }

        override fun channelRegistered(ctx: ChannelHandlerContext?) {
            println("MultiplexHandlerTest.channelRegistered")
        }

        override fun channelReadComplete(ctx: ChannelHandlerContext?) {
            readCompleteEventCount++
            println("MultiplexHandlerTest.channelReadComplete")
        }

        override fun handlerAdded(ctx: ChannelHandlerContext?) {
            println("MultiplexHandlerTest.handlerAdded")
            this.ctx = ctx
        }

        override fun exceptionCaught(ctx: ChannelHandlerContext?, cause: Throwable?) {
            println("MultiplexHandlerTest.exceptionCaught")
        }

        override fun handlerRemoved(ctx: ChannelHandlerContext?) {
            println("MultiplexHandlerTest.handlerRemoved")
        }
    }
}
