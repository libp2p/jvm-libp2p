package io.libp2p.pubsub.gossip

import io.libp2p.core.dsl.host
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.etc.util.netty.LoggingHandlerShort
import io.libp2p.pubsub.gossip.builders.GossipRouterBuilder
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.transport.tcp.TcpTransport
import io.netty.handler.logging.LogLevel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

abstract class TwoGossipHostTestBase {

    open val params = GossipParams()

    open val router1 by lazy { GossipRouterBuilder(params = params).build() }
    open val router2 by lazy { GossipRouterBuilder(params = params).build() }

    open val gossip1 by lazy {
        Gossip(router1, debugGossipHandler = LoggingHandlerShort("host-1", LogLevel.INFO))
    }
    open val gossip2 by lazy {
        Gossip(router2, debugGossipHandler = LoggingHandlerShort("host-2", LogLevel.INFO))
    }

    val host1 by lazy {
        host {
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
                +StreamMuxerProtocol.Mplex
            }
            protocols {
                +gossip1
            }
            debug {
                muxFramesHandler.addNettyHandler(LoggingHandlerShort("host-1", LogLevel.INFO))
            }
        }
    }

    val host2 by lazy {
        host {
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
                +StreamMuxerProtocol.Mplex
            }
            protocols {
                +gossip2
            }
            debug {
                muxFramesHandler.addNettyHandler(LoggingHandlerShort("host-2", LogLevel.INFO))
            }
        }
    }

    @BeforeEach
    fun startHosts() {
        val client = host1.start()
        val server = host2.start()
        client.get(5, TimeUnit.SECONDS)
        println("Client started")
        server.get(5, TimeUnit.SECONDS)
        println("Server started")
    }

    @AfterEach
    fun stopHosts() {
        host1.stop().get(5, TimeUnit.SECONDS)
        println("Client Host stopped")
        host2.stop().get(5, TimeUnit.SECONDS)
        println("Server Host stopped")
    }

    private fun gossipConnected(router: GossipRouter) =
        router.peers.isNotEmpty() &&
            router.peers[0].getInboundHandler() != null &&
            router.peers[0].getOutboundHandler() != null

    protected fun connect() {
        val connect = host1.network
            .connect(host2.peerId, Multiaddr.fromString("/ip4/127.0.0.1/tcp/40001/p2p/" + host2.peerId))
        connect.get(10, TimeUnit.SECONDS)

        waitFor { gossipConnected(router1) }
        waitFor { gossipConnected(router2) }
    }

    protected fun waitForSubscribed(router: GossipRouter, topic: String) {
        waitFor { router.getPeerTopics().join().values.flatten().any { topic == it } }
    }

    protected fun waitFor(predicate: () -> Boolean) {
        for (i in 0..100) {
            if (predicate()) return
            Thread.sleep(100)
        }
        throw TimeoutException("Timeout waiting for condition")
    }
}
