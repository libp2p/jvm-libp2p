package io.libp2p.mux

import io.libp2p.core.ConnectionClosedException
import io.libp2p.core.Libp2pException
import io.libp2p.core.Stream
import io.libp2p.core.StreamHandler
import io.libp2p.etc.types.fromHex
import io.libp2p.etc.types.getX
import io.libp2p.etc.types.toByteArray
import io.libp2p.etc.types.toByteBuf
import io.libp2p.etc.types.toHex
import io.libp2p.etc.util.netty.mux.MuxId
import io.libp2p.etc.util.netty.nettyInitializer
import io.libp2p.mux.MuxFrame.Flag.DATA
import io.libp2p.mux.MuxFrame.Flag.OPEN
import io.libp2p.mux.MuxFrame.Flag.RESET
import io.libp2p.tools.TestChannel
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

/**
 * Created by Anton Nashatyrev on 09.07.2019.
 */
class MultiplexHandlerTest {
    val childHandlers = mutableListOf<TestHandler>()
    lateinit var multistreamHandler: MuxHandler
    lateinit var ech: TestChannel

    @BeforeEach
    fun startMultiplexor() {
        childHandlers.clear()
        multistreamHandler = MuxHandler(
            createStreamHandler(
                nettyInitializer {
                    println("New child channel created")
                    val handler = TestHandler()
                    it.pipeline().addLast(handler)
                    childHandlers += handler
                })
        )

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
            multistreamHandler.createStream(StreamHandler.create { println("This shouldn't be displayed: parent stream is closed") })

        assertThrows(ConnectionClosedException::class.java) { staleStream.stream.getX(3.0) }
    }

    fun assertHandlerCount(count: Int) = assertEquals(count, childHandlers.size)
    fun assertLastMessage(handler: Int, msgCount: Int, msg: String) {
        val messages = childHandlers[handler].inboundMessages
        assertEquals(msgCount, messages.size)
        assertEquals(msg, messages.last())
    }

    fun openStream(id: Long) = writeFrame(id, OPEN)
    fun writeStream(id: Long, msg: String) = writeFrame(id, DATA, msg.fromHex().toByteBuf())
    fun resetStream(id: Long) = writeFrame(id, RESET)
    fun writeFrame(id: Long, flag: MuxFrame.Flag, data: ByteBuf? = null) =
        ech.writeInbound(MuxFrame(MuxId(id, true), flag, data))

    fun createStreamHandler(channelInitializer: ChannelHandler) = object : StreamHandler<Unit> {
        override fun handleStream(stream: Stream): CompletableFuture<out Unit> {
            stream.nettyChannel.pipeline().addLast(channelInitializer)
            return CompletableFuture.completedFuture(Unit)
        }
    }

    class TestHandler : ChannelInboundHandlerAdapter() {
        val inboundMessages = mutableListOf<String>()
        var ctx: ChannelHandlerContext? = null

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