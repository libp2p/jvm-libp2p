package io.libp2p.pubsub.gossip

import io.libp2p.core.Connection
import io.libp2p.core.Host
import io.libp2p.core.dsl.host
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.core.pubsub.MessageApi
import io.libp2p.core.pubsub.Subscriber
import io.libp2p.core.pubsub.Topic
import io.libp2p.etc.types.toByteArray
import io.libp2p.etc.types.toByteBuf
import io.libp2p.pubsub.gossip.builders.GossipRouterBuilder
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.transport.tcp.TcpTransport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * libp2p allows several connections to the same peer, most commonly when both peers dial each other
 * at the same time, while gossipsub keeps a single outbound stream per peer id.
 *
 * See https://github.com/libp2p/jvm-libp2p/issues/526
 */
class GossipMultipleConnectionsTest {

    private val topic = Topic("topic")

    private val router1 = GossipRouterBuilder().build()
    private val router2 = GossipRouterBuilder().build()
    private val gossip1 = Gossip(router1)
    private val gossip2 = Gossip(router2)

    private val host1 = newHost(40011, gossip1)
    private val host2 = newHost(40012, gossip2)

    private val host2Address get() = Multiaddr.fromString("/ip4/127.0.0.1/tcp/40012/p2p/${host2.peerId}")

    private fun newHost(port: Int, gossip: Gossip): Host = host {
        identity { random() }
        transports { add(::TcpTransport) }
        network { listen("/ip4/127.0.0.1/tcp/$port") }
        secureChannels { add(::NoiseXXSecureChannel) }
        muxers { +StreamMuxerProtocol.Mplex }
        protocols { +gossip }
    }

    @BeforeEach
    fun startHosts() {
        host1.start().get(5, TimeUnit.SECONDS)
        host2.start().get(5, TimeUnit.SECONDS)
    }

    @AfterEach
    fun stopHosts() {
        host1.stop().get(5, TimeUnit.SECONDS)
        host2.stop().get(5, TimeUnit.SECONDS)
    }

    @Test
    fun `gossip survives closing the connection its stream was opened on`() {
        val messages = mutableListOf<MessageApi>()
        gossip1.subscribe(Subscriber { }, topic)
        gossip2.subscribe(Subscriber { messages += it }, topic)

        val connections = listOf(
            host1.network.connect(host2.peerId, host2Address).get(10, TimeUnit.SECONDS),
            dialAdditionalConnection()
        )

        waitFor { gossipConnected(router1) && gossipConnected(router2) }

        // Closing the connection which carries our outbound stream leaves us without one while we
        // are still connected to the peer via the other connection.
        val streamConnection = router1.peers[0].getOutboundHandler()!!.stream.connection
        val remainingConnection = connections.first { it !== streamConnection }
        streamConnection.close().get(5, TimeUnit.SECONDS)
        assertThat(remainingConnection.closeFuture()).isNotDone

        waitFor { gossipConnected(router1) && gossipConnected(router2) }
        waitFor { router1.getPeerTopics().join().values.any { topic.topic in it } }
        // Wait for the peer to be grafted rather than just re-subscribed. The peer handler is
        // rebuilt on the remaining connection, so publishing straight after the subscription
        // arrives can still find `peersTopics` empty for the new handler.
        waitFor { meshed(router1) && meshed(router2) }

        val msgBytes = ByteArray(32) { 0xab.toByte() }
        gossip1.createPublisher(null).publish(msgBytes.toByteBuf(), topic).get(10, TimeUnit.SECONDS)

        waitFor { messages.isNotEmpty() }
        assertThat(messages).hasSize(1).allMatch { it.data.toByteArray().contentEquals(msgBytes) }
    }

    /**
     * [io.libp2p.core.Network.connect] reuses an existing connection, so a second one to the same
     * peer has to be dialed via the transport directly.
     */
    private fun dialAdditionalConnection(): Connection {
        val addr = host2Address
        val transport = host1.network.transports.first { it.handles(addr) }
        val connection = transport.dial(addr, host1.network.connectionHandler).get(10, TimeUnit.SECONDS)
        waitFor { host2.network.connections.count { it.secureSession().remoteId == host1.peerId } == 2 }
        return connection
    }

    private fun meshed(router: GossipRouter) =
        router.submitOnEventThread { router.mesh[topic.topic]?.isNotEmpty() == true }.join()

    private fun gossipConnected(router: GossipRouter) =
        router.peers.size == 1 &&
            router.peers[0].getInboundHandler() != null &&
            router.peers[0].getOutboundHandler() != null

    private fun waitFor(predicate: () -> Boolean) {
        for (i in 0..100) {
            if (predicate()) return
            Thread.sleep(100)
        }
        throw TimeoutException("Timeout waiting for condition")
    }
}
