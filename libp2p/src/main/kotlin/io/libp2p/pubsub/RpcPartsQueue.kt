package io.libp2p.pubsub

import io.libp2p.etc.types.forward
import pubsub.pb.Rpc
import java.util.concurrent.CompletableFuture

data class RpcPartsBatch(
    val rpc: Rpc.RPC,
    val writePromise: CompletableFuture<Unit>
)

/**
 * Accumulates outbound pubsub RPC parts before they are written to a peer.
 *
 * Implementations may decide how many queued parts can be sent in a single outbound RPC. For example,
 * gossip queues split parts into protocol-limit-valid batches, while the default queue drains
 * everything at once.
 *
 * Implementations are not expected to be thread-safe; routers own and access queues on their event
 * executor.
 */
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

    /**
     * Returns true when there are no queued parts.
     */
    fun isEmpty(): Boolean

    /**
     * Removes queued parts for the next outbound write and returns them as a ready-to-send batch.
     *
     * The returned [RpcPartsBatch] contains a merged protobuf RPC and the write promise to complete
     * when that RPC write finishes. Completing the batch promise forwards the result to publish
     * promises attached with [addPublish].
     *
     * The original queue may still contain later parts when protocol limits require multiple
     * outbound RPCs.
     *
     * Returns `null` when the queue is empty.
     */
    fun takeBatch(): RpcPartsBatch?

    /**
     * Fails all queued publish promises with [exception] and clears this queue.
     */
    fun abort(exception: Exception)
}

/**
 * Default [RpcPartsQueue] implementation
 *
 * NOT thread safe
 */
open class DefaultRpcPartsQueue : RpcPartsQueue {

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
    override fun takeBatch(): RpcPartsBatch? {
        if (parts.isEmpty()) return null
        val ret = createBatch(parts.toList())
        parts.clear()
        return ret
    }

    protected fun createBatch(batchParts: List<AbstractPart>): RpcPartsBatch {
        return RpcPartsBatch(
            mergeRpc(batchParts),
            mergePromises(batchParts)
        )
    }

    private fun mergePromises(batchParts: List<AbstractPart>): CompletableFuture<Unit> {
        val ret = CompletableFuture<Unit>()
        batchParts.mapNotNull { it.writePromise }.forEach { ret.forward(it) }
        return ret
    }

    private fun mergeRpc(batchParts: List<AbstractPart>): Rpc.RPC {
        val builder = Rpc.RPC.newBuilder()
        batchParts.forEach {
            it.appendToBuilder(builder)
        }
        return builder.build()
    }

    override fun abort(exception: Exception) {
        mergePromises(parts).completeExceptionally(exception)
        parts.clear()
    }
}
