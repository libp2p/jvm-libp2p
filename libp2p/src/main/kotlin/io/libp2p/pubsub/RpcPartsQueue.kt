package io.libp2p.pubsub

import io.libp2p.etc.types.forward
import pubsub.pb.Rpc
import java.util.concurrent.CompletableFuture

interface RpcPartsQueue {

    enum class SubscriptionStatus { Subscribed, Unsubscribed }

    fun addPublish(message: Rpc.Message)
    fun addPublish(message: Rpc.Message, writePromise: CompletableFuture<Unit>)

    fun addSubscribe(topic: Topic) {
        addSubscription(topic, SubscriptionStatus.Subscribed)
    }

    fun addUnsubscribe(topic: Topic) {
        addSubscription(topic, SubscriptionStatus.Unsubscribed)
    }

    fun addSubscription(topic: Topic, status: SubscriptionStatus)

    fun isEmpty(): Boolean
    fun takeBatch(): RpcPartsQueue

    fun mergePromises(): CompletableFuture<Unit>
    fun mergeRpc(): Rpc.RPC

    fun abort(exception: Exception)
}

/**
 * Default [RpcPartsQueue] implementation
 *
 * NOT thread safe
 */
open class DefaultRpcPartsQueue : RpcPartsQueue {

    constructor()
    protected constructor(parts: List<AbstractPart>) {
        this.parts.addAll(parts)
    }

    protected interface AbstractPart {
        fun appendToBuilder(builder: Rpc.RPC.Builder)
        val writePromise: CompletableFuture<Unit>? get() = null
    }

    protected data class PublishPart(
        val message: Rpc.Message,
        override val writePromise: CompletableFuture<Unit>? = null
    ) : AbstractPart {
        override fun appendToBuilder(builder: Rpc.RPC.Builder) {
            builder.addPublish(message)
        }
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

    override fun addPublish(message: Rpc.Message) {
        addPart(PublishPart(message))
    }

    override fun addPublish(
        message: Rpc.Message,
        writePromise: CompletableFuture<Unit>
    ) {
        addPart(PublishPart(message, writePromise))
    }

    override fun addSubscription(topic: Topic, status: RpcPartsQueue.SubscriptionStatus) {
        addPart(SubscriptionPart(topic, status))
    }

    override fun isEmpty(): Boolean = parts.isEmpty()
    override fun takeBatch(): RpcPartsQueue {
        val ret = DefaultRpcPartsQueue(parts.toList())
        parts.clear()
        return ret
    }

    override fun mergePromises(): CompletableFuture<Unit> {
        val ret = CompletableFuture<Unit>()
        parts.mapNotNull { it.writePromise }.forEach { ret.forward(it) }
        return ret
    }

    override fun mergeRpc(): Rpc.RPC {
        val builder = Rpc.RPC.newBuilder()
        parts.forEach {
            it.appendToBuilder(builder)
        }
        parts.clear()
        return builder.build()
    }

    override fun abort(exception: Exception) {
        mergePromises().completeExceptionally(exception)
        parts.clear()
    }
}
