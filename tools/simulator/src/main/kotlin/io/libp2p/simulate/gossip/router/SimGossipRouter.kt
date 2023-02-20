package io.libp2p.simulate.gossip.router

import io.libp2p.core.pubsub.ValidationResult
import io.libp2p.pubsub.*
import io.libp2p.pubsub.gossip.*
import io.netty.channel.ChannelHandler
import java.time.Duration
import java.util.*
import java.util.concurrent.ScheduledExecutorService

class SimGossipRouter(
    params: GossipParams,
    scoreParams: GossipScoreParams,
    currentTimeSupplier: CurrentTimeSupplier,
    random: Random,
    name: String,
    mCache: MCache,
    score: GossipScore,
    subscriptionTopicSubscriptionFilter: TopicSubscriptionFilter,
    protocol: PubsubProtocol,
    executor: ScheduledExecutorService,
    messageFactory: PubsubMessageFactory,
    seenMessages: SeenCache<Optional<ValidationResult>>,
    messageValidator: PubsubRouterMessageValidator,
    val serializeToBytes: Boolean,
    additionalHeartbeatDelay: Duration
) : GossipRouter(
    params,
    scoreParams,
    currentTimeSupplier,
    random,
    name,
    mCache,
    score,
    subscriptionTopicSubscriptionFilter,
    protocol,
    executor,
    messageFactory,
    seenMessages,
    messageValidator
) {

    override val heartbeatInitialDelay: Duration = params.heartbeatInterval + additionalHeartbeatDelay

    override fun initChannelWithHandler(streamHandler: StreamHandler, handler: ChannelHandler?) {
        if (serializeToBytes) {
            super.initChannelWithHandler(streamHandler, handler)
        } else {
            // exchange Rpc.RPC messages directly (without serialization) for performance reasons
            with(streamHandler.stream) {
                handler?.also { pushHandler(it) }
                pushHandler(streamHandler)
            }
        }
    }
}