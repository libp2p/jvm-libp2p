package io.libp2p.core.security.secio

import io.libp2p.core.crypto.KEY_TYPE
import io.libp2p.core.crypto.generateKeyPair
import io.libp2p.core.protocol.Negotiator
import io.libp2p.core.protocol.ProtocolSelect
import io.libp2p.core.types.toByteArray
import io.libp2p.core.types.toByteBuf
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Created by Anton Nashatyrev on 19.06.2019.
 */

class SecIoSecureChannelTest {

    @Test
    fun test1() {
        val (privKey1, pubKey1) = generateKeyPair(KEY_TYPE.ECDSA)
        val (privKey2, pubKey2) = generateKeyPair(KEY_TYPE.ECDSA)


        var rec1: String? = null
        var rec2: String? = null
        val latch = CountDownLatch(2)
        val eCh1 = TestChannel(LoggingHandler("#1", LogLevel.ERROR),
            Negotiator.createInitializer(true, "/secio/1.0.0"),
            ProtocolSelect(listOf(SecIoSecureChannel(privKey1))),
            object : TestHandler("1") {
                override fun channelActive(ctx: ChannelHandlerContext) {
                    super.channelActive(ctx)
                    ctx.writeAndFlush("Hello World from $name".toByteArray().toByteBuf())
                }

                override fun channelRead(ctx: ChannelHandlerContext, msg: Any?) {
                    msg as ByteBuf
                    rec1 = msg.toByteArray().toString(StandardCharsets.UTF_8)
                    println("==$name== read: $rec1")
                    latch.countDown()
                }
            })
        val eCh2 = TestChannel(
            LoggingHandler("#2", LogLevel.ERROR),
            Negotiator.createInitializer(true, "/secio/1.0.0"),
            ProtocolSelect(listOf(SecIoSecureChannel(privKey2))),
            object : TestHandler("2") {
                override fun channelActive(ctx: ChannelHandlerContext) {
                    super.channelActive(ctx)
                    ctx.writeAndFlush("Hello World from $name".toByteArray().toByteBuf())
                }

                override fun channelRead(ctx: ChannelHandlerContext, msg: Any?) {
                    msg as ByteBuf
                    rec2 = msg.toByteArray().toString(StandardCharsets.UTF_8)
                    println("==$name== read: $rec2")
                    latch.countDown()
                }
            })
        interConnect(eCh1, eCh2)

        latch.await(10, TimeUnit.SECONDS)

        Assertions.assertEquals("Hello World from 1", rec2)
        Assertions.assertEquals("Hello World from 2", rec1)
    }
}

fun interConnect(ch1: TestChannel, ch2: TestChannel) {
    ch1.connect(ch2)
    ch2.connect(ch1)
}

class TestChannel(vararg handlers: ChannelHandler?) : EmbeddedChannel(*handlers) {
    var link: TestChannel? = null
    val executor = Executors.newSingleThreadExecutor()

    @Synchronized
    fun connect(other: TestChannel) {
        link = other
        outboundMessages().forEach(this::send)
    }

    @Synchronized
    override fun handleOutboundMessage(msg: Any?) {
        super.handleOutboundMessage(msg)
        if (link != null) {
            send(msg!!)
        }
    }

    fun send(msg: Any) {
        executor.execute {
            println("---- link!!.writeInbound")
            link!!.writeInbound(msg)
        }
    }
}