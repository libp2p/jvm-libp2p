package io.libp2p.pubsub

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class MaxCountTopicSubscriptionFilterTest {

    private val filter = MaxCountTopicSubscriptionFilter(5, 10, AllowPrefixedTopicSubscriptionFilter())

    @Test
    fun `allow requests within limits`() {
        val subscriptions = listOf(
            PubsubSubscription("allow_1", true),
            PubsubSubscription("allow_2", true),
            PubsubSubscription("allow_3", true),
        )
        val result = filter.filterIncomingSubscriptions(subscriptions, listOf("existing_1"))
        assertThat(result).isEqualTo(subscriptions)
    }

    @Test
    fun `should apply delegate filter`() {
        val result = filter.filterIncomingSubscriptions(
            listOf(
                PubsubSubscription("allow_1", true),
                PubsubSubscription("nope_1", true),
                PubsubSubscription("nope_2", false),
                PubsubSubscription("allow_2", false),
            ),
            listOf()
        )
        assertThat(result).isEqualTo(listOf(PubsubSubscription("allow_1", true), PubsubSubscription("allow_2", false)))
    }

    @Test
    fun `reject requests with too many subscriptions in one message`() {
        assertThrows<InvalidMessageException> {
            filter.filterIncomingSubscriptions(
                listOf(
                    PubsubSubscription("allow_1", true),
                    PubsubSubscription("nope_2", true),
                    PubsubSubscription("allow_3", true),
                    PubsubSubscription("allow_4", false),
                    PubsubSubscription("nope_5", false),
                    PubsubSubscription("allow_6", true),
                ),
                listOf()
            )
        }
    }

    @Test
    fun `reject requests that result in too many subscriptions`() {
        assertThrows<InvalidMessageException> {
            filter.filterIncomingSubscriptions(
                listOf(
                    PubsubSubscription("allow_8", true),
                    PubsubSubscription("allow_9", true),
                    PubsubSubscription("allow_10", true),
                    PubsubSubscription("allow_11", true),
                ),
                listOf("allow_1", "allow_2", "allow_3", "allow_4", "allow_5", "allow_6", "allow_7")
            )
        }
    }

    @Test
    fun `allow requests that stay under limit because of filtering`() {
        val result = filter.filterIncomingSubscriptions(
            listOf(
                PubsubSubscription("allow_8", true),
                PubsubSubscription("allow_9", true),
                PubsubSubscription("allow_10", true),
                PubsubSubscription("nope_11", true),
            ),
            listOf("allow_1", "allow_2", "allow_3", "allow_4", "allow_5", "allow_6", "allow_7")
        )
        assertThat(result).isEqualTo(
            listOf(
                PubsubSubscription("allow_8", true),
                PubsubSubscription("allow_9", true),
                PubsubSubscription("allow_10", true),
            )
        )
    }

    @Test
    fun `allow requests that stay under limit because of unsubscriptions`() {
        val result = filter.filterIncomingSubscriptions(
            listOf(
                PubsubSubscription("allow_7", false),
                PubsubSubscription("allow_8", true),
                PubsubSubscription("allow_9", true),
                PubsubSubscription("allow_10", true),
                PubsubSubscription("allow_11", true),
            ),
            listOf("allow_1", "allow_2", "allow_3", "allow_4", "allow_5", "allow_6", "allow_7")
        )
        assertThat(result).isEqualTo(
            listOf(
                PubsubSubscription("allow_7", false),
                PubsubSubscription("allow_8", true),
                PubsubSubscription("allow_9", true),
                PubsubSubscription("allow_10", true),
                PubsubSubscription("allow_11", true),
            )
        )
    }

    @Test
    fun `not count unsubscriptions for topics not currently subscribed to`() {
        assertThrows<InvalidMessageException> {
            filter.filterIncomingSubscriptions(
                listOf(
                    PubsubSubscription("allow_what", false),
                    PubsubSubscription("allow_8", true),
                    PubsubSubscription("allow_9", true),
                    PubsubSubscription("allow_10", true),
                    PubsubSubscription("allow_11", true),
                ),
                listOf("allow_1", "allow_2", "allow_3", "allow_4", "allow_5", "allow_6", "allow_7")
            )
        }
    }

    @Test
    fun `not count subscriptions for topics already subscribed to`() {
        val subscriptions = listOf(
            PubsubSubscription("allow_6", true),
            PubsubSubscription("allow_7", true),
            PubsubSubscription("allow_8", true),
            PubsubSubscription("allow_9", true),
            PubsubSubscription("allow_10", true),
        )
        val result = filter.filterIncomingSubscriptions(
            subscriptions, listOf("allow_1", "allow_2", "allow_3", "allow_4", "allow_5", "allow_6", "allow_7")
        )
        assertThat(result).isEqualTo(subscriptions)
    }

    class AllowPrefixedTopicSubscriptionFilter : TopicSubscriptionFilter {
        override fun canSubscribe(topic: Topic): Boolean = topic.startsWith("allow")
    }
}
