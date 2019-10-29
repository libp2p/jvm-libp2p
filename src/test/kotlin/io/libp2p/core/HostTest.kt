package io.libp2p.core

import io.libp2p.core.dsl.SecureChannelCtor
import io.libp2p.core.dsl.host
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.etc.types.getX
import io.libp2p.mux.mplex.MplexStreamMuxer
import io.libp2p.protocol.Identify
import io.libp2p.protocol.IdentifyController
import io.libp2p.protocol.Ping
import io.libp2p.protocol.PingController
import io.libp2p.protocol.PingBinding
import io.libp2p.tools.CountingPingProtocol
import io.libp2p.tools.DoNothing
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.security.plaintext.PlaintextInsecureChannel
import io.libp2p.security.secio.SecIoSecureChannel
import io.libp2p.tools.DoNothingController
import io.libp2p.transport.tcp.TcpTransport
import io.netty.handler.logging.LogLevel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable
import java.util.concurrent.TimeUnit

@Tag("secure-channel")
class SecioHostTest : HostTest(::SecIoSecureChannel)

@DisabledIfEnvironmentVariable(named = "TRAVIS", matches = "true")
@Tag("secure-channel")
class NoiseXXHostTest : HostTest(::NoiseXXSecureChannel)

@Tag("secure-channel")
class PlaintextHostTest : HostTest(::PlaintextInsecureChannel)

abstract class HostTest(val secureChannelCtor: SecureChannelCtor) {
    val listenAddress = "/ip4/127.0.0.1/tcp/40002"

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
            +DoNothing()
        }
        debug {
            afterSecureHandler.setLogger(LogLevel.ERROR)
            muxFramesHandler.setLogger(LogLevel.ERROR)
        }
    }

    var countedPingResponder = CountingPingProtocol()
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
            listen(listenAddress)
        }
        protocols {
            +PingBinding(countedPingResponder)
            +Identify()
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
    fun unknownLocalProtocol() {
        val badProtocol = clientHost.newStream<PingController>(
            "/__no_such_protocol/1.0.0",
            serverHost.peerId,
            Multiaddr(listenAddress)
        )
        assertThrows(NoSuchProtocolException::class.java) { badProtocol.stream.getX(5.0) }
        assertThrows(NoSuchProtocolException::class.java) { badProtocol.controller.getX(5.0) }
    }

    @Test
    fun unsupportedServerProtocol() {
        // remote party doesn't support the protocol
        val unsupportedProtocol = clientHost.newStream<DoNothingController>(
            "/ipfs/do-nothing/1.0.0",
            serverHost.peerId,
            Multiaddr(listenAddress)
        )
        // stream should be created
        unsupportedProtocol.stream.get()
        println("Stream created")
        // ... though protocol controller should fail
        assertThrows(NoSuchProtocolException::class.java) { unsupportedProtocol.controller.getX() }
    }

    @Test
    fun pingOverSecureConnection() {
        val ping = clientHost.newStream<PingController>(
            "/ipfs/ping/1.0.0",
            serverHost.peerId,
            Multiaddr(listenAddress)
        )
        val pingStream = ping.stream.get(5, TimeUnit.SECONDS)
        println("Ping stream created")
        val pingCtr = ping.controller.get(5, TimeUnit.SECONDS)
        println("Ping controller created")

        for (i in 1..10) {
            val latency = pingCtr.ping().get(1, TimeUnit.SECONDS)
            println("Ping $i is ${latency}ms")
        }
        pingStream.close().get(5, TimeUnit.SECONDS)
        println("Ping stream closed")

        assertEquals(10, countedPingResponder.pingsReceived)

        // stream is closed, the call should fail correctly
        assertThrows(ConnectionClosedException::class.java) {
            pingCtr.ping().getX(5.0)
        }
    }

    @Test
    fun identifyOverSecureConnection() {
        val identify = clientHost.newStream<IdentifyController>(
            "/ipfs/id/1.0.0",
            serverHost.peerId,
            Multiaddr(listenAddress)
        )
        val identifyStream = identify.stream.get(5, TimeUnit.SECONDS)
        println("Identify stream created")
        val identifyController = identify.controller.get(5, TimeUnit.SECONDS)
        println("Identify controller created")

        val remoteIdentity = identifyController.id().get(5, TimeUnit.SECONDS)
        println(remoteIdentity)

        identifyStream.close().get(5, TimeUnit.SECONDS)
        println("Identify stream closed")

        assertEquals("jvm/0.1", remoteIdentity.agentVersion)

        assertTrue(remoteIdentity.protocolsList.contains("/ipfs/id/1.0.0"))
        assertTrue(remoteIdentity.protocolsList.contains("/ipfs/ping/1.0.0"))

        assertEquals(identifyStream.connection.localAddress(), Multiaddr(remoteIdentity.observedAddr.toByteArray()))

        assertEquals(1, remoteIdentity.listenAddrsCount)
        val remoteAddress = Multiaddr(remoteIdentity.listenAddrsList[0].toByteArray())
        assertEquals(listenAddress, remoteAddress.toString())
        assertEquals(identifyStream.connection.remoteAddress(), remoteAddress)
    }
}

