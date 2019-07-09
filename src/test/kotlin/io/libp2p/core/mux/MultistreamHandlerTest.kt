package io.libp2p.core.mux

import io.libp2p.core.types.fromHex
import io.libp2p.core.types.toByteArray
import io.libp2p.core.types.toByteBuf
import io.libp2p.core.types.toHex
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.embedded.EmbeddedChannel
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * Created by Anton Nashatyrev on 09.07.2019.
 */
class MultistreamHandlerTest {

    @Test
    fun simpleTest1() {
        var childMsg: ByteBuf? = null
        val multistreamHandler = MultistreamHandler(object : ChannelInboundHandlerAdapter() {
            override fun channelInactive(ctx: ChannelHandlerContext?) {
                println("MultistreamHandlerTest.channelInactive")
            }

            override fun channelRead(ctx: ChannelHandlerContext?, msg: Any?) {
                println("MultistreamHandlerTest.channelRead")
                childMsg = msg as ByteBuf?
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
        })

        val ech = EmbeddedChannel(multistreamHandler)
        ech.writeInbound(MultistreamFrame("22".fromHex().toByteBuf(), MultistreamId(12)))
        Assertions.assertEquals("22", childMsg!!.toByteArray().toHex())
    }
}