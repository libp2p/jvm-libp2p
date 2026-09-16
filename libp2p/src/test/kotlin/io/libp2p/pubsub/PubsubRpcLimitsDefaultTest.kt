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
    fun `GossipRouter plumbs the total field budget`() {
        val builder = GossipRouterBuilder(params = GossipParams(maxTotalFields = 1_234))
        try {
            assertThat(builder.build().readRpcLimits().maxTotalFields).isEqualTo(1_234)
        } finally {
            builder.scheduledAsyncExecutor.shutdownNow()
        }
    }

    @Test
    fun `GossipParams defaults the total field budget`() {
        assertThat(GossipParams().maxTotalFields).isEqualTo(65536)
        assertThat(GossipParams.builder().build().maxTotalFields).isEqualTo(65536)
    }

    /**
     * The field budget is only meaningful relative to the byte budget: their ratio is the minimum
     * average wire bytes per field below which it can fire. Conformant peers sit well above it -
     * the tight shape is short topic names at ~8 bytes per field - so the defaults must keep this
     * ratio at or below 4. Pinned because raising one default without the other silently changes
     * which honest traffic gets dropped.
     */
    @Test
    fun `default field budget leaves at most four wire bytes per field`() {
        val params = GossipParams()
        val bytesPerField = params.maxControlMessageSize.toDouble() / params.maxTotalFields!!
        assertThat(bytesPerField).isLessThanOrEqualTo(4.0)
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
