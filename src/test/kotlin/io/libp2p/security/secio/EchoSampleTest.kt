package io.libp2p.security.secio

import io.libp2p.core.Connection
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.StreamHandler
import io.libp2p.core.crypto.KEY_TYPE
import io.libp2p.core.crypto.generateKeyPair
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multistream.Multistream
import io.libp2p.etc.SimpleClientHandler
import io.libp2p.etc.createSimpleBinding
import io.libp2p.etc.types.toByteArray
import io.libp2p.mux.mplex.MplexStreamMuxer
import io.libp2p.transport.ConnectionUpgrader
import io.libp2p.transport.tcp.TcpTransport
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import org.apache.logging.log4j.LogManager
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class EchoProtocol : SimpleClientHandler() {
    private val respFuture = CompletableFuture<String>()

    fun echo(str: String): CompletableFuture<String> {
        writeAndFlush(Unpooled.copiedBuffer(str.toByteArray()))
        return respFuture
    }

    override fun messageReceived(ctx: ChannelHandlerContext, msg: ByteBuf) {
        respFuture.complete(String(msg.toByteArray()))
    }
}

class EchoSampleTest {

    /**
     * Requires running go echo sample
     * https://github.com/libp2p/go-libp2p-examples/tree/master/echo
     * > echo -l 10000
     */
    @Test
    @Disabled
    fun connect1() {
        val logger = LogManager.getLogger("test")

        val (privKey1, _) = generateKeyPair(KEY_TYPE.ECDSA)
        val upgrader = ConnectionUpgrader(
            listOf(SecIoSecureChannel(privKey1)),
            listOf(MplexStreamMuxer().also {
                it.muxFramesDebugHandler = LoggingHandler("#3", LogLevel.INFO)
            })
        ).also {
                it.beforeSecureHandler = LoggingHandler("#1", LogLevel.INFO)
                it.afterSecureHandler = LoggingHandler("#2", LogLevel.INFO)
            }

        val tcpTransport = TcpTransport(upgrader)
        val applicationProtocols = listOf(createSimpleBinding("/echo/1.0.0") { EchoProtocol() })
        val inboundStreamHandler = StreamHandler.create(Multistream.create(applicationProtocols))
        val connectionHandler = ConnectionHandler.createStreamHandlerInitializer(inboundStreamHandler)
        logger.info("Dialing...")
        val connFuture: CompletableFuture<Connection> = tcpTransport.dial(Multiaddr("/ip4/127.0.0.1/tcp/10000"), connectionHandler)

        val echoString = "Helooooooooooooooooooooooooo\n"
        connFuture.thenCompose {
            logger.info("Connection made")
            it.muxerSession.createStream(Multistream.create(applicationProtocols).toStreamHandler()).controler
        }.thenCompose {
            logger.info("Stream created, sending echo string...")
            it.echo(echoString)
        }.thenAccept {
            logger.info("Received back string: $it")
            Assertions.assertEquals(echoString, it)
        }.get(5, TimeUnit.SECONDS)
        logger.info("Success!")
    }
}