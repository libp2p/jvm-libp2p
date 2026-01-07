package io.libp2p.pubsub

import pubsub.pb.Rpc

interface RpcPartsQueue {

    enum class SubscriptionStatus { Subscribed, Unsubscribed }

    fun addPublish(message: Rpc.Message)

    fun addSubscribe(topic: Topic) {
        addSubscription(topic, SubscriptionStatus.Subscribed)
    }

    fun addUnsubscribe(topic: Topic) {
        addSubscription(topic, SubscriptionStatus.Unsubscribed)
    }

    fun addSubscription(topic: Topic, status: SubscriptionStatus)

    /**
     * Add a subscription with partial message options
     */
    fun addSubscription(topic: Topic, status: SubscriptionStatus, requestsPartial: Boolean) {
        // Default implementation ignores partial flag
        addSubscription(topic, status)
    }

    /**
     * Add a subscribe message with partial message options
     */
    fun addSubscribePartial(topic: Topic, requestsPartial: Boolean = true) {
        addSubscription(topic, SubscriptionStatus.Subscribed, requestsPartial)
    }

    fun takeMerged(): List<Rpc.RPC>
}

/**
 * Default [RpcPartsQueue] implementation
 *
 * NOT thread safe
 */
open class DefaultRpcPartsQueue : RpcPartsQueue {

    protected interface AbstractPart {
        fun appendToBuilder(builder: Rpc.RPC.Builder)
    }

    protected data class PublishPart(val message: Rpc.Message) : AbstractPart {
        override fun appendToBuilder(builder: Rpc.RPC.Builder) {
            builder.addPublish(message)
        }
    }

    protected data class SubscriptionPart(
        val topic: Topic,
        val status: RpcPartsQueue.SubscriptionStatus,
        val requestsPartial: Boolean = false
    ) : AbstractPart {
        override fun appendToBuilder(builder: Rpc.RPC.Builder) {
            val subOptsBuilder = builder.addSubscriptionsBuilder()
            subOptsBuilder.setTopicid(topic)
            subOptsBuilder.setSubscribe(status == RpcPartsQueue.SubscriptionStatus.Subscribed)
            if (requestsPartial) {
                subOptsBuilder.setRequestsPartial(true)
            }
        }
    }

    protected open val parts = mutableListOf<AbstractPart>()

    protected open fun addPart(part: AbstractPart) {
        parts += part
    }

    override fun addPublish(message: Rpc.Message) {
        addPart(PublishPart(message))
    }

    override fun addSubscription(topic: Topic, status: RpcPartsQueue.SubscriptionStatus) {
        addPart(SubscriptionPart(topic, status, false))
    }

    override fun addSubscription(topic: Topic, status: RpcPartsQueue.SubscriptionStatus, requestsPartial: Boolean) {
        addPart(SubscriptionPart(topic, status, requestsPartial))
    }

    override fun takeMerged(): List<Rpc.RPC> {
        val builder = Rpc.RPC.newBuilder()
        parts.forEach {
            it.appendToBuilder(builder)
        }
        parts.clear()
        return listOf(builder.build())
    }
}
