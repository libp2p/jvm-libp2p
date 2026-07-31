package io.libp2p.pubsub.gossip

import io.libp2p.core.PeerId
import io.libp2p.etc.types.toProtobuf
import io.libp2p.pubsub.DefaultRpcPartsQueue
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

    // TODO Need to check if we should handle when control extension and extension messages could be separated by split  (https://github.com/libp2p/jvm-libp2p/issues/440)
    fun addControlExtensions(ctrlMessage: Rpc.ControlExtensions)
}

/**
 * Gossip-aware [RpcPartsQueue] implementation.
 *
 * The queue respects gossip message-count limits and [GossipParams.maxGossipMessageSize] when
 * selecting parts for [takeBatch]. Size limiting uses each part's conservative standalone RPC
 * estimate, so a batch can be split before the actual merged protobuf RPC is built.
 *
 * NOT thread safe
 */
open class DefaultGossipRpcPartsQueue(
    private val params: GossipParams
) : DefaultRpcPartsQueue(), GossipRpcPartsQueue {

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

    protected val urgentControlParts = mutableListOf<AbstractPart>()
    protected val stateControlParts = mutableListOf<AbstractPart>()
    protected val bulkParts = mutableListOf<AbstractPart>()
    protected val priorityPartLists = listOf(urgentControlParts, stateControlParts, bulkParts)

    override fun addPart(part: AbstractPart) {
        if (part.estimatedMaxSerializedSize > params.maxGossipMessageSize) {
            throw TooLargeMessageException(
                "RPC part estimated serialized size ${part.estimatedMaxSerializedSize} exceeds " +
                    "maxGossipMessageSize ${params.maxGossipMessageSize}: $part"
            )
        }
        priorityPartList(part).add(part)
        super.addPart(part)
    }

    private fun priorityPartList(part: AbstractPart): MutableList<AbstractPart> =
        when (part) {
            is IDontWantPart -> urgentControlParts
            is SubscriptionPart,
            is ControlExtensionPart,
            is GraftPart,
            is PrunePart -> stateControlParts
            is PublishPart,
            is IHavePart,
            is IWantPart -> bulkParts
            else -> bulkParts
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

    override fun takeBatch(): RpcPartsBatch? {
        val priorityParts = priorityPartLists.firstOrNull { it.isNotEmpty() } ?: return null
        var publishCount = params.maxPublishedMessages ?: Int.MAX_VALUE
        var subscriptionCount = params.maxSubscriptions ?: Int.MAX_VALUE
        var iHaveCount = params.maxIHaveLength
        var iWantCount = params.maxIWantMessageIds ?: Int.MAX_VALUE
        var iDontWantCount = params.maxIDontWantMessageIds
        var graftCount = params.maxGraftMessages ?: Int.MAX_VALUE
        var pruneCount = params.maxPruneMessages ?: Int.MAX_VALUE
        var sizeLeft = params.maxGossipMessageSize

        var partIdx = 0

        while (partIdx < priorityParts.size &&
            publishCount > 0 && subscriptionCount > 0 && iHaveCount > 0 &&
            iWantCount > 0 && iDontWantCount > 0 && graftCount > 0 && pruneCount > 0
        ) {
            val part = priorityParts[partIdx]
            when (part) {
                is PublishPart -> publishCount--
                is SubscriptionPart -> subscriptionCount--
                is IHavePart -> iHaveCount--
                is IWantPart -> iWantCount--
                is IDontWantPart -> iDontWantCount--
                is GraftPart -> graftCount--
                is PrunePart -> pruneCount--
            }
            sizeLeft -= part.estimatedMaxSerializedSize
            if (sizeLeft < 0) {
                break
            }
            partIdx++
        }
        if (partIdx == 0) return null

        val batchParts: MutableList<AbstractPart> = priorityParts.subList(0, partIdx)
        val ret = createBatch(batchParts)
        onPartsRemoving(batchParts)
        batchParts.forEach { removePart(it, parts) }
        batchParts.clear()

        return ret
    }

    private fun removePart(part: AbstractPart, from: MutableList<AbstractPart>) {
        val idx = from.indexOfFirst { it === part }
        if (idx >= 0) {
            from.removeAt(idx)
        }
    }

    override fun abort(exception: Exception) {
        super.abort(exception)
        priorityPartLists.forEach { it.clear() }
    }
}
