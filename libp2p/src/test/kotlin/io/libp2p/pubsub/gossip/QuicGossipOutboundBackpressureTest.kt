package io.libp2p.pubsub.gossip

import io.libp2p.core.Host
import io.libp2p.core.Stream
import io.libp2p.core.dsl.Builder
import io.libp2p.core.dsl.host
import io.libp2p.pubsub.PubsubProtocol
import io.libp2p.pubsub.gossip.builders.GossipRouterBuilder
import io.libp2p.transport.quic.QuicConfig
import io.libp2p.transport.quic.QuicStream
import io.libp2p.transport.quic.QuicTransport
import io.netty.channel.Channel
import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPromise
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit

/**
 * Real QUIC reproduction for outbound gossipsub writes accumulating behind exhausted flow control.
 *
 * This verifies that the router keeps one unresolved write during a real QUIC flow-control stall
 * and resumes the queued publications when the receiver starts reading again.
 */
class QuicGossipOutboundBackpressureTest : GossipTestsBase() {

    @Test
    fun `a stalled QUIC pubsub stream must not accumulate unresolved writes`() {
        val quicConfig = QuicConfig(
            maxConnectionData = 4L * 1024 * 1024,
            maxStreamDataLocal = STREAM_WINDOW,
            maxStreamDataRemote = STREAM_WINDOW,
            idleTimeout = Duration.ofSeconds(60)
        )
        val senderRouter = createRouter()
        val receiverRouter = createRouter()
        val writeRecorder = RecordingOutboundWriteHandler()
        val senderGossip = Gossip(senderRouter, debugGossipHandler = writeRecorder)
        val receiverGossip = Gossip(receiverRouter)
        val senderHost = createHost(senderGossip, quicConfig)
        val receiverHost = createHost(receiverGossip, quicConfig)

        try {
            senderHost.start().get(5, TimeUnit.SECONDS)
            receiverHost.start().get(5, TimeUnit.SECONDS)

            senderHost.network
                .connect(receiverHost.peerId, receiverHost.listenAddresses().single())
                .get(10, TimeUnit.SECONDS)

            waitFor("both semi-duplex gossipsub streams") {
                hasActiveSemiDuplexPeer(senderRouter) && hasActiveSemiDuplexPeer(receiverRouter)
            }

            senderRouter.subscribe(TOPIC)
            receiverRouter.subscribe(TOPIC)
            waitFor("gossipsub subscriptions") {
                hasPeerSubscription(senderRouter) && hasPeerSubscription(receiverRouter)
            }

            val senderStream = senderRouter.outboundQuicStream()
            val receiverStream = receiverRouter.inboundQuicStream()
            val senderChannel = senderStream.quicStreamChannel
            val receiverChannel = receiverStream.quicStreamChannel
            val connectionChannel = senderChannel.parent()

            assertThat(connectionChannel).isNotNull
            assertThat(connectionChannel.isActive).isTrue()
            assertThat(senderChannel.isActive).isTrue()
            assertThat(receiverChannel.isActive).isTrue()

            receiverChannel.eventLoop().submit {
                receiverChannel.config().isAutoRead = false
            }.sync()
            assertThat(receiverChannel.config().isAutoRead).isFalse()

            writeRecorder.begin(senderChannel)
            val publishFutures = (0 until MESSAGE_COUNT).map { sequence ->
                senderRouter.publish(
                    newMessage(TOPIC, sequence.toLong(), ByteArray(MESSAGE_SIZE) { 0x5a })
                )
            }

            waitFor("sender QUIC stream to become unwritable") {
                !senderChannel.isWritable
            }

            assertThat(connectionChannel.isActive).isTrue()
            assertThat(senderChannel.isActive).isTrue()
            assertThat(receiverChannel.isActive).isTrue()
            assertThat(publishFutures).hasSize(MESSAGE_COUNT)

            val unresolvedWrites = writeRecorder.unresolvedWrites()
            assertThat(unresolvedWrites)
                .withFailMessage(
                    "unresolved write promises reaching the stalled QUIC stream: expected at most 1, " +
                        "observed ${unresolvedWrites.size}"
                )
                .hasSizeLessThanOrEqualTo(1)

            val writesBeforeResume = writeRecorder.totalWrites()
            receiverChannel.eventLoop().submit {
                receiverChannel.config().isAutoRead = true
                receiverChannel.read()
            }.sync()

            waitFor("sender QUIC stream to become writable again") {
                senderChannel.isWritable
            }
            waitFor("all queued publications to complete") {
                publishFutures.all { it.isDone }
            }

            assertThat(publishFutures)
                .allMatch { it.isDone && !it.isCompletedExceptionally }
            assertThat(writeRecorder.unresolvedWrites()).isEmpty()
            assertThat(writeRecorder.totalWrites()).isGreaterThan(writesBeforeResume)
            assertThat(connectionChannel.isActive).isTrue()
            assertThat(senderChannel.isActive).isTrue()
            assertThat(receiverChannel.isActive).isTrue()
        } finally {
            senderHost.stop().get(5, TimeUnit.SECONDS)
            receiverHost.stop().get(5, TimeUnit.SECONDS)
        }
    }

    private fun createRouter(): GossipRouter = GossipRouterBuilder(
        protocol = PubsubProtocol.Gossip_V_1_2,
        params = GossipParams(
            D = 1,
            DLow = 1,
            DHigh = 1,
            floodPublishMaxMessageSizeThreshold = ALWAYS_FLOOD_PUBLISH
        ),
        scoreParams = GossipScoreParams(
            peerScoreParams = GossipPeerScoreParams(isDirect = { true })
        )
    ).build()

    private fun createHost(gossip: Gossip, quicConfig: QuicConfig): Host = host(Builder.Defaults.None) {
        identity { random() }
        secureTransports {
            add { key, protocols -> QuicTransport.ECDSA(key, protocols, quicConfig) }
        }
        network { listen("/ip4/127.0.0.1/udp/${randomPort()}/quic-v1") }
        protocols { +gossip }
    }

    private fun hasActiveSemiDuplexPeer(router: GossipRouter): Boolean {
        val peer = router.peers.singleOrNull() ?: return false
        return peer.getInboundHandler()?.ctx != null && peer.getOutboundHandler()?.ctx != null
    }

    private fun hasPeerSubscription(router: GossipRouter): Boolean =
        router.getPeerTopics().join().values.any { TOPIC in it }

    private fun GossipRouter.outboundQuicStream(): QuicStream =
        peers.single().getOutboundHandler()!!.stream.asQuicStream()

    private fun GossipRouter.inboundQuicStream(): QuicStream =
        peers.single().getInboundHandler()!!.stream.asQuicStream()

    private fun Stream.asQuicStream(): QuicStream = this as? QuicStream
        ?: throw AssertionError("Expected a QUIC pubsub stream, got $this")

    private fun waitFor(description: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + WAIT_TIMEOUT.toNanos()
        while (!predicate()) {
            if (System.nanoTime() >= deadline) {
                throw AssertionError("Timed out waiting for $description")
            }
            Thread.sleep(20)
        }
    }

    @ChannelHandler.Sharable
    private class RecordingOutboundWriteHandler : ChannelDuplexHandler() {
        private val promises = CopyOnWriteArrayList<ChannelPromise>()

        @Volatile
        private var target: Channel? = null

        fun begin(channel: Channel) {
            promises.clear()
            target = channel
        }

        override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
            if (ctx.channel() === target) {
                promises += promise
            }
            ctx.write(msg, promise)
        }

        fun unresolvedWrites(): List<ChannelPromise> = promises.filterNot { it.isDone }

        fun totalWrites(): Int = promises.size
    }

    private companion object {
        const val TOPIC = "quic-backpressure-topic"
        const val MESSAGE_COUNT = 128
        const val MESSAGE_SIZE = 8 * 1024
        const val STREAM_WINDOW = 16L * 1024
        val WAIT_TIMEOUT: Duration = Duration.ofSeconds(15)

        fun randomPort(): Int = ThreadLocalRandom.current().nextInt(10_000, 60_000)
    }
}
