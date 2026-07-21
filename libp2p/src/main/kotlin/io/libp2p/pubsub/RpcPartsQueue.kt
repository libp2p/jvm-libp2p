package io.libp2p.pubsub

import pubsub.pb.Rpc

interface RpcPartsQueue {

    enum class SubscriptionStatus { Subscribed, Unsubscribed }

    fun addPublish(message: Rpc.Message)
    fun addRpc(rpc: Rpc.RPC)

    fun addSubscribe(topic: Topic) {
        addSubscription(topic, SubscriptionStatus.Subscribed)
    }

    fun addUnsubscribe(topic: Topic) {
        addSubscription(topic, SubscriptionStatus.Unsubscribed)
    }

    fun addSubscription(topic: Topic, status: SubscriptionStatus)

    fun takeMerged(): List<Rpc.RPC>
}

/**
 * Default [RpcPartsQueue] implementation
 *
 * NOT thread safe
 */
open class DefaultRpcPartsQueue(
    private val maxMessageSize: Int = Int.MAX_VALUE
) : RpcPartsQueue {

    protected interface AbstractPart {
        fun appendToBuilder(builder: Rpc.RPC.Builder)

        fun standaloneRpcSize(): Int =
            Rpc.RPC.newBuilder()
                .also(::appendToBuilder)
                .build()
                .serializedSize
    }

    protected data class PublishPart(val message: Rpc.Message) : AbstractPart {
        override fun appendToBuilder(builder: Rpc.RPC.Builder) {
            builder.addPublish(message)
        }
    }

    protected data class RpcPart(val rpc: Rpc.RPC) : AbstractPart {
        override fun appendToBuilder(builder: Rpc.RPC.Builder) {
            builder.mergeFrom(rpc)
        }

        override fun standaloneRpcSize(): Int = rpc.serializedSize
    }

    protected data class SubscriptionPart(val topic: Topic, val status: RpcPartsQueue.SubscriptionStatus) : AbstractPart {
        override fun appendToBuilder(builder: Rpc.RPC.Builder) {
            builder.addSubscriptionsBuilder().apply {
                setTopicid(topic)
                setSubscribe(status == RpcPartsQueue.SubscriptionStatus.Subscribed)
            }
        }
    }

    protected open val parts = mutableListOf<AbstractPart>()

    protected open fun addPart(part: AbstractPart) {
        parts += part
    }

    protected fun requirePartFits(part: AbstractPart): Int =
        part.standaloneRpcSize().also { size ->
            require(size <= maxMessageSize) {
                "Outbound RPC part size $size exceeds maxGossipMessageSize $maxMessageSize"
            }
        }

    override fun addPublish(message: Rpc.Message) {
        addPart(PublishPart(message))
    }

    override fun addRpc(rpc: Rpc.RPC) {
        addPart(RpcPart(rpc))
    }

    override fun addSubscription(topic: Topic, status: RpcPartsQueue.SubscriptionStatus) {
        addPart(SubscriptionPart(topic, status))
    }

    override fun takeMerged(): List<Rpc.RPC> {
        val messages = mutableListOf<Rpc.RPC>()
        var builder = Rpc.RPC.newBuilder()
        var estimatedSize = 0
        var hasMergedParts = false

        fun flushBuilder() {
            messages += builder.build()
            builder = Rpc.RPC.newBuilder()
            estimatedSize = 0
            hasMergedParts = false
        }

        parts.forEach { part ->
            val partSize = requirePartFits(part)
            if (part is RpcPart) {
                if (hasMergedParts) flushBuilder()
                messages += part.rpc
            } else {
                if (hasMergedParts && estimatedSize > maxMessageSize - partSize) {
                    flushBuilder()
                }
                part.appendToBuilder(builder)
                estimatedSize += partSize
                hasMergedParts = true
            }
        }
        parts.clear()
        if (hasMergedParts) {
            flushBuilder()
        } else if (messages.isEmpty()) {
            messages += builder.build()
        }
        return messages
    }
}
