package io.libp2p.core.security.secio

import io.libp2p.core.crypto.KEY_TYPE
import io.libp2p.core.crypto.generateKeyPair
import io.libp2p.core.mplex.MplexChannelInitializer
import io.libp2p.core.protocol.Negotiator
import io.libp2p.core.protocol.ProtocolSelect
import io.libp2p.core.protocol.Protocols
import io.netty.bootstrap.Bootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.DefaultMessageSizeEstimator
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test


class NetworkTest {

    @Test
    @Disabled
    fun connect1() {

        val b = Bootstrap()
        b.group(NioEventLoopGroup())
        b.channel(NioSocketChannel::class.java)

        b.option(ChannelOption.SO_KEEPALIVE, true)
        b.option(ChannelOption.MESSAGE_SIZE_ESTIMATOR, DefaultMessageSizeEstimator.DEFAULT)
        b.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 15 * 1000)
        b.remoteAddress("localhost", 10000)

        val (privKey1, pubKey1) = generateKeyPair(KEY_TYPE.ECDSA)
        val secioProtocolSelect = ProtocolSelect(listOf(SecIoSecureChannel(privKey1)))
//        val mplexProtocolSelect = ProtocolSelect(listOf(MplexStreamMuxer()))

        b.handler(object: ChannelInitializer<Channel>() {
            override fun initChannel(ch: Channel) {
                ch.pipeline().addLast(LoggingHandler("###1", LogLevel.ERROR))
                ch.pipeline().addLast(Negotiator.createInitializer(true, Protocols.SECIO_1_0_0))
                ch.pipeline().addLast(secioProtocolSelect)
                ch.pipeline().addLast(LoggingHandler("###2", LogLevel.ERROR))
                ch.pipeline().addLast(Negotiator.createInitializer(true, Protocols.MPLEX_6_7_0))
                ch.pipeline().addLast(MplexChannelInitializer())
                ch.pipeline().addLast(LoggingHandler("###3", LogLevel.ERROR))
            }
        })

        // Start the client.
        println("Connecting")
        b.connect().await()
        println("Connected")

        Thread.sleep(10000000L)
    }
}