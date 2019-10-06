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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

@Tag("secure-channel")
class SecioTest : HostTest(::SecIoSecureChannel)

@Tag("secure-channel")
class NoiseXXTest : HostTest(::NoiseXXSecureChannel)

abstract class HostTest(val secureChannelCtor: SecureChannelCtor) {
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
        debug {
            afterSecureHandler.setLogger(LogLevel.ERROR)
            muxFramesHandler.setLogger(LogLevel.ERROR)
        }
    }

    val serverHost = host {
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
        network {
            listen("/ip4/0.0.0.0/tcp/40002")
        }
        protocols {
            +Ping()
        }
    }

    @BeforeEach
    fun startHosts() {
        val client = clientHost.start()
        val server = serverHost.start()
        client.get(5, TimeUnit.SECONDS)
        println("Client started")
        server.get(5, TimeUnit.SECONDS)
        println("Server started")
    }

    @AfterEach
    fun stopHosts() {
        clientHost.stop().get(5, TimeUnit.SECONDS)
        println("Client Host stopped")
        serverHost.stop().get(5, TimeUnit.SECONDS)
        println("Server Host stopped")
    }

    @Test
    fun unknownProtocol() {
        val badProtocol = clientHost.newStream<PingController>(
            "/__no_such_protocol/1.0.0",
            serverHost.peerId,
            Multiaddr("/ip4/127.0.0.1/tcp/40002")
        )
        assertThrows(NoSuchProtocolException::class.java) { badProtocol.stream.getX(5.0) }
        assertThrows(NoSuchProtocolException::class.java) { badProtocol.controler.getX(5.0) }
    }

    @Test
    fun unsupportedServerProtocol() {
        // remote party doesn't support the protocol
        val unsupportedProtocol = clientHost.newStream<PingController>(
            "/ipfs/id/1.0.0",
            serverHost.peerId,
            Multiaddr("/ip4/127.0.0.1/tcp/40002")
        )
        // stream should be created
        unsupportedProtocol.stream.get(5, TimeUnit.SECONDS)
        println("Stream created")
        // ... though protocol controller should fail
        assertThrows(NoSuchProtocolException::class.java) { unsupportedProtocol.controler.getX(15.0) }
    }

    @Test
    fun pingOverSecureConnection() {
        val ping = clientHost.newStream<PingController>(
            "/ipfs/ping/1.0.0",
            serverHost.peerId,
            Multiaddr("/ip4/127.0.0.1/tcp/40002")
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