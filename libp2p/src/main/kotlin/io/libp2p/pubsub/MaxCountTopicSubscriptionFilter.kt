package io.libp2p.pubsub

class MaxCountTopicSubscriptionFilter(
    private val maxSubscriptionsPerRequest: Int,
    private val maxSubscribedTopics: Int,
    private val delegateFilter: TopicSubscriptionFilter
) : TopicSubscriptionFilter {
    override fun canSubscribe(topic: Topic): Boolean =
        delegateFilter.canSubscribe(topic)

    override fun filterIncomingSubscriptions(
        subscriptions: Collection<PubsubSubscription>,
        currentlySubscribedTopics: Collection<Topic>
    ): Collection<PubsubSubscription> {
        if (subscriptions.size > maxSubscriptionsPerRequest) {
            throw InvalidMessageException("Too many subscriptions per request")
        }
        val filteredSubscriptions =
            delegateFilter.filterIncomingSubscriptions(subscriptions, currentlySubscribedTopics)

        var unsubscribed = 0
        var newSubscribed = 0
        filteredSubscriptions.forEach { subscription: PubsubSubscription ->
            val currentlySubscribed = currentlySubscribedTopics.contains(subscription.topic)
            if (subscription.subscribe && !currentlySubscribed) newSubscribed++
            if (!subscription.subscribe && currentlySubscribed) unsubscribed++
        }

        if (currentlySubscribedTopics.size + newSubscribed - unsubscribed > maxSubscribedTopics) {
            throw InvalidMessageException("Too many subscribed topics")
        }
        return filteredSubscriptions
    }
}
