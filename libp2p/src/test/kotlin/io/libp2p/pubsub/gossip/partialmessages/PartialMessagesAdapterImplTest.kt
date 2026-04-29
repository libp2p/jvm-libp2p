package io.libp2p.pubsub.gossip.partialmessages

import com.google.protobuf.ByteString
import io.libp2p.core.PeerId
import io.libp2p.pubsub.Topic
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pubsub.pb.Rpc

class PartialMessagesAdapterImplTest {

    private val topic = "test-topic"
    private val groupIdBytes = "group-1".toByteArray()
    private lateinit var peer1: PeerId
    private lateinit var peer2: PeerId
    private lateinit var capturedCalls: MutableList<IncomingRpcCall<String>>
    private lateinit var adapter: PartialMessagesAdapterImpl<String>

    data class IncomingRpcCall<S>(
        val from: PeerId,
        val peerStates: Map<PeerId, S>,
        val rpc: Rpc.PartialMessagesExtension
    )

    private fun makeHandler() = object : PartialMessagesHandler<String> {
        override fun onIncomingRpc(
            from: PeerId,
            peerStates: Map<PeerId, String>,
            rpc: Rpc.PartialMessagesExtension,
            feedback: PartialMessagesPeerFeedback
        ) {
            capturedCalls += IncomingRpcCall(from, peerStates, rpc)
        }

        override fun onEmitGossip(
            topic: Topic,
            groupId: ByteArray,
            gossipPeers: Collection<PeerId>,
            peerStates: Map<PeerId, String>,
            feedback: PartialMessagesPeerFeedback
        ) {}
    }

    @BeforeEach
    fun setup() {
        peer1 = PeerId.random()
        peer2 = PeerId.random()
        capturedCalls = mutableListOf()
        adapter = PartialMessagesAdapterImpl(
            handler = makeHandler(),
            stateStore = PartialGroupStateStore(groupTtlHeartbeats = 3),
            feedback = NopPartialMessagesFeedback
        )
    }

    private fun buildRpc(
        topicId: String = topic,
        groupId: ByteArray = groupIdBytes,
        partialMessage: ByteArray? = null,
        partsMetadata: ByteArray? = null
    ): Rpc.PartialMessagesExtension =
        Rpc.PartialMessagesExtension.newBuilder()
            .setTopicID(topicId)
            .setGroupID(ByteString.copyFrom(groupId))
            .apply {
                if (partialMessage != null) setPartialMessage(ByteString.copyFrom(partialMessage))
                if (partsMetadata != null) setPartsMetadata(ByteString.copyFrom(partsMetadata))
            }
            .build()

    @Test
    fun `dispatches valid RPC to handler`() {
        val rpc = buildRpc()

        adapter.onIncomingRpc(topic, peer1, rpc)

        assertThat(capturedCalls).hasSize(1)
        assertThat(capturedCalls[0].from).isEqualTo(peer1)
        assertThat(capturedCalls[0].rpc).isEqualTo(rpc)
    }

    @Test
    fun `peerStates map is empty on first RPC for a fresh group`() {
        adapter.onIncomingRpc(topic, peer1, buildRpc())

        assertThat(capturedCalls[0].peerStates).isEmpty()
    }

    @Test
    fun `second RPC for the same group reuses the same peerStates object`() {
        adapter.onIncomingRpc(topic, peer1, buildRpc())
        adapter.onIncomingRpc(topic, peer2, buildRpc())

        assertThat(capturedCalls).hasSize(2)
        // Both calls receive the same live GroupState.peerStates reference
        assertThat(capturedCalls[0].peerStates).isSameAs(capturedCalls[1].peerStates)
    }

    @Test
    fun `optional partialMessage and partsMetadata are forwarded to handler`() {
        val rpc = buildRpc(
            partialMessage = byteArrayOf(1, 2, 3),
            partsMetadata = byteArrayOf(0xFF.toByte())
        )

        adapter.onIncomingRpc(topic, peer1, rpc)

        assertThat(capturedCalls[0].rpc.partialMessage.toByteArray()).isEqualTo(byteArrayOf(1, 2, 3))
        assertThat(capturedCalls[0].rpc.partsMetadata.toByteArray()).isEqualTo(byteArrayOf(0xFF.toByte()))
    }

    @Test
    fun `handler not called when per-topic DoS cap is exceeded`() {
        val store = PartialGroupStateStore<String>(
            groupTtlHeartbeats = 3,
            peerInitiatedGroupLimitPerTopic = 1
        )
        val capped = PartialMessagesAdapterImpl(
            handler = makeHandler(),
            stateStore = store,
            feedback = NopPartialMessagesFeedback
        )

        capped.onIncomingRpc(topic, peer1, buildRpc(groupId = "g1".toByteArray()))
        capped.onIncomingRpc(topic, peer1, buildRpc(groupId = "g2".toByteArray()))

        assertThat(capturedCalls).hasSize(1)
    }

    @Test
    fun `handler not called when per-peer DoS cap is exceeded`() {
        val store = PartialGroupStateStore<String>(
            groupTtlHeartbeats = 3,
            peerInitiatedGroupLimitPerTopicPerPeer = 1
        )
        val capped = PartialMessagesAdapterImpl(
            handler = makeHandler(),
            stateStore = store,
            feedback = NopPartialMessagesFeedback
        )

        capped.onIncomingRpc(topic, peer1, buildRpc(groupId = "g1".toByteArray()))
        capped.onIncomingRpc(topic, peer1, buildRpc(groupId = "g2".toByteArray()))

        assertThat(capturedCalls).hasSize(1)
    }

    @Test
    fun `different topics create independent groups`() {
        adapter.onIncomingRpc("topic-a", peer1, buildRpc(topicId = "topic-a"))
        adapter.onIncomingRpc("topic-b", peer1, buildRpc(topicId = "topic-b"))

        assertThat(capturedCalls).hasSize(2)
        assertThat(capturedCalls[0].peerStates).isNotSameAs(capturedCalls[1].peerStates)
    }

    // ---- publishPartial ----

    @Test
    fun `publishPartial enqueues RPC for peer that requests partial`() {
        val enqueued = mutableListOf<Triple<PeerId, ByteArray?, ByteArray?>>()
        val payload = byteArrayOf(1, 2, 3)
        val meta = byteArrayOf(0xAA.toByte())
        val actionsFn = PublishActionsFn<String> { _, _ ->
            sequenceOf(peer1 to PublishAction(partialMessage = payload, partsMetadata = meta))
        }

        adapter.publishPartial(
            topic = topic,
            groupId = groupIdBytes.toGroupId(),
            actionsFn = actionsFn,
            peerRequestsPartial = { true },
            enqueueFn = { p, pm, meta2 -> enqueued += Triple(p, pm, meta2) }
        )

        assertThat(enqueued).hasSize(1)
        assertThat(enqueued[0].first).isEqualTo(peer1)
        assertThat(enqueued[0].second).isEqualTo(payload)
        assertThat(enqueued[0].third).isEqualTo(meta)
    }

    @Test
    fun `publishPartial omits partialMessage when peerRequestsPartial is false`() {
        val enqueued = mutableListOf<Triple<PeerId, ByteArray?, ByteArray?>>()
        val payload = byteArrayOf(1, 2, 3)
        val meta = byteArrayOf(0xAA.toByte())
        val actionsFn = PublishActionsFn<String> { _, _ ->
            sequenceOf(peer1 to PublishAction(partialMessage = payload, partsMetadata = meta))
        }

        adapter.publishPartial(
            topic = topic,
            groupId = groupIdBytes.toGroupId(),
            actionsFn = actionsFn,
            peerRequestsPartial = { false },
            enqueueFn = { p, pm, meta2 -> enqueued += Triple(p, pm, meta2) }
        )

        assertThat(enqueued).hasSize(1)
        assertThat(enqueued[0].second).isNull()
        assertThat(enqueued[0].third).isEqualTo(meta)
    }

    @Test
    fun `publishPartial skips peer when action contains an error`() {
        val enqueued = mutableListOf<PeerId>()
        val actionsFn = PublishActionsFn<String> { _, _ ->
            sequenceOf(peer1 to PublishAction(error = RuntimeException("oops")))
        }

        adapter.publishPartial(
            topic = topic,
            groupId = groupIdBytes.toGroupId(),
            actionsFn = actionsFn,
            peerRequestsPartial = { true },
            enqueueFn = { p, _, _ -> enqueued += p }
        )

        assertThat(enqueued).isEmpty()
    }

    @Test
    fun `publishPartial does not call enqueueFn when both partialMessage and partsMetadata are null`() {
        val enqueued = mutableListOf<PeerId>()
        val actionsFn = PublishActionsFn<String> { _, _ ->
            sequenceOf(peer1 to PublishAction<String>())
        }

        adapter.publishPartial(
            topic = topic,
            groupId = groupIdBytes.toGroupId(),
            actionsFn = actionsFn,
            peerRequestsPartial = { true },
            enqueueFn = { p, _, _ -> enqueued += p }
        )

        assertThat(enqueued).isEmpty()
    }

    @Test
    fun `publishPartial stores nextPeerState in group`() {
        val actionsFn = PublishActionsFn<String> { _, _ ->
            sequenceOf(peer1 to PublishAction(partsMetadata = byteArrayOf(1), nextPeerState = "state-for-peer1"))
        }

        adapter.publishPartial(
            topic = topic,
            groupId = groupIdBytes.toGroupId(),
            actionsFn = actionsFn,
            peerRequestsPartial = { true },
            enqueueFn = { _, _, _ -> }
        )

        val group = adapter.stateStore.getGroup(topic, groupIdBytes.toGroupId())
        assertThat(group?.peerStates?.get(peer1)).isEqualTo("state-for-peer1")
    }

    @Test
    fun `publishPartial provides peerRequestsPartial predicate to decide`() {
        val predicateCapture = mutableListOf<Boolean>()
        val actionsFn = PublishActionsFn<String> { _, peerRequestsPartial ->
            predicateCapture += peerRequestsPartial(peer1)
            emptySequence()
        }

        adapter.publishPartial(
            topic = topic,
            groupId = groupIdBytes.toGroupId(),
            actionsFn = actionsFn,
            peerRequestsPartial = { it == peer1 },
            enqueueFn = { _, _, _ -> }
        )

        assertThat(predicateCapture).containsExactly(true)
    }
}
