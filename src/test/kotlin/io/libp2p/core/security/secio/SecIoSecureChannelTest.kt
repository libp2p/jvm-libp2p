package io.libp2p.core.security.secio

import io.libp2p.core.crypto.KEY_TYPE
import io.libp2p.core.crypto.generateKeyPair
import io.libp2p.core.multistream.Negotiator
import io.libp2p.core.multistream.ProtocolSelect
import io.libp2p.core.types.toByteArray
import io.libp2p.core.types.toByteBuf
import io.libp2p.tools.TestChannel
import io.libp2p.tools.TestChannel.Companion.interConnect
import io.libp2p.tools.TestHandler
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import io.netty.util.ResourceLeakDetector
import org.apache.logging.log4j.LogManager
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Created by Anton Nashatyrev on 19.06.2019.
 */

class SecIoSecureChannelTest {

    init {
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID)
    }

    @Test
    fun test1() {
        val (privKey1, pubKey1) = generateKeyPair(KEY_TYPE.ECDSA)
        val (privKey2, pubKey2) = generateKeyPair(KEY_TYPE.ECDSA)

        var rec1: String? = null
        var rec2: String? = null
        val latch = CountDownLatch(2)
        val eCh1 = TestChannel("#1", true, LoggingHandler("#1", LogLevel.ERROR),
            Negotiator.createInitializer("/secio/1.0.0"),
            ProtocolSelect(listOf(SecIoSecureChannel(privKey1))),
            object : TestHandler("1") {
                override fun channelActive(ctx: ChannelHandlerContext) {
                    super.channelActive(ctx)
                    ctx.writeAndFlush("Hello World from $name".toByteArray().toByteBuf())
                }

                override fun channelRead(ctx: ChannelHandlerContext, msg: Any?) {
                    msg as ByteBuf
                    rec1 = msg.toByteArray().toString(StandardCharsets.UTF_8)
                    logger.debug("==$name== read: $rec1")
                    latch.countDown()
                }
            })
        val eCh2 = TestChannel("#2", false,
            LoggingHandler("#2", LogLevel.ERROR),
            Negotiator.createInitializer("/secio/1.0.0"),
            ProtocolSelect(listOf(SecIoSecureChannel(privKey2))),
            object : TestHandler("2") {
                override fun channelActive(ctx: ChannelHandlerContext) {
                    super.channelActive(ctx)
                    ctx.writeAndFlush("Hello World from $name".toByteArray().toByteBuf())
                }

                override fun channelRead(ctx: ChannelHandlerContext, msg: Any?) {
                    msg as ByteBuf
                    rec2 = msg.toByteArray().toString(StandardCharsets.UTF_8)
                    logger.debug("==$name== read: $rec2")
                    latch.countDown()
                }
            })
        interConnect(eCh1, eCh2)

        latch.await(10, TimeUnit.SECONDS)

        Assertions.assertEquals("Hello World from 1", rec2)
        Assertions.assertEquals("Hello World from 2", rec1)

        System.gc()
        Thread.sleep(500)
        System.gc()
        Thread.sleep(500)
        System.gc()
    }

    companion object {
        private val logger = LogManager.getLogger(SecIoSecureChannelTest::class.java)
    }
}
