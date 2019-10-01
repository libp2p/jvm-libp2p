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
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

/**
 * Created by Anton Nashatyrev on 09.07.2019.
 */
class MultiplexHandlerTest {

    @Test
    fun simpleTest1() {
        class TestHandler : ChannelInboundHandlerAdapter() {
            val inboundMessages = mutableListOf<ByteBuf>()
            var ctx: ChannelHandlerContext? = null

            override fun channelInactive(ctx: ChannelHandlerContext?) {
                println("MultiplexHandlerTest.channelInactive")
            }

            override fun channelRead(ctx: ChannelHandlerContext?, msg: Any?) {
                println("MultiplexHandlerTest.channelRead")
                inboundMessages += msg as ByteBuf
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
        val childHandlers = mutableListOf<TestHandler>()
        val multistreamHandler = MuxHandler(
            createStreamHandler(
                nettyInitializer {
                    println("New child channel created")
                    val handler = TestHandler()
                    it.pipeline().addLast(handler)
                    childHandlers += handler
                })
        )

        val ech = TestChannel("test", true, LoggingHandler(LogLevel.ERROR), multistreamHandler)
        ech.writeInbound(MuxFrame(MuxId(12, true), OPEN))
        ech.writeInbound(MuxFrame(MuxId(12, true), DATA, "22".fromHex().toByteBuf()))
        Assertions.assertEquals(1, childHandlers.size)
        Assertions.assertEquals(1, childHandlers[0].inboundMessages.size)
        Assertions.assertEquals("22", childHandlers[0].inboundMessages[0].toByteArray().toHex())
        Assertions.assertFalse(childHandlers[0].ctx!!.channel().closeFuture().isDone)
        ech.writeInbound(MuxFrame(MuxId(12, true), DATA, "23".fromHex().toByteBuf()))
        Assertions.assertEquals(1, childHandlers.size)
        Assertions.assertEquals(2, childHandlers[0].inboundMessages.size)
        Assertions.assertEquals("23", childHandlers[0].inboundMessages[1].toByteArray().toHex())

        ech.writeInbound(MuxFrame(MuxId(22, true), OPEN))
        ech.writeInbound(MuxFrame(MuxId(22, true), DATA, "33".fromHex().toByteBuf()))
        Assertions.assertEquals(2, childHandlers.size)
        Assertions.assertEquals(1, childHandlers[1].inboundMessages.size)
        Assertions.assertEquals("33", childHandlers[1].inboundMessages[0].toByteArray().toHex())
        Assertions.assertFalse(childHandlers[1].ctx!!.channel().closeFuture().isDone)
        childHandlers[1].ctx!!.channel().closeFuture().addListener {
            println("Channel #2 closed")
        }

        ech.writeInbound(MuxFrame(MuxId(12, true), DATA, "24".fromHex().toByteBuf()))
        Assertions.assertEquals(2, childHandlers.size)
        Assertions.assertEquals(3, childHandlers[0].inboundMessages.size)
        Assertions.assertEquals("24", childHandlers[0].inboundMessages[2].toByteArray().toHex())

        ech.writeInbound(MuxFrame(MuxId(22, true), DATA, "34".fromHex().toByteBuf()))
        Assertions.assertEquals(2, childHandlers.size)
        Assertions.assertEquals(2, childHandlers[1].inboundMessages.size)
        Assertions.assertEquals("34", childHandlers[1].inboundMessages[1].toByteArray().toHex())

        ech.writeInbound(MuxFrame(MuxId(22, true), RESET))
        Assertions.assertTrue(childHandlers[1].ctx!!.channel().closeFuture().isDone)
        Assertions.assertThrows(Libp2pException::class.java) {
            ech.writeInbound(MuxFrame(MuxId(22, true), DATA, "34".fromHex().toByteBuf()))
        }

        ech.close().await()

        Assertions.assertTrue(childHandlers[0].ctx!!.channel().closeFuture().isDone)

        val staleStream =
            multistreamHandler.createStream(StreamHandler.create { println("This shouldn't be displayed: parent stream is closed") })

        Assertions.assertThrows(ConnectionClosedException::class.java) { staleStream.stream.getX(3.0) }
    }

    fun createStreamHandler(channelInitializer: ChannelHandler) = object : StreamHandler<Unit> {
        override fun handleStream(stream: Stream): CompletableFuture<out Unit> {
            stream.pushHandler(channelInitializer)
            return CompletableFuture.completedFuture(Unit)
        }
    }
}