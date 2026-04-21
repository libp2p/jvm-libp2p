package io.libp2p.pubsub

import pubsub.pb.Rpc

interface RpcPartsQueue {

    enum class SubscriptionStatus { Subscribed, Unsubscribed }

    fun addPublish(message: Rpc.Message)

    fun addSubscribe(topic: Topic) {
        addSubscribe(topic, requestsPartial = false, supportsSendingPartial = false)
    }

    fun addSubscribe(topic: Topic, requestsPartial: Boolean, supportsSendingPartial: Boolean) {
        addSubscription(topic, SubscriptionStatus.Subscribed, requestsPartial, supportsSendingPartial)
    }

    fun addUnsubscribe(topic: Topic) {
        addSubscription(topic, SubscriptionStatus.Unsubscribed, requestsPartial = false, supportsSendingPartial = false)
    }

    fun addSubscription(
        topic: Topic,
        status: SubscriptionStatus,
        requestsPartial: Boolean,
        supportsSendingPartial: Boolean
    )

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
        val requestsPartial: Boolean = false,
        val supportsSendingPartial: Boolean = false
    ) : AbstractPart {
        override fun appendToBuilder(builder: Rpc.RPC.Builder) {
            val subBuilder = builder.addSubscriptionsBuilder()
            subBuilder.topicid = topic
            subBuilder.subscribe = status == RpcPartsQueue.SubscriptionStatus.Subscribed
            // Per spec: partial flags MUST NOT be sent on unsubscribe (subscribe=false).
            if (status == RpcPartsQueue.SubscriptionStatus.Subscribed) {
                if (requestsPartial) subBuilder.requestsPartial = true
                if (supportsSendingPartial) subBuilder.supportsSendingPartial = true
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

    override fun addSubscription(
        topic: Topic,
        status: RpcPartsQueue.SubscriptionStatus,
        requestsPartial: Boolean,
        supportsSendingPartial: Boolean
    ) {
        addPart(SubscriptionPart(topic, status, requestsPartial, supportsSendingPartial))
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
