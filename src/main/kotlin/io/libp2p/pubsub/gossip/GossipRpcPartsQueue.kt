package io.libp2p.pubsub.gossip

import io.libp2p.core.PeerId
import io.libp2p.etc.types.toProtobuf
import io.libp2p.pubsub.DefaultRpcPartsQueue
import io.libp2p.pubsub.MessageId
import io.libp2p.pubsub.RpcPartsQueue
import io.libp2p.pubsub.Topic
import pubsub.pb.Rpc

interface GossipRpcPartsQueue : RpcPartsQueue {

    fun addIHave(messageId: MessageId)
    fun addIHaves(messageIds: Collection<MessageId>) = messageIds.forEach { addIHave(it) }
    fun addIWant(messageId: MessageId)
    fun addIWants(messageIds: Collection<MessageId>) = messageIds.forEach { addIWant(it) }

    fun addGraft(topic: Topic)

    /**
     * Gossip 1.0 variant
     */
    fun addPrune(topic: Topic)
    /**
     * Gossip 1.1 variant
     */
    fun addPrune(topic: Topic, backoffSeconds: Long, backoffPeers: List<PeerId>)
}

/**
 * Default [RpcPartsQueue] implementation
 *
 * NOT thread safe
 */
open class DefaultGossipRpcPartsQueue(
    private val params: GossipParams
) : DefaultRpcPartsQueue(), GossipRpcPartsQueue {

    protected data class IHavePart(val messageId: MessageId) : AbstractPart {
        override fun appendToBuilder(builder: Rpc.RPC.Builder) {
            val ctrlBuilder = builder.controlBuilder
            val iHaveBuilder = if (ctrlBuilder.ihaveBuilderList.isEmpty()) {
                ctrlBuilder.addIhaveBuilder()
            } else {
                ctrlBuilder.getIhaveBuilder(0)
            }
            iHaveBuilder.addMessageIDs(messageId.toProtobuf())
        }
    }

    protected data class IWantPart(val messageId: MessageId) : AbstractPart {
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

    protected data class GraftPart(val topic: Topic) : AbstractPart {
        override fun appendToBuilder(builder: Rpc.RPC.Builder) {
            builder.controlBuilder.addGraftBuilder().setTopicID(topic)
        }
    }

    protected data class PrunePart(val topic: Topic, val backoffSeconds: Long?, val backoffPeers: List<PeerId>) : AbstractPart {
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

    override fun addIHave(messageId: MessageId) {
        addPart(IHavePart(messageId))
    }

    override fun addIWant(messageId: MessageId) {
        addPart(IWantPart(messageId))
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

    override fun takeMerged(): List<Rpc.RPC> {
        val ret = mutableListOf<Rpc.RPC>()
        var partIdx = 0
        while (partIdx < parts.size) {
            val builder = Rpc.RPC.newBuilder()

            var publishCount = params.maxPublishedMessages ?: Int.MAX_VALUE
            var subscriptionCount = params.maxSubscriptions ?: Int.MAX_VALUE
            var iHaveCount = params.maxIHaveLength
            var iWantCount = params.maxIWantMessageIds ?: Int.MAX_VALUE
            var graftCount = params.maxGraftMessages ?: Int.MAX_VALUE
            var pruneCount = params.maxPruneMessages ?: Int.MAX_VALUE

            while (partIdx < parts.size &&
                publishCount > 0 && subscriptionCount > 0 && iHaveCount > 0 &&
                iWantCount > 0 && graftCount > 0 && pruneCount > 0
            ) {

                val part = parts[partIdx++]
                when (part) {
                    is PublishPart -> publishCount--
                    is SubscriptionPart -> subscriptionCount--
                    is IHavePart -> iHaveCount--
                    is IWantPart -> iWantCount--
                    is GraftPart -> graftCount--
                    is PrunePart -> pruneCount--
                }

                part.appendToBuilder(builder)
            }
            ret += builder.build()
        }

        parts.clear()
        return ret
    }
}
