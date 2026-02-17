package io.libp2p.pubsub.gossip

import io.libp2p.pubsub.gossip.builders.GossipRouterBuilder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GossipRouterBuilderTest {

    @Test
    fun `builds GossipRouter with both extensions disabled by default`() {
        val router = GossipRouterBuilder().build()

        assertThat(router.gossipExtensionsState.testExtensionsEnabled()).isFalse()
        assertThat(router.gossipExtensionsState.partialMessagesEnabled()).isFalse()
    }

    @Test
    fun `localExtensionSupport reflects config in built router`() {
        val config = GossipExtensionsConfig(
            testExtensionEnabled = true,
            partialMessagesEnabled = false
        )
        val router = GossipRouterBuilder(gossipExtensionsConfig = config).build()

        val localSupport = router.gossipExtensionsState.localExtensionSupport
        assertThat(localSupport.testExtension).isTrue()
        assertThat(localSupport.partialMessages).isFalse()
    }
}
