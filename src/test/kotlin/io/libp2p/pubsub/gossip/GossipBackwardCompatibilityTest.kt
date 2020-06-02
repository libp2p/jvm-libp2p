package io.libp2p.pubsub.gossip

import io.libp2p.core.dsl.host
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.mux.mplex.MplexStreamMuxer
import io.libp2p.pubsub.PubsubProtocol
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.transport.tcp.TcpTransport
import io.netty.handler.logging.LogLevel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class GossipBackwardCompatibilityTest {

    val routerV_1_0 = GossipRouter(protocol = PubsubProtocol.Gossip_V_1_0)
    val hostV_1_0 = host {
        identity {
            random()
        }
        transports {
            add(::TcpTransport)
        }
        network {
            listen("/ip4/127.0.0.1/tcp/40002")
        }
        secureChannels {
            add(::NoiseXXSecureChannel)
        }
        muxers {
            +::MplexStreamMuxer
        }
        protocols {
            +Gossip(routerV_1_0)
        }
        debug {
            muxFramesHandler.setLogger(LogLevel.ERROR)
        }
    }

    val routerV_1_1 = GossipRouter(protocol = PubsubProtocol.Gossip_V_1_1)
    val hostV_1_1 = host {
        identity {
            random()
        }
        transports {
            add(::TcpTransport)
        }
        network {
            listen("/ip4/127.0.0.1/tcp/40001")
        }
        secureChannels {
            add(::NoiseXXSecureChannel)
        }
        muxers {
            +::MplexStreamMuxer
        }
        protocols {
            +Gossip(routerV_1_1)
        }
        debug {
            muxFramesHandler.setLogger(LogLevel.ERROR)
        }
    }

    @BeforeEach
    fun startHosts() {
        val client = hostV_1_0.start()
        val server = hostV_1_1.start()
        client.get(5, TimeUnit.SECONDS)
        println("Client started")
        server.get(5, TimeUnit.SECONDS)
        println("Server started")
    }

    @AfterEach
    fun stopHosts() {
        hostV_1_0.stop().get(5, TimeUnit.SECONDS)
        println("Client Host stopped")
        hostV_1_1.stop().get(5, TimeUnit.SECONDS)
        println("Server Host stopped")
    }

    @Test
    fun testConnect() {
        val connect = hostV_1_0.network.connect(hostV_1_1.peerId, Multiaddr.fromString("/ip4/127.0.0.1/tcp/40001"))
        val connection = connect.get(10, TimeUnit.SECONDS)

        waitFor { routerV_1_0.peers.isNotEmpty() }
        waitFor { routerV_1_1.peers.isNotEmpty() }
    }

    fun waitFor(predicate: () -> Boolean) {
        for (i in 0..100) {
            if (predicate()) return
            Thread.sleep(100)
        }
        throw TimeoutException("Timeout waiting for condition")
    }
}