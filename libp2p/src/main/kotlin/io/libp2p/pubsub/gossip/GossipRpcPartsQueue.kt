package io.libp2p.pubsub.gossip

import io.libp2p.core.PeerId
import io.libp2p.etc.types.toProtobuf
import io.libp2p.pubsub.AbstractRpcPartsQueue
import io.libp2p.pubsub.MessageId
import io.libp2p.pubsub.RpcPartsBatch
import io.libp2p.pubsub.RpcPartsQueue
import io.libp2p.pubsub.TooLargeMessageException
import io.libp2p.pubsub.Topic
import pubsub.pb.Rpc

interface GossipRpcPartsQueue : RpcPartsQueue {

    fun addIHave(messageId: MessageId, topic: Topic)
    fun addIHaves(messageIds: Collection<MessageId>, topic: Topic) = messageIds.forEach { addIHave(it, topic) }
    fun addIWant(messageId: MessageId)
    fun addIWants(messageIds: Collection<MessageId>) = messageIds.forEach { addIWant(it) }

    fun addIDontWant(messageId: MessageId)
    fun addIDontWants(messageIds: Collection<MessageId>) = messageIds.forEach { addIDontWant(it) }

    fun addGraft(topic: Topic)

    /**
     * Gossip 1.0 variant
     */
    fun addPrune(topic: Topic)

    /**
     * Gossip 1.1 variant
     */
    fun addPrune(topic: Topic, backoffSeconds: Long, backoffPeers: List<PeerId>)

    fun addControlExtensions(ctrlMessage: Rpc.ControlExtensions)
}

/**
 * Gossip-aware [RpcPartsQueue] implementation.
 *
 * The queue respects gossip message-count limits, [GossipParams.maxGossipMessageSize] and
 * [GossipParams.maxControlMessageSize] when selecting parts for [takeBatch]. Size limiting uses
 * each part's conservative standalone RPC estimate, so a batch can be split before the actual
 * merged protobuf RPC is built.
 *
 * NOT thread safe
 */
open class DefaultGossipRpcPartsQueue(
    private val params: GossipParams
) : AbstractRpcPartsQueue(), GossipRpcPartsQueue {

    protected data class IHavePart(val messageId: MessageId, val topic: Topic) : AbstractPart() {
        override fun appendToBuilder(builder: Rpc.RPC.Builder) {
            val ctrlBuilder = builder.controlBuilder
            val iHaveBuilder = ctrlBuilder.ihaveBuilderList
                .find { it.topicID == topic }
                ?: ctrlBuilder.addIhaveBuilder().setTopicID(topic)

            iHaveBuilder.addMessageIDs(messageId.toProtobuf())
        }
    }

    protected data class IWantPart(val messageId: MessageId) : AbstractPart() {
        override fun appendToBuilder(builder: Rpc.RPC.Builder) {
            val ctrlBuilder = builder.controlBuilder
            val iWantBuilder = if (ctrlBuilder.iwantBuilderList.isEmpty()) {
                ctrlBuilder.addIwantBuilder()
            } else {
                ctrlBuilder.getIwantBuilder(0)
            }
            iWantBuilder.addMessageIDs(messageId.toProtobuf())
        }
    }

    protected data class IDontWantPart(val messageId: MessageId) : AbstractPart() {
        override fun appendToBuilder(builder: Rpc.RPC.Builder) {
            val ctrlBuilder = builder.controlBuilder
            val iDontWantBuilder = if (ctrlBuilder.idontwantBuilderList.isEmpty()) {
                ctrlBuilder.addIdontwantBuilder()
            } else {
                ctrlBuilder.getIdontwantBuilder(0)
            }
            iDontWantBuilder.addMessageIDs(messageId.toProtobuf())
        }
    }

    protected data class GraftPart(val topic: Topic) : AbstractPart() {
        override fun appendToBuilder(builder: Rpc.RPC.Builder) {
            builder.controlBuilder.addGraftBuilder().setTopicID(topic)
        }
    }

    protected data class PrunePart(val topic: Topic, val backoffSeconds: Long?, val backoffPeers: List<PeerId>) :
        AbstractPart() {
        override fun appendToBuilder(builder: Rpc.RPC.Builder) {
            val pruneBuilder = builder.controlBuilder.addPruneBuilder()
            pruneBuilder.setTopicID(topic)
            if (backoffSeconds != null) {
                pruneBuilder.setBackoff(backoffSeconds)
                pruneBuilder.addAllPeers(
                    backoffPeers.map {
                        Rpc.PeerInfo.newBuilder().setPeerID(it.bytes.toProtobuf()).build()
                    }
                )
            }
        }
    }

    protected data class ControlExtensionPart(val ctrlExtension: Rpc.ControlExtensions) : AbstractPart() {
        override fun appendToBuilder(builder: Rpc.RPC.Builder) {
            builder.controlBuilder.setExtensions(ctrlExtension)
        }
    }

    protected val priorityPartLists = listOf(
        mutableListOf<AbstractPart>(),
        mutableListOf(),
        mutableListOf()
    )

    override fun addPart(part: AbstractPart) {
        if (part.estimatedMaxSerializedSize > params.maxGossipMessageSize) {
            throw TooLargeMessageException(
                "RPC part estimated serialized size ${part.estimatedMaxSerializedSize} exceeds " +
                    "maxGossipMessageSize ${params.maxGossipMessageSize}: $part"
            )
        }
        priorityPartList(part).add(part)
        addPartSize(part)
    }

    private fun priorityPartList(part: AbstractPart): MutableList<AbstractPart> =
        when (part) {
            is ControlExtensionPart,
            is IDontWantPart -> priorityPartLists[URGENT_CONTROL_PRIORITY]
            is SubscriptionPart,
            is GraftPart,
            is PrunePart -> priorityPartLists[STATE_CONTROL_PRIORITY]
            is PublishPart,
            is IHavePart,
            is IWantPart -> priorityPartLists[BULK_PRIORITY]
            else -> priorityPartLists[BULK_PRIORITY]
        }

    override fun addIHave(messageId: MessageId, topic: Topic) {
        addPart(IHavePart(messageId, topic))
    }

    override fun addIWant(messageId: MessageId) {
        addPart(IWantPart(messageId))
    }

    override fun addIDontWant(messageId: MessageId) {
        addPart(IDontWantPart(messageId))
    }

    override fun addGraft(topic: Topic) {
        addPart(GraftPart(topic))
    }

    override fun addPrune(topic: Topic) {
        addPart(PrunePart(topic, null, emptyList()))
    }

    override fun addPrune(topic: Topic, backoffSeconds: Long, backoffPeers: List<PeerId>) {
        addPart(PrunePart(topic, backoffSeconds, backoffPeers))
    }

    override fun addControlExtensions(ctrlMessage: Rpc.ControlExtensions) {
        addPart(ControlExtensionPart(ctrlMessage))
    }

    override fun isEmpty(): Boolean = priorityPartLists.all { it.isEmpty() }

    override fun takeBatch(): RpcPartsBatch? {
        val topmostPriorityList = priorityPartLists.firstOrNull { it.isNotEmpty() } ?: return null
        return takeBatch(topmostPriorityList)
    }

    override fun dropLowPriority() {
        dropParts(priorityPartLists[BULK_PRIORITY])
    }

    private fun takeBatch(priorityParts: MutableList<AbstractPart>): RpcPartsBatch? {
        var publishCount = params.maxPublishedMessages ?: Int.MAX_VALUE
        var iHaveCount = params.maxIHaveLength
        var iDontWantCount = params.maxIDontWantMessageIdsPerRpc
        var subscriptionCount = params.maxSubscriptionsPerRpc
        var sizeLeft = params.maxGossipMessageSize

        /**
         * Remaining control-plane bytes for this batch, mirroring the inbound
         * [GossipParams.maxControlMessageSize] guard so we never emit an RPC a peer running this
         * same code would reject pre-decode. Per-category counters cannot enforce this on their
         * own: publish, IHAVE and IWANT parts share the BULK priority list and are merged into one
         * RPC, so only a cumulative byte counter bounds their sum.
         *
         * Publish payloads are exempt on the inbound side, so only a publish part's envelope
         * overhead is charged here. [AbstractPart.estimatedMaxSerializedSize] is a standalone-RPC
         * estimate and over-counts once parts merge and share protobuf wrappers, which errs towards
         * splitting a batch earlier than strictly required.
         *
         * [GossipParams.maxSubscriptionsPerRpc] is the one count that the byte budget does not
         * subsume, because peers enforce subscriptions by count and drop the whole RPC when the
         * count is exceeded.
         */
        var controlLeft = params.maxControlMessageSize

        var partIdx = 0

        while (partIdx < priorityParts.size &&
            publishCount > 0 && iHaveCount > 0 && iDontWantCount > 0 && subscriptionCount > 0
        ) {
            val part = priorityParts[partIdx]
            when (part) {
                is PublishPart -> publishCount--
                is IHavePart -> iHaveCount--
                is IDontWantPart -> iDontWantCount--
                is SubscriptionPart -> subscriptionCount--
            }
            sizeLeft -= part.estimatedMaxSerializedSize
            controlLeft -= when (part) {
                is PublishPart -> part.estimatedMaxSerializedSize - part.message.data.size()
                else -> part.estimatedMaxSerializedSize
            }
            // A part that alone exceeds a budget is still emitted, otherwise the queue would
            // never drain past it.
            if (partIdx > 0 && (sizeLeft < 0 || controlLeft < 0)) {
                break
            }
            partIdx++
        }
        if (partIdx == 0) return null

        val batchParts: MutableList<AbstractPart> = priorityParts.subList(0, partIdx)
        val ret = createBatch(batchParts)
        removePartsSize(batchParts)
        batchParts.clear()

        return ret
    }

    override fun dropAll(exception: Exception) {
        mergePromises(priorityPartLists.flatten()).completeExceptionally(exception)
        super.dropAll(exception)
        priorityPartLists.forEach { it.clear() }
    }

    private companion object {
        const val URGENT_CONTROL_PRIORITY = 0
        const val STATE_CONTROL_PRIORITY = 1
        const val BULK_PRIORITY = 2
    }
}
