package io.libp2p.pubsub

import com.google.protobuf.UnknownFieldSet
import io.libp2p.etc.types.forward
import pubsub.pb.Rpc
import java.util.concurrent.CompletableFuture

/**
 * A ready-to-send RPC and its associated write completion.
 */
data class RpcPartsBatch(
    val rpc: Rpc.RPC,
    val writePromise: CompletableFuture<Unit>,
)

/**
 * Accumulates outbound pubsub RPC parts before they are written to a peer.
 *
 * Implementations may decide how many queued parts can be sent in a single outbound RPC. For example,
 * gossip queues split parts into protocol-limit-valid batches, while the default queue drains
 * everything at once.
 *
 * Every queued part contributes a conservative serialized-size estimate. [estimateMaxSerializedSize]
 * returns the accumulated estimate for all currently queued parts so callers can reason about the
 * pending outbound data before the final RPC is built.
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
     * Drops queued parts that are considered low priority by this queue implementation.
     *
     * Publish promises associated with dropped parts are failed with [DroppedRpcPartsException].
     * Higher-priority parts, if any, are left queued. Queues without explicit priorities may treat
     * all queued parts as low priority.
     */
    fun dropLowPriority()

    /**
     * Fails all queued publish promises with [exception] and clears this queue.
     */
    fun dropAll(exception: Exception)

    /**
     * Returns a conservative upper bound for the serialized size of all currently queued parts.
     *
     * The estimate is intentionally allowed to be larger than the final serialized RPC size. Parts
     * may share protobuf wrapper messages when [takeBatch] merges them, while the estimate is based
     * on each part's standalone serialized form. Implementations may use the same per-part estimate
     * to decide where to split batches before building the final protobuf message.
     */
    fun estimateMaxSerializedSize(): Int
}

/**
 * Counts the unknown protobuf fields protobuf-java retained on a parsed message.
 *
 * A forwarded message carries the unknown fields its sender included, and protobuf-java
 * re-serializes them, so the inbound walker charges them and an outbound estimate that ignored them
 * would under-count. Each scalar occurrence is one field; a group is one field plus its interior,
 * matching [RpcMessageCountValidator]'s `skipCounting`.
 */
internal fun countUnknownFields(unknownFields: UnknownFieldSet): Int {
    var count = 0
    for (entry in unknownFields.asMap()) {
        val field = entry.value
        count += field.varintList.size +
            field.fixed32List.size +
            field.fixed64List.size +
            field.lengthDelimitedList.size
        field.groupList.forEach { count += 1 + countUnknownFields(it) }
    }
    return count
}

abstract class AbstractRpcPartsQueue : RpcPartsQueue {

    protected abstract class AbstractPart {

        abstract fun appendToBuilder(builder: Rpc.RPC.Builder)

        /**
         * Conservative upper bound for this part when serialized as a standalone RPC.
         *
         * The value is lazy because [appendToBuilder] depends on subclass state. It is cached after
         * the part is added to a queue and can then be used for accumulated queue-size accounting.
         */
        val estimatedMaxSerializedSize: Int by lazy {
            Rpc.RPC.newBuilder().also { appendToBuilder(it) }.buildPartial().serializedSize
        }

        /**
         * Number of protobuf fields this part's standalone RPC contains, counted exactly as
         * [RpcMessageCountValidator] counts them on the inbound side: one per field occurrence at
         * every nesting level. Over-counts once parts merge and share protobuf wrappers, which errs
         * towards splitting a batch earlier than strictly required - the queue must never emit an
         * RPC that a peer running this same code would reject pre-decode.
         *
         * Every part states its own count instead of deriving one by protobuf reflection: a part's
         * shape is fixed and known here at compile time, and reflecting over it costs two orders of
         * magnitude more than reading it off the part, on the event thread, once per peer. A new
         * part type therefore fails to compile until it states a count, and
         * `RpcPartsFieldCountTest` pins each count against the real inbound walker.
         */
        abstract val estimatedMaxFieldCount: Int

        open val writePromise: CompletableFuture<Unit>? = null
    }

    protected data class PublishPart(
        val message: Rpc.Message,
        override val writePromise: CompletableFuture<Unit>? = null
    ) : AbstractPart() {
        override fun appendToBuilder(builder: Rpc.RPC.Builder) {
            builder.addPublish(message)
        }

        // The `publish` entry itself, then one per field set inside the Message. Pinned against the
        // inbound walker by RpcPartsFieldCountTest; schema changes are guarded by RpcSchemaAccountingTest.
        // FloodRouter shares this part but never reads the field count, so defer the work (including
        // walking unknown fields). Queue access is single-threaded, so synchronization is unnecessary.
        override val estimatedMaxFieldCount: Int by lazy(LazyThreadSafetyMode.NONE) {
            var n = 1 + message.topicIDsCount
            if (message.hasFrom()) n++
            if (message.hasData()) n++
            if (message.hasSeqno()) n++
            if (message.hasSignature()) n++
            if (message.hasKey()) n++
            n + countUnknownFields(message.unknownFields)
        }

        override fun toString(): String =
            "PublishPart(" +
                "dataSize=${message.data.size()}, " +
                "topicIDs=${message.topicIDsList}, " +
                "hasWritePromise=${writePromise != null}" +
                ")"
    }

    protected data class SubscriptionPart(val topic: Topic, val status: RpcPartsQueue.SubscriptionStatus) : AbstractPart() {
        override fun appendToBuilder(builder: Rpc.RPC.Builder) {
            builder.addSubscriptionsBuilder().apply {
                setTopicid(topic)
                setSubscribe(status == RpcPartsQueue.SubscriptionStatus.Subscribed)
            }
        }

        // subscriptions entry + subscribe + topicid
        override val estimatedMaxFieldCount: Int get() = 3
    }

    private var estimatedMaxSerializedSizeAccum: Int = 0

    protected abstract fun addPart(part: AbstractPart)

    protected open fun addPartSize(part: AbstractPart) {
        estimatedMaxSerializedSizeAccum += part.estimatedMaxSerializedSize
    }
    protected fun removePartsSize(removedParts: List<AbstractPart>) {
        removedParts.forEach {
            removePartSize(it)
        }
    }
    protected fun removePartSize(removedPart: AbstractPart) {
        estimatedMaxSerializedSizeAccum -= removedPart.estimatedMaxSerializedSize
    }

    override fun estimateMaxSerializedSize(): Int = estimatedMaxSerializedSizeAccum

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

    protected fun createBatch(batchParts: List<AbstractPart>): RpcPartsBatch {
        return RpcPartsBatch(
            mergeRpc(batchParts),
            mergePromises(batchParts)
        )
    }

    protected fun mergePromises(batchParts: List<AbstractPart>): CompletableFuture<Unit> {
        val ret = CompletableFuture<Unit>()
        batchParts.mapNotNull { it.writePromise }.forEach { ret.forward(it) }
        return ret
    }

    protected fun dropParts(droppedParts: MutableList<AbstractPart>) {
        if (droppedParts.isEmpty()) return
        mergePromises(droppedParts).completeExceptionally(
            DroppedRpcPartsException("Queued low priority RPC parts were dropped")
        )
        removePartsSize(droppedParts)
        droppedParts.clear()
    }

    private fun mergeRpc(batchParts: List<AbstractPart>): Rpc.RPC {
        val builder = Rpc.RPC.newBuilder()
        batchParts.forEach {
            it.appendToBuilder(builder)
        }
        return builder.build()
    }

    override fun dropAll(exception: Exception) {
        estimatedMaxSerializedSizeAccum = 0
    }
}

/**
 * Default [RpcPartsQueue] implementation
 *
 * NOT thread safe
 */
open class DefaultRpcPartsQueue : AbstractRpcPartsQueue() {

    protected open val parts = mutableListOf<AbstractPart>()

    override fun addPart(part: AbstractPart) {
        parts += part
        addPartSize(part)
    }

    override fun isEmpty(): Boolean = parts.isEmpty()
    override fun takeBatch(): RpcPartsBatch? {
        if (parts.isEmpty()) return null
        val ret = createBatch(parts.toList())
        removePartsSize(parts)
        parts.clear()
        return ret
    }

    override fun dropLowPriority() {
        dropParts(parts)
    }

    override fun dropAll(exception: Exception) {
        mergePromises(parts).completeExceptionally(exception)
        super.dropAll(exception)
        parts.clear()
    }
}
