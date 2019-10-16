package io.libp2p.security.noise

import io.libp2p.core.crypto.KEY_TYPE
import io.libp2p.core.crypto.generateKeyPair
import io.libp2p.core.multistream.Mode
import io.libp2p.core.multistream.ProtocolMatcher
import io.libp2p.etc.types.toByteArray
import io.libp2p.multistream.Negotiator
import io.libp2p.multistream.ProtocolSelect
import io.libp2p.tools.TestChannel.Companion.interConnect
import io.libp2p.tools.TestHandler
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.config.Configurator
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NoiseSecureChannelTest {
    @Test
    fun testNoiseChannelThroughEmbedded() {
        // test Noise secure channel through embedded channels
        logger.debug("Beginning embedded test")

        // node keys
        val (privKeyAlicePeer, _) = generateKeyPair(KEY_TYPE.ECDSA)
        val (privKeyBobPeer, _) = generateKeyPair(KEY_TYPE.ECDSA)

        // noise keys
        val ch1 = NoiseXXSecureChannel(privKeyAlicePeer)
        val ch2 = NoiseXXSecureChannel(privKeyBobPeer)

        val protocolSelect1 = ProtocolSelect(listOf(ch1))
        val protocolSelect2 = ProtocolSelect(listOf(ch2))

        val eCh1 = io.libp2p.tools.TestChannel(
            "#1", true, LoggingHandler("#1", LogLevel.ERROR),
            Negotiator.createRequesterInitializer(NoiseXXSecureChannel.announce),
            protocolSelect1
        )

        val eCh2 = io.libp2p.tools.TestChannel(
            "#2", false,
            LoggingHandler("#2", LogLevel.ERROR),
            Negotiator.createResponderInitializer(listOf(ProtocolMatcher(Mode.STRICT, NoiseXXSecureChannel.announce))),
            protocolSelect2
        )

        logger.debug("Connecting initial channels")
        interConnect(eCh1, eCh2)

        logger.debug("Waiting for negotiation to complete...")
        protocolSelect1.selectedFuture.get(10, TimeUnit.SECONDS)
        protocolSelect2.selectedFuture.get(10, TimeUnit.SECONDS)
        logger.debug("Secured!")

        var rec1: String? = ""
        var rec2: String? = ""
        val latch = CountDownLatch(2)

        // Setup alice's pipeline
        eCh1.pipeline().addLast(object : TestHandler("1") {
            override fun channelRead(ctx: ChannelHandlerContext, msg: Any?) {
                msg as ByteBuf
                rec1 = String(msg.toByteArray())
                logger.debug("==$name== read: $msg")
                latch.countDown()
            }
        })

        // Setup bob's pipeline
        eCh2.pipeline().addLast(object : TestHandler("2") {
            override fun channelRead(ctx: ChannelHandlerContext, msg: Any?) {
                msg as ByteBuf
                rec2 = String(msg.toByteArray())
                logger.debug("==$name== read: $msg")
                latch.countDown()
            }
        })

        eCh1.writeAndFlush(Unpooled.wrappedBuffer("Hello World from 1".toByteArray()))
        eCh2.writeAndFlush(Unpooled.wrappedBuffer("Hello World from 2".toByteArray()))

        latch.await(5, TimeUnit.SECONDS)

        Assertions.assertEquals("Hello World from 1", rec2)
        Assertions.assertEquals("Hello World from 2", rec1)

        System.gc()
        Thread.sleep(500)
        System.gc()
        Thread.sleep(500)
        System.gc()
    }

    companion object {
        private val logger = LogManager.getLogger(NoiseSecureChannelTest::class.java.name)
    }

    init {
        Configurator.setLevel(NoiseSecureChannelTest::class.java.name, Level.DEBUG)
    }
}