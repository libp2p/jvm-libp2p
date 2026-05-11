package io.libp2p.pubsub.gossip

import io.libp2p.core.PeerId
import io.libp2p.pubsub.Topic
import io.libp2p.pubsub.gossip.builders.GossipRouterBuilder
import io.libp2p.pubsub.gossip.partialmessages.PartialMessagesHandler
import io.libp2p.pubsub.gossip.partialmessages.PartialMessagesPeerFeedback
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pubsub.pb.Rpc

class GossipRouterBuilderTest {

    private val nopHandler: PartialMessagesHandler<Unit> = object : PartialMessagesHandler<Unit> {
        override fun onIncomingRpc(from: PeerId, peerStates: Map<PeerId, Unit>, rpc: Rpc.PartialMessagesExtension, feedback: PartialMessagesPeerFeedback) {}
        override fun onEmitGossip(topic: Topic, groupId: ByteArray, gossipPeers: Collection<PeerId>, peerStates: Map<PeerId, Unit>, feedback: PartialMessagesPeerFeedback) {}
    }

    @Test
    fun `builds GossipRouter with both extensions disabled by default`() {
        val router = GossipRouterBuilder().build()

        assertThat(router.gossipExtensionsState.testExtensionsEnabled()).isFalse()
        assertThat(router.gossipExtensionsState.partialMessagesEnabled()).isFalse()
    }

    @Test
    fun `localExtensionSupport reflects config in built router`() {
        val router = GossipRouterBuilder()
            // Enabling only test extensions
            .enabledGossipExtensions(
                GossipExtension.TEST_EXTENSION
            )
            .build()

        val localSupport = router.gossipExtensionsState.localExtensionSupport
        assertThat(localSupport.testExtension).isTrue()
        assertThat(localSupport.partialMessages).isFalse()
    }

    @Test
    fun `localExtensionSupport with all extensions enabled`() {
        val router = GossipRouterBuilder()
            // Enabling all extensions
            .enabledGossipExtensions(
                GossipExtension.TEST_EXTENSION,
                GossipExtension.PARTIAL_MESSAGES,
            )
            .apply { partialMessagesHandler = nopHandler }
            .build()

        val localSupport = router.gossipExtensionsState.localExtensionSupport
        assertThat(localSupport.testExtension).isTrue()
        assertThat(localSupport.partialMessages).isTrue()
    }
}
