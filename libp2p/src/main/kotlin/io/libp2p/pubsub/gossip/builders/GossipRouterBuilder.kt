package io.libp2p.pubsub.gossip.builders

import com.google.common.util.concurrent.ThreadFactoryBuilder
import io.libp2p.core.pubsub.ValidationResult
import io.libp2p.etc.types.lazyVar
import io.libp2p.pubsub.*
import io.libp2p.pubsub.gossip.*
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

typealias GossipRouterEventsSubscriber = (GossipRouterEventListener) -> Unit
typealias GossipScoreFactory =
    (GossipScoreParams, ScheduledExecutorService, CurrentTimeSupplier, GossipRouterEventsSubscriber) -> GossipScore

open class GossipRouterBuilder(

    var name: String = "GossipRouter",
    var protocol: PubsubProtocol = PubsubProtocol.Gossip_V_1_1,

    var params: GossipParams = GossipParams(),
    var scoreParams: GossipScoreParams = GossipScoreParams(),

    var scheduledAsyncExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(
        ThreadFactoryBuilder().setDaemon(true).setNameFormat("GossipRouter-event-thread-%d").build()
    ),
    var currentTimeSuppluer: CurrentTimeSupplier = { System.currentTimeMillis() },
    var random: Random = Random(),

    var messageFactory: PubsubMessageFactory = { DefaultPubsubMessage(it) },
    var messageValidator: PubsubRouterMessageValidator = NOP_ROUTER_VALIDATOR,

    var subscriptionTopicSubscriptionFilter: TopicSubscriptionFilter = TopicSubscriptionFilter.AllowAllTopicSubscriptionFilter(),

    var scoreFactory: GossipScoreFactory =
        { scoreParams1, scheduledAsyncRxecutor, currentTimeSuppluer1, eventsSubscriber ->
            val gossipScore = DefaultGossipScore(scoreParams1, scheduledAsyncRxecutor, currentTimeSuppluer1)
            eventsSubscriber(gossipScore)
            gossipScore
        },
    val gossipRouterEventListeners: MutableList<GossipRouterEventListener> = mutableListOf()
) {

    var seenCache: SeenCache<Optional<ValidationResult>> by lazyVar { TTLSeenCache(SimpleSeenCache(), params.seenTTL, currentTimeSuppluer) }
    var mCache: MCache by lazyVar { MCache(params.gossipSize, params.gossipHistoryLength) }

    private var disposed = false

    protected open fun createGossipRouter(): GossipRouter {
        val gossipScore = scoreFactory(scoreParams, scheduledAsyncExecutor, currentTimeSuppluer, { gossipRouterEventListeners += it })

        val router = GossipRouter(
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
            messageValidator = messageValidator
        )

        router.eventBroadcaster.listeners += gossipRouterEventListeners
        return router
    }

    open fun build(): GossipRouter {
        if (disposed) throw RuntimeException("The builder was already used")
        disposed = true
        return createGossipRouter()
    }
}
