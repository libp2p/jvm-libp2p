package io.libp2p.pubsub

interface TopicSubscriptionFilter {

    fun canSubscribe(topic: Topic): Boolean

    @JvmDefault
    fun filterIncomingSubscriptions(
        subscriptions: Collection<PubsubSubscription>,
        currentlySubscribedTopics: Collection<Topic>
    ): Collection<PubsubSubscription> {
        return subscriptions.filter { canSubscribe(it.topic) }
    }

    class AllowAllTopicSubscriptionFilter : TopicSubscriptionFilter {
        override fun canSubscribe(topic: Topic): Boolean = true
        override fun filterIncomingSubscriptions(
            subscriptions: Collection<PubsubSubscription>,
            currentlySubscribedTopics: Collection<Topic>
        ): Collection<PubsubSubscription> = subscriptions
    }
}
