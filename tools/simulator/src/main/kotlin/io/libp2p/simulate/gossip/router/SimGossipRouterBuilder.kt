package io.libp2p.simulate.gossip.router

import io.libp2p.core.crypto.sha256
import io.libp2p.etc.types.toWBytes
import io.libp2p.pubsub.AbstractPubsubMessage
import io.libp2p.pubsub.MessageId
import io.libp2p.pubsub.gossip.GossipRouter
import io.libp2p.pubsub.gossip.builders.GossipRouterBuilder
import pubsub.pb.Rpc
import kotlin.time.Duration

class SimGossipRouterBuilder : GossipRouterBuilder() {
    var serializeMessagesToBytes: Boolean = false
    var additionalHeartbeatDelay: Duration = Duration.ZERO

    init {
        messageFactory = { SimPubsubMessage(it) }
    }

    override fun createGossipRouter(): GossipRouter {
        val gossipScore =
            scoreFactory(scoreParams, scheduledAsyncExecutor, currentTimeSuppluer) { gossipRouterEventListeners += it }

        val router = SimGossipRouter(
            params = params,
            scoreParams = scoreParams,
            currentTimeSupplier = currentTimeSuppluer,
            random = random,
            name = name,
            mCache = mCache,
            score = gossipScore,
            subscriptionTopicSubscriptionFilter = subscriptionTopicSubscriptionFilter,
            protocol = protocol,
            executor = scheduledAsyncExecutor,
            messageFactory = messageFactory,
            seenMessages = seenCache,
            messageValidator = messageValidator,
            serializeToBytes = serializeMessagesToBytes,
            additionalHeartbeatDelay = additionalHeartbeatDelay
        )

        router.eventBroadcaster.listeners += gossipRouterEventListeners
        return router
    }

    class SimPubsubMessage(override val protobufMessage: Rpc.Message) : AbstractPubsubMessage() {
        override val messageId: MessageId =
            sha256(protobufMessage.data.toByteArray()).sliceArray(0..7).toWBytes()
    }

}
