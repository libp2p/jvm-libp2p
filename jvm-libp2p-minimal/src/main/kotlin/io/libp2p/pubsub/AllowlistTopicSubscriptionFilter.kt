package io.libp2p.pubsub

class AllowlistTopicSubscriptionFilter(private val allowedTopics: Set<Topic>) : TopicSubscriptionFilter {
    override fun canSubscribe(topic: Topic): Boolean = allowedTopics.contains(topic)
}
