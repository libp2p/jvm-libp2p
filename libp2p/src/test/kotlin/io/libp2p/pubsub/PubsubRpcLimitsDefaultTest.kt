package io.libp2p.pubsub

import io.libp2p.pubsub.flood.FloodRouter
import io.libp2p.pubsub.gossip.GossipParams
import io.libp2p.pubsub.gossip.builders.GossipRouterBuilder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Pins the toggle-off contract for the inbound count-validation pipeline: any
 * [AbstractRouter] subclass that does not opt in must observe
 * [PubsubRpcLimits.NONE], so its wire behaviour is unchanged by this defence.
 *
 * Uses reflection because `rpcLimits` is `protected` and [FloodRouter] is `final`.
 */
class PubsubRpcLimitsDefaultTest {

    @Test
    fun `FloodRouter inherits NONE rpcLimits from AbstractRouter`() {
        assertThat(FloodRouter().readRpcLimits()).isEqualTo(PubsubRpcLimits.NONE)
    }

    @Test
    fun `GossipRouter plumbs the control byte budget`() {
        val builder = GossipRouterBuilder(params = GossipParams(maxControlMessageSize = 123_456))
        try {
            assertThat(builder.build().readRpcLimits().maxControlMessageSize).isEqualTo(123_456)
        } finally {
            builder.scheduledAsyncExecutor.shutdownNow()
        }
    }

    @Test
    fun `GossipParams defaults the control byte budget to 256 KiB`() {
        assertThat(GossipParams().maxControlMessageSize).isEqualTo(256 * 1024)
        assertThat(GossipParams.builder().build().maxControlMessageSize).isEqualTo(256 * 1024)
    }

    private fun AbstractRouter.readRpcLimits(): PubsubRpcLimits {
        val getter = AbstractRouter::class.java.getDeclaredMethod("getRpcLimits")
        getter.isAccessible = true
        return getter.invoke(this) as PubsubRpcLimits
    }
}
