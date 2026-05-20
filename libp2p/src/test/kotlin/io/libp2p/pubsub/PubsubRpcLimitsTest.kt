package io.libp2p.pubsub

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

class PubsubRpcLimitsTest {

    @Test
    fun `NONE is a noop`() {
        assertThat(PubsubRpcLimits.NONE.isNoop).isTrue
    }

    @ParameterizedTest(name = "non-noop when {0}")
    @MethodSource("nonNoopMutations")
    fun `any configured limit or reject flag makes isNoop false`(
        @Suppress("UNUSED_PARAMETER") label: String,
        mutated: PubsubRpcLimits,
    ) {
        assertThat(mutated.isNoop).isFalse
    }

    companion object {
        @JvmStatic
        fun nonNoopMutations(): List<Arguments> = listOf(
            Arguments.of("maxPublishedMessages set", PubsubRpcLimits.NONE.copy(maxPublishedMessages = 1)),
            Arguments.of("maxTopicsPerPublishedMessage set", PubsubRpcLimits.NONE.copy(maxTopicsPerPublishedMessage = 1)),
            Arguments.of("maxSubscriptions set", PubsubRpcLimits.NONE.copy(maxSubscriptions = 1)),
            Arguments.of("maxIHaveMessageIds set", PubsubRpcLimits.NONE.copy(maxIHaveMessageIds = 1)),
            Arguments.of("maxIWantMessageIds set", PubsubRpcLimits.NONE.copy(maxIWantMessageIds = 1)),
            Arguments.of("maxGraftMessages set", PubsubRpcLimits.NONE.copy(maxGraftMessages = 1)),
            Arguments.of("maxPruneMessages set", PubsubRpcLimits.NONE.copy(maxPruneMessages = 1)),
            Arguments.of("maxPeersPerPruneMessage set", PubsubRpcLimits.NONE.copy(maxPeersPerPruneMessage = 1)),
            Arguments.of("maxIDontWantMessages set", PubsubRpcLimits.NONE.copy(maxIDontWantMessages = 1)),
            Arguments.of("maxIDontWantMessageIds set", PubsubRpcLimits.NONE.copy(maxIDontWantMessageIds = 1)),
            Arguments.of("rejectEmptyPublishEntries=true", PubsubRpcLimits.NONE.copy(rejectEmptyPublishEntries = true)),
            Arguments.of("rejectEmptyIDontWantEntries=true", PubsubRpcLimits.NONE.copy(rejectEmptyIDontWantEntries = true)),
        )
    }
}
