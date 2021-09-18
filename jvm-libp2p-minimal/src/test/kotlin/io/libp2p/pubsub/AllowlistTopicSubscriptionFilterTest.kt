package io.libp2p.pubsub

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class AllowlistTopicSubscriptionFilterTest {

    private val filter = AllowlistTopicSubscriptionFilter(hashSetOf("a", "b", "c", "d"))

    @Test
    fun `only allow whitelisted topics`() {
        val result = filter.filterIncomingSubscriptions(
            listOf(
                PubsubSubscription("a", true),
                PubsubSubscription("b", false),
                PubsubSubscription("c", true),
                PubsubSubscription("d", true),
                PubsubSubscription("e", true),
                PubsubSubscription("f", false),
                PubsubSubscription("g", false),
            ),
            listOf()
        )

        assertThat(result).isEqualTo(
            listOf(
                PubsubSubscription("a", true),
                PubsubSubscription("b", false),
                PubsubSubscription("c", true),
                PubsubSubscription("d", true),
            )
        )
    }

    @Test
    fun `return empty list when no allowed topics`() {
        val result = filter.filterIncomingSubscriptions(listOf(PubsubSubscription("nope", true)), listOf())
        assertThat(result).isEmpty()
    }
}
