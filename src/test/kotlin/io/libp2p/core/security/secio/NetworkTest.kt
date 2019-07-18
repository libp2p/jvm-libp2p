package io.libp2p.core.security.secio

import io.libp2p.core.crypto.KEY_TYPE
import io.libp2p.core.crypto.generateKeyPair
import io.libp2p.core.protocol.Negotiator
import io.libp2p.core.protocol.ProtocolSelect
import io.netty.bootstrap.Bootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.DefaultMessageSizeEstimator
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import org.apache.logging.log4j.LogManager
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

class NetworkTest {

    @Disabled
    @Test
    fun connect1() {

        val b = Bootstrap()
        b.group(NioEventLoopGroup())
        b.channel(NioSocketChannel::class.java)

        b.option(ChannelOption.SO_KEEPALIVE, true)
        b.option(ChannelOption.MESSAGE_SIZE_ESTIMATOR, DefaultMessageSizeEstimator.DEFAULT)
        b.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 15 * 1000)
        b.remoteAddress("localhost", 10000)

        val (privKey1, pubKey1) = generateKeyPair(KEY_TYPE.ECDSA)
        val protocolSelect = ProtocolSelect(listOf(SecIoSecureChannel(privKey1)))

        b.handler(object : ChannelInitializer<Channel>() {
            override fun initChannel(ch: Channel) {
                ch.pipeline().addLast(LoggingHandler("###1", LogLevel.ERROR))
                ch.pipeline().addLast(Negotiator.createInitializer(true, "/secio/1.0.0"))
                ch.pipeline().addLast(protocolSelect)
                ch.pipeline().addLast(object : LoggingHandler("###2", LogLevel.ERROR) {
                    override fun channelActive(ctx: ChannelHandlerContext?) {
                        super.channelActive(ctx)
                    }
                })
            }
        })

        // Start the client.
        logger.info("Establishing a connection ...")
        b.connect().await()
        logger.info("Connection established.")

        Thread.sleep(10000000L)
    }

    companion object {
        private val logger = LogManager.getLogger(NetworkTest::class.java)
    }
}