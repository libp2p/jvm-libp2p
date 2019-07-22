package io.libp2p.core.security.secio

import io.libp2p.core.Connection
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.StreamHandlerMock
import io.libp2p.core.crypto.KEY_TYPE
import io.libp2p.core.crypto.generateKeyPair
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.mux.mplex.MplexStreamMuxer
import io.libp2p.core.protocol.Mode
import io.libp2p.core.protocol.Multistream
import io.libp2p.core.protocol.ProtocolBinding
import io.libp2p.core.protocol.ProtocolBindingInitializer
import io.libp2p.core.protocol.ProtocolMatcher
import io.libp2p.core.transport.ConnectionUpgrader
import io.libp2p.core.transport.tcp.TcpTransport
import io.libp2p.core.types.toByteArray
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelOption
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class TestController: ChannelInboundHandlerAdapter() {
    var ctx: ChannelHandlerContext? = null
    val respFuture = CompletableFuture<String>()
    val activeFuture = CompletableFuture<TestController>()

    fun echo(str: String) : CompletableFuture<String> {
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

class TestProtocol: ProtocolBinding<TestController> {
    override val announce = "/echo/1.0.0"
    override val matcher = ProtocolMatcher(Mode.STRICT, announce)
    override fun initializer(selectedProtocol: String): ProtocolBindingInitializer<TestController> {
        val controller = TestController()
        return ProtocolBindingInitializer(controller, controller.activeFuture)
    }
}

class EchoSampleTest {

    @Test
    @Disabled
    fun connect1() {

        val b = Bootstrap()
        b.group(NioEventLoopGroup())
        b.channel(NioSocketChannel::class.java)
        b.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 15 * 1000)

        val (privKey1, pubKey1) = generateKeyPair(KEY_TYPE.ECDSA)
        val upgrader = ConnectionUpgrader(
            listOf(SecIoSecureChannel(privKey1)),
            listOf(MplexStreamMuxer()),
            LoggingHandler("", LogLevel.ERROR),
            LoggingHandler("###", LogLevel.ERROR)
        )
        val tcpTransport = TcpTransport(upgrader, b)
        val connFuture = CompletableFuture<Connection>()
        val connHandler = object : ConnectionHandler() {
            override fun accept(conn: Connection) {
                println("New connection: $conn")
                connFuture.complete(conn)
            }
        }
        val appProtocolsMultistream = Multistream.create(listOf(TestProtocol()), false)
        val streamHandler = StreamHandlerMock(appProtocolsMultistream.initializer().first)
        val dialFuture = tcpTransport.dial(Multiaddr("/ip4/127.0.0.1/tcp/10000"), connHandler, streamHandler)
        dialFuture.handle { ignore, thr ->
            println("Dial complete: $thr")
        }
        println("Dialing...")

        val echoString = "Heloooooooooo\n"
        connFuture.thenCompose {
            println("#### Connection made")
            val echoInitiator = Multistream.create(listOf(TestProtocol()), true)
            val (channelHandler, completableFuture) =
                echoInitiator.initializer()
            println("#### Creating stream")
            it.muxerSession.get().createStream(StreamHandlerMock(channelHandler))
            completableFuture
        }.thenCompose {
            println("#### Stream created, sending echo string...")
            it.echo(echoString)
        }.thenAccept {
            println("#### Received back string: $it")
            Assertions.assertEquals(echoString, it)
        }.get(5, TimeUnit.SECONDS)
        println("#### Success!")
    }
}