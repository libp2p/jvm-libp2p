package io.libp2p.pubsub

import io.libp2p.pubsub.flood.FloodRouter
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

    private fun AbstractRouter.readRpcLimits(): PubsubRpcLimits {
        val getter = AbstractRouter::class.java.getDeclaredMethod("getRpcLimits")
        getter.isAccessible = true
        return getter.invoke(this) as PubsubRpcLimits
    }
}
