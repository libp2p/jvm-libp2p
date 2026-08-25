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

    @Test
    fun `maxControlMessageSize defaults to null and is appended last`() {
        assertThat(PubsubRpcLimits.NONE.maxControlMessageSize).isNull()

        // Positional construction must keep its existing meaning: the new field is appended,
        // so the first twelve positional arguments are unchanged.
        val limits = PubsubRpcLimits(
            null, null, null, null, null, null, null, null,
            9, 10, false, true,
        )
        assertThat(limits.maxIDontWantMessages).isEqualTo(9)
        assertThat(limits.maxIDontWantMessageIds).isEqualTo(10)
        assertThat(limits.rejectEmptyPublishEntries).isFalse()
        assertThat(limits.rejectEmptyIDontWantEntries).isTrue()
        assertThat(limits.maxControlMessageSize).isNull()
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
            Arguments.of("maxControlMessageSize set", PubsubRpcLimits.NONE.copy(maxControlMessageSize = 1)),
        )
    }
}
