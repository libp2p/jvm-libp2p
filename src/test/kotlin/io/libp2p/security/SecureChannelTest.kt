package io.libp2p.security

import io.libp2p.core.crypto.KEY_TYPE
import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.crypto.generateKeyPair
import io.libp2p.core.multistream.Mode
import io.libp2p.core.multistream.ProtocolMatcher
import io.libp2p.core.security.SecureChannel
import io.libp2p.etc.types.toByteArray
import io.libp2p.etc.types.toByteBuf
import io.libp2p.multistream.Negotiator
import io.libp2p.multistream.ProtocolSelect
import io.libp2p.tools.TestChannel
import io.libp2p.tools.TestChannel.Companion.interConnect
import io.libp2p.tools.TestHandler
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import io.netty.util.ResourceLeakDetector
import org.apache.logging.log4j.LogManager
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

typealias SecureChannelCtor = (PrivKey) -> SecureChannel

abstract class SecureChannelTest(
    val secureChannelCtor: SecureChannelCtor,
    val announce: String
) {
    init {
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID)
    }

    @Test
    fun secureInterconnect() {
        val (privKey1, _) = generateKeyPair(KEY_TYPE.ECDSA)
        val (privKey2, _) = generateKeyPair(KEY_TYPE.ECDSA)

        val latch = CountDownLatch(2)

        val protocolSelect1 = makeSelector(privKey1)
        val protocolSelect2 = makeSelector(privKey2)

        val eCh1 = makeChannel("#1", true, protocolSelect1)
        val eCh2 = makeChannel("#2", false, protocolSelect2)

        logger.debug("Connecting channels...")
        interConnect(eCh1, eCh2)

        logger.debug("Waiting for negotiation to complete...")
        protocolSelect1.selectedFuture.get(10, TimeUnit.SECONDS)
        protocolSelect2.selectedFuture.get(10, TimeUnit.SECONDS)
        logger.debug("Secured!")

        val handler1 = SecureChannelTestHandler("1", latch)
        val handler2 = SecureChannelTestHandler("2", latch)

        eCh1.pipeline().addLast(handler1)
        eCh2.pipeline().addLast(handler2)

        eCh1.pipeline().fireChannelActive()
        eCh2.pipeline().fireChannelActive()

        latch.await(10, TimeUnit.SECONDS)

        Assertions.assertEquals("Hello World from 1", handler2.received)
        Assertions.assertEquals("Hello World from 2", handler1.received)

        System.gc()
        Thread.sleep(500)
        System.gc()
        Thread.sleep(500)
        System.gc()
    } // secureInterconnect

    fun makeSelector(key: PrivKey) = ProtocolSelect(listOf(secureChannelCtor(key)))

    fun makeChannel(
        name: String,
        initiator: Boolean,
        selector: ChannelInboundHandlerAdapter
    ): TestChannel {
        val negotiator = if (initiator) {
            Negotiator.createRequesterInitializer(announce)
        } else {
            Negotiator.createResponderInitializer(listOf(ProtocolMatcher(Mode.STRICT, announce)))
        }

        return TestChannel(
            name,
            initiator,
            LoggingHandler(name, LogLevel.ERROR),
            negotiator,
            selector
        )
    } // makeChannel

    class SecureChannelTestHandler(name: String, val latch: CountDownLatch) : TestHandler(name) {
        lateinit var received: String

        override fun channelActive(ctx: ChannelHandlerContext) {
            super.channelActive(ctx)
            ctx.writeAndFlush("Hello World from $name".toByteArray().toByteBuf())
        }

        override fun channelRead(ctx: ChannelHandlerContext, msg: Any?) {
            msg as ByteBuf
            received = msg.toByteArray().toString(StandardCharsets.UTF_8)
            logger.debug("==$name== read: $received")
            latch.countDown()
        }
    } // SecureChannelTestHandler

    companion object {
        private val logger = LogManager.getLogger(SecureChannelTest::class.java)
    }
} // class SecureChannelTest
