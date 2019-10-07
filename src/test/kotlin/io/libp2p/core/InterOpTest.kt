package io.libp2p.core

import io.libp2p.core.dsl.SecureChannelCtor
import io.libp2p.core.dsl.host
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.etc.types.getX
import io.libp2p.mux.mplex.MplexStreamMuxer
import io.libp2p.protocol.Identify
import io.libp2p.protocol.Ping
import io.libp2p.protocol.PingController
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.security.secio.SecIoSecureChannel
import io.libp2p.transport.tcp.TcpTransport
import io.netty.handler.logging.LogLevel
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertThrows
import java.io.File
import java.util.concurrent.TimeUnit

@Tag("secure-channel")
class SecioInterOpTest : InterOpTest(::SecIoSecureChannel)

abstract class InterOpTest(val secureChannelCtor: SecureChannelCtor) {
    val clientHost = host {
        identity {
            random()
        }
        transports {
            +::TcpTransport
        }
        secureChannels {
            add(secureChannelCtor)
        }
        muxers {
            +::MplexStreamMuxer
        }
        protocols {
            +Ping()
            +Identify()
        }
        /*debug {
            afterSecureHandler.setLogger(LogLevel.ERROR)
            muxFramesHandler.setLogger(LogLevel.ERROR)
        }*/
    }

    val serverHost = ProcessBuilder(*"node lib/index.js".split(" ").toTypedArray())
        .directory(File("/home/jez/work/web3labs/jvm-libp2p/src/test/js/ping-server"))
        .redirectOutput(ProcessBuilder.Redirect.PIPE)
        .redirectError(ProcessBuilder.Redirect.INHERIT)

    lateinit var serverPeerId: PeerId
    lateinit var server: Process

    @BeforeEach
    fun startServer() {
        server = serverHost.start()
        println("Server started")
        Thread.sleep(1000)
        val available = server.inputStream.available()
        if (available != 0) {
            val bytes = server.inputStream.readNBytes(available!!)
            val peerId = String(bytes!!).trim().split("/").last()
            serverPeerId = PeerId.fromBase58(peerId)
        }
    }

    @AfterEach
    fun stopServer() {
        server.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
        println("Server stopped")
    }

    @BeforeEach
    fun startClient() {
        val client = clientHost.start()
        client.get(5, TimeUnit.SECONDS)
        println("Client started")
    }

    @AfterEach
    fun stopHosts() {
        clientHost.stop().get(5, TimeUnit.SECONDS)
        println("Client Host stopped")
    }

    @Test
    fun unknownProtocol() {
        val badProtocol = clientHost.newStream<PingController>(
            "/__no_such_protocol/1.0.0",
            serverPeerId,
            Multiaddr("/ip4/127.0.0.1/tcp/40000")
        )
        assertThrows(NoSuchProtocolException::class.java) { badProtocol.stream.getX(5.0) }
        assertThrows(NoSuchProtocolException::class.java) { badProtocol.controler.getX(5.0) }
    }

    @Test
    fun pingOverSecureConnection() {
        val ping = clientHost.newStream<PingController>(
            "/ipfs/ping/1.0.0",
            serverPeerId,
            Multiaddr("/ip4/127.0.0.1/tcp/40000")
        )
        val pingStream = ping.stream.get(5, TimeUnit.SECONDS)
        println("Ping stream created")
        val pingCtr = ping.controler.get(10, TimeUnit.SECONDS)
        println("Ping controller created")

        for (i in 1..10) {
            val latency = pingCtr.ping().get(1, TimeUnit.SECONDS)
            println("Ping is $latency")
        }
        pingStream.nettyChannel.close().await(5, TimeUnit.SECONDS)
        println("Ping stream closed")

        // stream is closed, the call should fail correctly
        assertThrows(ConnectionClosedException::class.java) {
            pingCtr.ping().getX(5.0)
        }
    }
}