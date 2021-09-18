package io.libp2p.pubsub.gossip

import io.libp2p.core.dsl.host
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.pubsub.PubsubProtocol
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.transport.tcp.TcpTransport
import io.netty.handler.logging.LogLevel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
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
            + StreamMuxerProtocol.Mplex
        }
        protocols {
            +Gossip(routerV_1_0)
        }
        debug {
            muxFramesHandler.addLogger(LogLevel.ERROR)
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
            + StreamMuxerProtocol.Mplex
        }
        protocols {
            +Gossip(routerV_1_1)
        }
        debug {
            muxFramesHandler.addLogger(LogLevel.ERROR)
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

    private fun gossipConnected(router: GossipRouter) =
        router.peers.isNotEmpty() &&
            router.peers[0].getInboundHandler() != null &&
            router.peers[0].getOutboundHandler() != null

    @Test
    fun testConnect_1_0_to_1_1() {
        val connect = hostV_1_0.network
            .connect(hostV_1_1.peerId, Multiaddr.fromString("/ip4/127.0.0.1/tcp/40001"))
        connect.get(10, TimeUnit.SECONDS)

        waitFor { gossipConnected(routerV_1_0) }
        waitFor { gossipConnected(routerV_1_1) }

        Assertions.assertEquals(
            PubsubProtocol.Gossip_V_1_0.announceStr,
            routerV_1_0.peers[0].getInboundHandler()!!.stream.getProtocol().get()
        )
        Assertions.assertEquals(
            PubsubProtocol.Gossip_V_1_0.announceStr,
            routerV_1_0.peers[0].getOutboundHandler()!!.stream.getProtocol().get()
        )
        Assertions.assertEquals(
            PubsubProtocol.Gossip_V_1_0.announceStr,
            routerV_1_1.peers[0].getInboundHandler()!!.stream.getProtocol().get()
        )
        Assertions.assertEquals(
            PubsubProtocol.Gossip_V_1_0.announceStr,
            routerV_1_1.peers[0].getOutboundHandler()!!.stream.getProtocol().get()
        )
    }

    @Test
    fun testConnect_1_1_to_1_0() {
        val connect = hostV_1_1.network
            .connect(hostV_1_0.peerId, Multiaddr.fromString("/ip4/127.0.0.1/tcp/40002"))
        connect.get(10, TimeUnit.SECONDS)

        waitFor { gossipConnected(routerV_1_0) }
        waitFor { gossipConnected(routerV_1_1) }

        Assertions.assertEquals(
            PubsubProtocol.Gossip_V_1_0.announceStr,
            routerV_1_0.peers[0].getInboundHandler()!!.stream.getProtocol().get()
        )
        Assertions.assertEquals(
            PubsubProtocol.Gossip_V_1_0.announceStr,
            routerV_1_0.peers[0].getOutboundHandler()!!.stream.getProtocol().get()
        )
        Assertions.assertEquals(
            PubsubProtocol.Gossip_V_1_0.announceStr,
            routerV_1_1.peers[0].getInboundHandler()!!.stream.getProtocol().get()
        )
        Assertions.assertEquals(
            PubsubProtocol.Gossip_V_1_0.announceStr,
            routerV_1_1.peers[0].getOutboundHandler()!!.stream.getProtocol().get()
        )
    }

    fun waitFor(predicate: () -> Boolean) {
        for (i in 0..100) {
            if (predicate()) return
            Thread.sleep(100)
        }
        throw TimeoutException("Timeout waiting for condition")
    }
}
