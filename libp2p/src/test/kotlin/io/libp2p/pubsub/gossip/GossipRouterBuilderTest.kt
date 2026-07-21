package io.libp2p.pubsub.gossip

import io.libp2p.pubsub.PubsubOutboundLimits
import io.libp2p.pubsub.gossip.builders.GossipRouterBuilder
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
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
            .build()

        val localSupport = router.gossipExtensionsState.localExtensionSupport
        assertThat(localSupport.testExtension).isTrue()
        assertThat(localSupport.partialMessages).isTrue()
    }

    @Test
    fun `outbound byte default uses final gossip params`() {
        val builder = GossipRouterBuilder()
        builder.params = GossipParams(maxGossipMessageSize = 12_234_442)

        val router = builder.build()

        assertThat(router.outboundLimits.maxRetainedBytesPerPeer).isEqualTo(16_428_746)
        assertThat(router.outboundLimits.maxRetainedEntriesPerPeer).isEqualTo(1024)
    }

    @Test
    fun `explicit outbound limits are retained`() {
        val router = GossipRouterBuilder().apply {
            maxOutboundRetainedBytesPerPeer = 20L * 1024 * 1024
            maxOutboundRetainedEntriesPerPeer = 256
        }.build()

        assertThat(router.outboundLimits)
            .isEqualTo(PubsubOutboundLimits(20L * 1024 * 1024, 256))
    }

    @Test
    fun `outbound bytes must fit one maximum gossip message`() {
        val builder = GossipRouterBuilder(
            params = GossipParams(maxGossipMessageSize = 1024)
        ).apply {
            maxOutboundRetainedBytesPerPeer = 1023
        }

        assertThatIllegalArgumentException()
            .isThrownBy(builder::build)
            .withMessageContaining("1023")
            .withMessageContaining("1024")
    }

    @Test
    fun `outbound entries must be positive`() {
        val builder = GossipRouterBuilder().apply {
            maxOutboundRetainedEntriesPerPeer = 0
        }

        assertThatIllegalArgumentException()
            .isThrownBy(builder::build)
            .withMessageContaining("maxOutboundRetainedEntriesPerPeer")
    }

    @Test
    fun `invalid outbound limits do not construct a router`() {
        val builder = CreationTrackingGossipRouterBuilder(
            params = GossipParams(maxGossipMessageSize = 1024)
        ).apply {
            maxOutboundRetainedBytesPerPeer = 1023
        }

        assertThatIllegalArgumentException().isThrownBy(builder::build)
        assertThat(builder.createCount).isZero()
    }

    private class CreationTrackingGossipRouterBuilder(
        params: GossipParams
    ) : GossipRouterBuilder(params = params) {
        var createCount = 0

        override fun createGossipRouter(): GossipRouter {
            createCount++
            return super.createGossipRouter()
        }
    }
}
