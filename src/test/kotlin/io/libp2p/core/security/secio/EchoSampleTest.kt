package io.libp2p.core.security.secio

import io.libp2p.core.StreamHandler
import io.libp2p.core.crypto.KEY_TYPE
import io.libp2p.core.crypto.generateKeyPair
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multistream.Mode
import io.libp2p.core.multistream.Multistream
import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.core.multistream.ProtocolBindingInitializer
import io.libp2p.core.multistream.ProtocolMatcher
import io.libp2p.core.mux.mplex.MplexStreamMuxer
import io.libp2p.core.transport.ConnectionUpgrader
import io.libp2p.core.transport.tcp.TcpTransport
import io.libp2p.core.types.toByteArray
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import org.apache.logging.log4j.LogManager
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class EchoController : ChannelInboundHandlerAdapter() {
    var ctx: ChannelHandlerContext? = null
    val respFuture = CompletableFuture<String>()
    val activeFuture = CompletableFuture<EchoController>()

    fun echo(str: String): CompletableFuture<String> {
        ctx!!.writeAndFlush(Unpooled.copiedBuffer(str.toByteArray()))
        return respFuture
    }

    override fun channelActive(ctx: ChannelHandlerContext) {
        this.ctx = ctx
        activeFuture.complete(this)
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        msg as ByteBuf
        respFuture.complete(String(msg.toByteArray()))
    }
}

class EchoProtocol : ProtocolBinding<EchoController> {
    override val announce = "/echo/1.0.0"
    override val matcher = ProtocolMatcher(Mode.STRICT, announce)
    override fun initializer(selectedProtocol: String): ProtocolBindingInitializer<EchoController> {
        val controller = EchoController()
        return ProtocolBindingInitializer(controller, controller.activeFuture)
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

        val (privKey1, pubKey1) = generateKeyPair(KEY_TYPE.ECDSA)
        val upgrader = ConnectionUpgrader(
            listOf(SecIoSecureChannel(privKey1)),
            listOf(MplexStreamMuxer().also {
                it.intermediateFrameHandler = LoggingHandler("#3", LogLevel.INFO) })
        ).also {
                it.beforeSecureHandler = LoggingHandler("#1", LogLevel.INFO)
                it.afterSecureHandler = LoggingHandler("#2", LogLevel.INFO)
            }

        val tcpTransport = TcpTransport(upgrader)
        val applicationProtocols = listOf(EchoProtocol())
        val inboundStreamHandler = StreamHandler.create(Multistream.create(applicationProtocols))
        logger.info("Dialing...")
        val connFuture = tcpTransport.dial(Multiaddr("/ip4/127.0.0.1/tcp/10000"), inboundStreamHandler)

        val echoString = "Helooooooooooooooooooooooooo\n"
        connFuture.thenCompose {
            logger.info("Connection made")
            val echoInitiator = Multistream.create(applicationProtocols)
            val (channelHandler, completableFuture) =
                echoInitiator.initializer()
            logger.info("Creating stream")
            it.muxerSession.get().createStream(StreamHandler.create(channelHandler))
            completableFuture
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