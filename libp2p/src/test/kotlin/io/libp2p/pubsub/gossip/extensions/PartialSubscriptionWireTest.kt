package io.libp2p.pubsub.gossip.extensions

import io.libp2p.core.PeerId
import io.libp2p.pubsub.PubsubProtocol
import io.libp2p.pubsub.Topic
import io.libp2p.pubsub.gossip.GossipExtension
import io.libp2p.pubsub.gossip.GossipTestsBase
import io.libp2p.pubsub.gossip.PartialSubFlags
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pubsub.pb.Rpc

class PartialSubscriptionWireTest : GossipTestsBase() {

    private val topicA = "topic-a"
    private val topicB = "topic-b"

    private fun newTest() = TwoRoutersTest(
        protocol = PubsubProtocol.Gossip_V_1_3,
        enabledGossipExtensions = listOf(GossipExtension.PARTIAL_MESSAGES),
        partialMessagesHandler = nopPartialMessagesHandler,
    )

    private fun Rpc.RPC.firstSubscribeFor(topic: String): Rpc.RPC.SubOpts? =
        subscriptionsList.firstOrNull { it.topicid == topic && it.subscribe }

    private fun Rpc.RPC.firstUnsubscribeFor(topic: String): Rpc.RPC.SubOpts? =
        subscriptionsList.firstOrNull { it.topicid == topic && !it.subscribe }

    /**
     * Reads `partialSubscriptionState.peerFlags` on the pubsub event loop so the
     * test thread establishes a happens-before with any pending event-loop
     * mutations. The state container is documented as not thread-safe; direct
     * access from the test thread risks `ConcurrentModificationException` and
     * stale reads.
     */
    private fun TwoRoutersTest.peerFlagsOnEventLoop(topic: Topic, peer: PeerId): PartialSubFlags =
        gossipRouter.submitOnEventThread {
            gossipRouter.partialSubscriptionState.peerFlags(topic, peer)
        }.join()

    private fun TwoRoutersTest.snapshotPartialStateOnEventLoop(): Map<Topic, Map<PeerId, PartialSubFlags>> =
        gossipRouter.submitOnEventThread {
            gossipRouter.partialSubscriptionState.snapshot()
        }.join()

    @Test
    fun `outbound subscribe carries configured partial flags with send-side coercion`() {
        val test = newTest()

        test.gossipRouter.setTopicPartialFlags(topicA, requestsPartial = true, supportsSendingPartial = false)
        test.gossipRouter.subscribe(topicA)

        val received = test.mockRouter.waitForMessage({ it.firstSubscribeFor(topicA) != null })
        val sub = received.firstSubscribeFor(topicA)!!
        assertThat(sub.requestsPartial).isTrue()
        // spec coercion: supportsSendingPartial := requestsPartial || supportsSendingPartial
        assertThat(sub.supportsSendingPartial).isTrue()
    }

    @Test
    fun `outbound subscribe with only supportsSendingPartial carries only that flag`() {
        val test = newTest()

        test.gossipRouter.setTopicPartialFlags(topicA, requestsPartial = false, supportsSendingPartial = true)
        test.gossipRouter.subscribe(topicA)

        val received = test.mockRouter.waitForMessage({ it.firstSubscribeFor(topicA) != null })
        val sub = received.firstSubscribeFor(topicA)!!
        assertThat(sub.requestsPartial).isFalse()
        assertThat(sub.supportsSendingPartial).isTrue()
    }

    @Test
    fun `outbound subscribe without configured flags has both flags absent`() {
        val test = newTest()

        test.gossipRouter.subscribe(topicA)

        val received = test.mockRouter.waitForMessage({ it.firstSubscribeFor(topicA) != null })
        val sub = received.firstSubscribeFor(topicA)!!
        assertThat(sub.hasRequestsPartial()).isFalse()
        assertThat(sub.hasSupportsSendingPartial()).isFalse()
    }

    @Test
    fun `outbound unsubscribe never carries partial flags`() {
        val test = newTest()

        test.gossipRouter.setTopicPartialFlags(topicA, requestsPartial = true, supportsSendingPartial = true)
        test.gossipRouter.subscribe(topicA)
        test.mockRouter.waitForMessage({ it.firstSubscribeFor(topicA) != null })

        test.gossipRouter.unsubscribe(topicA)

        val received = test.mockRouter.waitForMessage({ it.firstUnsubscribeFor(topicA) != null })
        val unsub = received.firstUnsubscribeFor(topicA)!!
        assertThat(unsub.hasRequestsPartial()).isFalse()
        assertThat(unsub.hasSupportsSendingPartial()).isFalse()
    }

    @Test
    fun `inbound subscribe with requestsPartial only stores coerced flags`() {
        val test = newTest()

        val rpc = subscribeRpc(topicA, requestsPartial = true, supportsSendingPartial = false)
        test.mockRouter.sendToSingle(rpc)

        val peerId = test.router2.peerId
        // Receive-side coercion: supportsSendingPartial := requestsPartial || supportsSendingPartial
        assertThat(test.peerFlagsOnEventLoop(topicA, peerId))
            .isEqualTo(PartialSubFlags(requestsPartial = true, supportsSendingPartial = true))
    }

    @Test
    fun `inbound subscribe with supportsSendingPartial only stores that flag verbatim`() {
        val test = newTest()

        val rpc = subscribeRpc(topicA, requestsPartial = false, supportsSendingPartial = true)
        test.mockRouter.sendToSingle(rpc)

        assertThat(test.peerFlagsOnEventLoop(topicA, test.router2.peerId))
            .isEqualTo(PartialSubFlags(requestsPartial = false, supportsSendingPartial = true))
    }

    @Test
    fun `inbound subscribe with both flags false leaves state empty`() {
        val test = newTest()

        val rpc = subscribeRpc(topicA, requestsPartial = false, supportsSendingPartial = false)
        test.mockRouter.sendToSingle(rpc)

        assertThat(test.peerFlagsOnEventLoop(topicA, test.router2.peerId))
            .isEqualTo(PartialSubFlags.NONE)
        assertThat(test.snapshotPartialStateOnEventLoop()).doesNotContainKey(topicA)
    }

    @Test
    fun `inbound unsubscribe ignores flags and clears any prior peer state`() {
        val test = newTest()
        val peerId = test.router2.peerId

        test.mockRouter.sendToSingle(subscribeRpc(topicA, requestsPartial = true, supportsSendingPartial = true))
        assertThat(test.peerFlagsOnEventLoop(topicA, peerId))
            .isEqualTo(PartialSubFlags(requestsPartial = true, supportsSendingPartial = true))

        // Unsubscribe with malicious flags set: flags MUST be ignored, state MUST be cleared.
        val unsub = Rpc.RPC.newBuilder().addSubscriptions(
            Rpc.RPC.SubOpts.newBuilder()
                .setTopicid(topicA)
                .setSubscribe(false)
                .setRequestsPartial(true)
                .setSupportsSendingPartial(true)
        ).build()
        test.mockRouter.sendToSingle(unsub)

        assertThat(test.peerFlagsOnEventLoop(topicA, peerId))
            .isEqualTo(PartialSubFlags.NONE)
    }

    @Test
    fun `peer disconnect clears stored partial subscription state`() {
        val test = newTest()
        val peerId = test.router2.peerId

        test.mockRouter.sendToSingle(subscribeRpc(topicA, requestsPartial = true, supportsSendingPartial = true))
        assertThat(test.peerFlagsOnEventLoop(topicA, peerId))
            .isEqualTo(PartialSubFlags(requestsPartial = true, supportsSendingPartial = true))

        test.connection.disconnect()

        assertThat(test.peerFlagsOnEventLoop(topicA, peerId))
            .isEqualTo(PartialSubFlags.NONE)
    }

    @Test
    fun `local unsubscribe clears stored partial subscription state for that topic`() {
        val test = newTest()
        val peerId = test.router2.peerId

        test.gossipRouter.subscribe(topicA)
        test.mockRouter.sendToSingle(subscribeRpc(topicA, requestsPartial = true, supportsSendingPartial = true))
        test.mockRouter.sendToSingle(subscribeRpc(topicB, requestsPartial = true, supportsSendingPartial = true))

        assertThat(test.peerFlagsOnEventLoop(topicA, peerId))
            .isEqualTo(PartialSubFlags(requestsPartial = true, supportsSendingPartial = true))

        test.gossipRouter.unsubscribe(topicA)

        assertThat(test.peerFlagsOnEventLoop(topicA, peerId))
            .isEqualTo(PartialSubFlags.NONE)
        // Other topic state preserved
        assertThat(test.peerFlagsOnEventLoop(topicB, peerId))
            .isEqualTo(PartialSubFlags(requestsPartial = true, supportsSendingPartial = true))
    }

    private fun subscribeRpc(topic: String, requestsPartial: Boolean, supportsSendingPartial: Boolean): Rpc.RPC =
        Rpc.RPC.newBuilder().addSubscriptions(
            Rpc.RPC.SubOpts.newBuilder()
                .setTopicid(topic)
                .setSubscribe(true)
                .setRequestsPartial(requestsPartial)
                .setSupportsSendingPartial(supportsSendingPartial)
        ).build()
}
