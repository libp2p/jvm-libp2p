package io.libp2p.core.mux

import io.libp2p.core.Libp2pException
import io.libp2p.core.mux.MultistreamFrame.Flag.CLOSE
import io.libp2p.core.mux.MultistreamFrame.Flag.DATA
import io.libp2p.core.mux.MultistreamFrame.Flag.OPEN
import io.libp2p.core.types.fromHex
import io.libp2p.core.types.toByteArray
import io.libp2p.core.types.toByteBuf
import io.libp2p.core.types.toHex
import io.libp2p.core.util.netty.multiplex.MultiplexId
import io.netty.buffer.ByteBuf
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelInitializer
import io.netty.channel.embedded.EmbeddedChannel
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * Created by Anton Nashatyrev on 09.07.2019.
 */
class MultistreamHandlerTest {

    @Test
    fun simpleTest1() {
        class TestHandler : ChannelInboundHandlerAdapter() {
            val inboundMessages = mutableListOf<ByteBuf>()

            override fun channelInactive(ctx: ChannelHandlerContext?) {
                println("MultistreamHandlerTest.channelInactive")
            }

            override fun channelRead(ctx: ChannelHandlerContext?, msg: Any?) {
                println("MultistreamHandlerTest.channelRead")
                inboundMessages += msg as ByteBuf
            }

            override fun channelUnregistered(ctx: ChannelHandlerContext?) {
                println("MultistreamHandlerTest.channelUnregistered")
            }

            override fun channelActive(ctx: ChannelHandlerContext?) {
                println("MultistreamHandlerTest.channelActive")
            }

            override fun channelRegistered(ctx: ChannelHandlerContext?) {
                println("MultistreamHandlerTest.channelRegistered")
            }

            override fun channelReadComplete(ctx: ChannelHandlerContext?) {
                println("MultistreamHandlerTest.channelReadComplete")
            }

            override fun handlerAdded(ctx: ChannelHandlerContext?) {
                println("MultistreamHandlerTest.handlerAdded")
            }

            override fun exceptionCaught(ctx: ChannelHandlerContext?, cause: Throwable?) {
                println("MultistreamHandlerTest.exceptionCaught")
            }

            override fun handlerRemoved(ctx: ChannelHandlerContext?) {
                println("MultistreamHandlerTest.handlerRemoved")
            }
        }
        val childHandlers = mutableListOf<TestHandler>()
        val multistreamHandler = MultistreamHandler(object : ChannelInitializer<Channel>() {
            override fun initChannel(ch: Channel) {
                println("New child channel created")
                val handler = TestHandler()
                ch.pipeline().addLast(handler)
                childHandlers += handler
            }
        })

        val ech = EmbeddedChannel(multistreamHandler)
        ech.writeInbound(MultistreamFrame(MultiplexId(12), OPEN))
        ech.writeInbound(MultistreamFrame(MultiplexId(12), DATA, "22".fromHex().toByteBuf()))
        Assertions.assertEquals(1, childHandlers.size)
        Assertions.assertEquals(1, childHandlers[0].inboundMessages.size)
        Assertions.assertEquals("22", childHandlers[0].inboundMessages[0].toByteArray().toHex())
        ech.writeInbound(MultistreamFrame(MultiplexId(12), DATA, "23".fromHex().toByteBuf()))
        Assertions.assertEquals(1, childHandlers.size)
        Assertions.assertEquals(2, childHandlers[0].inboundMessages.size)
        Assertions.assertEquals("23", childHandlers[0].inboundMessages[1].toByteArray().toHex())

        ech.writeInbound(MultistreamFrame(MultiplexId(22), OPEN))
        ech.writeInbound(MultistreamFrame(MultiplexId(22), DATA, "33".fromHex().toByteBuf()))
        Assertions.assertEquals(2, childHandlers.size)
        Assertions.assertEquals(1, childHandlers[1].inboundMessages.size)
        Assertions.assertEquals("33", childHandlers[1].inboundMessages[0].toByteArray().toHex())

        ech.writeInbound(MultistreamFrame(MultiplexId(12), DATA, "24".fromHex().toByteBuf()))
        Assertions.assertEquals(2, childHandlers.size)
        Assertions.assertEquals(3, childHandlers[0].inboundMessages.size)
        Assertions.assertEquals("24", childHandlers[0].inboundMessages[2].toByteArray().toHex())

        ech.writeInbound(MultistreamFrame(MultiplexId(22), DATA, "34".fromHex().toByteBuf()))
        Assertions.assertEquals(2, childHandlers.size)
        Assertions.assertEquals(2, childHandlers[1].inboundMessages.size)
        Assertions.assertEquals("34", childHandlers[1].inboundMessages[1].toByteArray().toHex())

        ech.writeInbound(MultistreamFrame(MultiplexId(22), CLOSE))
        Assertions.assertThrows(Libp2pException::class.java) {
            ech.writeInbound(MultistreamFrame(MultiplexId(22), DATA, "34".fromHex().toByteBuf()))
        }

        ech.close().await()
    }
}