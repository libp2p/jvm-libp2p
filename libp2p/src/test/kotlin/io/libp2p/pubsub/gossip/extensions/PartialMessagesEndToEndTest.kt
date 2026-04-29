package io.libp2p.pubsub.gossip.extensions

import com.google.protobuf.ByteString
import io.libp2p.core.PeerId
import io.libp2p.core.dsl.host
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.pubsub.PubsubProtocol
import io.libp2p.pubsub.Topic
import io.libp2p.pubsub.gossip.Gossip
import io.libp2p.pubsub.gossip.GossipExtension
import io.libp2p.pubsub.gossip.GossipRouter
import io.libp2p.pubsub.gossip.builders.GossipRouterBuilder
import io.libp2p.pubsub.gossip.partialmessages.PartialMessagesHandler
import io.libp2p.pubsub.gossip.partialmessages.PartialMessagesPeerFeedback
import io.libp2p.pubsub.gossip.partialmessages.PublishAction
import io.libp2p.pubsub.gossip.partialmessages.PublishActionsFn
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.transport.tcp.TcpTransport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pubsub.pb.Rpc
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * End-to-end integration test for partial messages over real TCP / Noise / Mplex (Step 5).
 *
 * Two libp2p hosts — both with [GossipExtension.PARTIAL_MESSAGES] enabled and a
 * trivial bitmap-based [PartialMessagesHandler] — connect, subscribe, and exchange
 * partial-message RPCs. This exercises the complete stack that Steps 1-4 built:
 * SubOpts flag plumbing, handler dispatch, group-state tracking, inbound dispatch,
 * and outbound [Gossip.publishPartial].
 *
 * PeerState is a single [ByteArray] where each bit represents one "part"
 * (0 = not yet offered to peer, 1 = offered).
 */
class PartialMessagesEndToEndTest {

    private val topic = "test-topic"
    private val groupId = "group-1".toByteArray()

    data class InboundCall(
        val from: PeerId,
        val rpc: Rpc.PartialMessagesExtension,
        val peerStatesSnapshot: Map<PeerId, ByteArray>,
    )

    private val node1Inbound = CopyOnWriteArrayList<InboundCall>()
    private val node2Inbound = CopyOnWriteArrayList<InboundCall>()

    private fun bitmapHandler(sink: CopyOnWriteArrayList<InboundCall>): PartialMessagesHandler<ByteArray> =
        object : PartialMessagesHandler<ByteArray> {
            override fun onIncomingRpc(
                from: PeerId,
                peerStates: Map<PeerId, ByteArray>,
                rpc: Rpc.PartialMessagesExtension,
                feedback: PartialMessagesPeerFeedback,
            ) {
                sink += InboundCall(from, rpc, peerStates.mapValues { it.value.copyOf() })
            }

            override fun onEmitGossip(
                topic: Topic,
                groupId: ByteArray,
                gossipPeers: Collection<PeerId>,
                peerStates: Map<PeerId, ByteArray>,
                feedback: PartialMessagesPeerFeedback,
            ) {}
        }

    private fun buildRouter(handler: PartialMessagesHandler<ByteArray>) =
        GossipRouterBuilder(
            protocol = PubsubProtocol.Gossip_V_1_3,
            enabledGossipExtensions = listOf(GossipExtension.PARTIAL_MESSAGES),
            partialMessagesHandler = handler,
        ).build()

    private val router1 by lazy { buildRouter(bitmapHandler(node1Inbound)) }
    private val router2 by lazy { buildRouter(bitmapHandler(node2Inbound)) }

    private val gossip1 by lazy { Gossip(router1) }
    private val gossip2 by lazy { Gossip(router2) }

    private val host1 by lazy {
        host {
            identity { random() }
            transports { add(::TcpTransport) }
            network { listen("/ip4/127.0.0.1/tcp/0") }
            secureChannels { add(::NoiseXXSecureChannel) }
            muxers { +StreamMuxerProtocol.Mplex }
            protocols { +gossip1 }
        }
    }

    private val host2 by lazy {
        host {
            identity { random() }
            transports { add(::TcpTransport) }
            network { listen("/ip4/127.0.0.1/tcp/0") }
            secureChannels { add(::NoiseXXSecureChannel) }
            muxers { +StreamMuxerProtocol.Mplex }
            protocols { +gossip2 }
        }
    }

    @BeforeEach
    fun setUp() {
        host1.start().get(5, TimeUnit.SECONDS)
        host2.start().get(5, TimeUnit.SECONDS)
    }

    @AfterEach
    fun tearDown() {
        host1.stop().get(5, TimeUnit.SECONDS)
        host2.stop().get(5, TimeUnit.SECONDS)
    }

    /**
     * Connects the two hosts and subscribes both to [topic] with full partial flags.
     * Returns after the ControlExtensions handshake and SubOpts exchange have both
     * been processed on their respective event threads.
     */
    private fun connectAndSubscribeWithPartialFlags() {
        // Set flags before connecting so they are included in the SubOpts sent on peer activation.
        router1.setTopicPartialFlags(topic, requestsPartial = true, supportsSendingPartial = true)
        router2.setTopicPartialFlags(topic, requestsPartial = true, supportsSendingPartial = true)
        router1.subscribe(topic)
        router2.subscribe(topic)

        // listenAddresses() already includes /p2p/<peerId>.
        val host2Addr = host2.listenAddresses().first()
        host1.network.connect(host2.peerId, host2Addr).get(10, TimeUnit.SECONDS)

        // Wait for ControlExtensions handshake (required before partial RPCs are accepted).
        val peer1Id = host1.peerId
        val peer2Id = host2.peerId
        waitForOnEventThread(router1) { router1.gossipExtensionsState.peerSupportsPartialMessages(peer2Id) }
        waitForOnEventThread(router2) { router2.gossipExtensionsState.peerSupportsPartialMessages(peer1Id) }

        // Wait for SubOpts with partial flags to be processed by both sides.
        waitForOnEventThread(router1) { router1.partialSubscriptionState.peerRequestsPartial(topic, peer2Id) }
        waitForOnEventThread(router2) { router2.partialSubscriptionState.peerRequestsPartial(topic, peer1Id) }
    }

    private fun waitFor(predicate: () -> Boolean) {
        repeat(100) {
            if (predicate()) return
            Thread.sleep(100)
        }
        throw TimeoutException("Timed out waiting for condition")
    }

    private fun waitForOnEventThread(router: GossipRouter, predicate: () -> Boolean) {
        waitFor { router.submitOnEventThread { predicate() }.get(1, TimeUnit.SECONDS) }
    }

    @Test
    fun `publishPartial delivers RPC with payload and metadata to peer handler`() {
        connectAndSubscribeWithPartialFlags()

        val partPayload = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        val metaBitmap = byteArrayOf(0x01) // bit 0 set: has part 0
        val peer2Id = host2.peerId

        gossip1.publishPartial(
            topic,
            groupId,
            PublishActionsFn<ByteArray> { _, _ ->
                sequenceOf(peer2Id to PublishAction(partialMessage = partPayload, partsMetadata = metaBitmap))
            },
        ).get(5, TimeUnit.SECONDS)

        waitFor { node2Inbound.isNotEmpty() }

        val call = node2Inbound.single()
        assertThat(call.from).isEqualTo(host1.peerId)
        assertThat(call.rpc.topicID).isEqualTo(topic)
        assertThat(call.rpc.groupID).isEqualTo(ByteString.copyFrom(groupId))
        assertThat(call.rpc.partialMessage.toByteArray()).isEqualTo(partPayload)
        assertThat(call.rpc.partsMetadata.toByteArray()).isEqualTo(metaBitmap)
    }

    @Test
    fun `bidirectional partial RPC round-trip between two hosts`() {
        connectAndSubscribeWithPartialFlags()

        val peer1Id = host1.peerId
        val peer2Id = host2.peerId

        val n1Payload = byteArrayOf(0xAA.toByte())
        val n1Meta = byteArrayOf(0x01) // node1 has part 0

        gossip1.publishPartial(
            topic,
            groupId,
            PublishActionsFn<ByteArray> { _, _ ->
                sequenceOf(peer2Id to PublishAction(partialMessage = n1Payload, partsMetadata = n1Meta))
            },
        ).get(5, TimeUnit.SECONDS)

        waitFor { node2Inbound.isNotEmpty() }

        val n2Payload = byteArrayOf(0xBB.toByte())
        val n2Meta = byteArrayOf(0x02) // node2 has part 1

        gossip2.publishPartial(
            topic,
            groupId,
            PublishActionsFn<ByteArray> { _, _ ->
                sequenceOf(peer1Id to PublishAction(partialMessage = n2Payload, partsMetadata = n2Meta))
            },
        ).get(5, TimeUnit.SECONDS)

        waitFor { node1Inbound.isNotEmpty() }

        val callToNode2 = node2Inbound.single()
        assertThat(callToNode2.from).isEqualTo(peer1Id)
        assertThat(callToNode2.rpc.topicID).isEqualTo(topic)
        assertThat(callToNode2.rpc.groupID).isEqualTo(ByteString.copyFrom(groupId))
        assertThat(callToNode2.rpc.partialMessage.toByteArray()).isEqualTo(n1Payload)
        assertThat(callToNode2.rpc.partsMetadata.toByteArray()).isEqualTo(n1Meta)

        val callToNode1 = node1Inbound.single()
        assertThat(callToNode1.from).isEqualTo(peer2Id)
        assertThat(callToNode1.rpc.topicID).isEqualTo(topic)
        assertThat(callToNode1.rpc.groupID).isEqualTo(ByteString.copyFrom(groupId))
        assertThat(callToNode1.rpc.partialMessage.toByteArray()).isEqualTo(n2Payload)
        assertThat(callToNode1.rpc.partsMetadata.toByteArray()).isEqualTo(n2Meta)
    }

    @Test
    fun `nextPeerState is persisted and passed to subsequent decide calls`() {
        connectAndSubscribeWithPartialFlags()

        val peer2Id = host2.peerId
        val statesSeenInSecondCall = CopyOnWriteArrayList<ByteArray?>()

        // First publish: store nextPeerState = 0x01 ("sent part 0 to peer2").
        gossip1.publishPartial(
            topic,
            groupId,
            PublishActionsFn<ByteArray> { _, _ ->
                sequenceOf(peer2Id to PublishAction(partsMetadata = byteArrayOf(0x03), nextPeerState = byteArrayOf(0x01)))
            },
        ).get(5, TimeUnit.SECONDS)

        // Second publish: decide() should observe peerStates[peer2Id] = 0x01.
        gossip1.publishPartial(
            topic,
            groupId,
            PublishActionsFn<ByteArray> { peerStates, _ ->
                statesSeenInSecondCall += peerStates[peer2Id]?.copyOf()
                sequenceOf(peer2Id to PublishAction(partsMetadata = byteArrayOf(0x03), nextPeerState = byteArrayOf(0x03)))
            },
        ).get(5, TimeUnit.SECONDS)

        assertThat(statesSeenInSecondCall).hasSize(1)
        assertThat(statesSeenInSecondCall[0]).isEqualTo(byteArrayOf(0x01))
    }

    @Test
    fun `spec MUST - partialMessage is omitted when peer supports sending but did not request`() {
        // Node2 supports sending partial but does NOT request it from node1.
        // The library MUST omit partialMessage in any RPC sent to node2.
        router1.setTopicPartialFlags(topic, requestsPartial = true, supportsSendingPartial = true)
        router2.setTopicPartialFlags(topic, requestsPartial = false, supportsSendingPartial = true)
        router1.subscribe(topic)
        router2.subscribe(topic)

        val host2Addr = host2.listenAddresses().first()
        host1.network.connect(host2.peerId, host2Addr).get(10, TimeUnit.SECONDS)

        val peer2Id = host2.peerId
        waitForOnEventThread(router1) { router1.gossipExtensionsState.peerSupportsPartialMessages(peer2Id) }
        waitForOnEventThread(router2) { router2.gossipExtensionsState.peerSupportsPartialMessages(host1.peerId) }
        // node2 subscribed with supportsSendingPartial=true, requestsPartial=false
        waitForOnEventThread(router1) { router1.partialSubscriptionState.peerSupportsSendingPartial(topic, peer2Id) }

        gossip1.publishPartial(
            topic,
            groupId,
            PublishActionsFn<ByteArray> { _, _ ->
                sequenceOf(
                    peer2Id to PublishAction(
                        partialMessage = byteArrayOf(0xFF.toByte()),
                        partsMetadata = byteArrayOf(0x01),
                    ),
                )
            },
        ).get(5, TimeUnit.SECONDS)

        waitFor { node2Inbound.isNotEmpty() }

        // Library MUST have omitted partialMessage because node2 didn't request partial.
        val call = node2Inbound.single()
        assertThat(call.rpc.hasPartialMessage()).isFalse()
        assertThat(call.rpc.partsMetadata.toByteArray()).isEqualTo(byteArrayOf(0x01))
    }
}
