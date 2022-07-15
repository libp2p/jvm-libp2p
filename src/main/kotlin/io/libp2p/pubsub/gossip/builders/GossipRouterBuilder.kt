package io.libp2p.pubsub.gossip.builders

import com.google.common.util.concurrent.ThreadFactoryBuilder
import io.libp2p.core.pubsub.ValidationResult
import io.libp2p.etc.types.lazyVar
import io.libp2p.etc.types.lazyVarInit
import io.libp2p.pubsub.*
import io.libp2p.pubsub.gossip.*
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

typealias GossipRouterEventsSubscriber = (GossipRouterEventListener) -> Unit
typealias GossipScoreFactory =
    (GossipScoreParams, ScheduledExecutorService, CurrentTimeSupplier, GossipRouterEventsSubscriber) -> GossipScore

open class GossipRouterBuilder {

    var name: String = "GossipRouter"
    var protocol: PubsubProtocol = PubsubProtocol.Gossip_V_1_1

    var scheduledAsyncExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(
        ThreadFactoryBuilder().setDaemon(true).setNameFormat("GossipRouter-event-thread-%d").build()
    )

    var currentTimeSuppluer: CurrentTimeSupplier by lazyVarInit { { System.currentTimeMillis() } }
    var random by lazyVarInit { Random() }

    var params: GossipParams = GossipParams()
    var scoreParams: GossipScoreParams = GossipScoreParams()

    var maxMsgSize: Int = DEFAULT_MAX_PUBSUB_MESSAGE_SIZE
    var messageFactory: PubsubMessageFactory = { DefaultPubsubMessage(it) }
    var messageValidator: PubsubRouterMessageValidator = NOP_ROUTER_VALIDATOR
    var maxSeenMessagesLimit = 10000
    var seenCache: SeenCache<Optional<ValidationResult>> by lazyVar {
        LRUSeenCache(SimpleSeenCache(), maxSeenMessagesLimit)
    }
    val mCache: MCache = MCache(params.gossipSize, params.gossipHistoryLength)

    var subscriptionTopicSubscriptionFilter: TopicSubscriptionFilter = TopicSubscriptionFilter.AllowAllTopicSubscriptionFilter()

    var scoreFactory: GossipScoreFactory =
        { scoreParams, scheduledAsyncRxecutor, currentTimeSuppluer, eventsSubscriber ->
            val gossipScore = DefaultGossipScore(scoreParams, scheduledAsyncRxecutor, currentTimeSuppluer)
            eventsSubscriber(gossipScore)
            gossipScore
        }
    val gossipRouterEventListeners = mutableListOf<GossipRouterEventListener>()

    protected fun createGossipRouter(): GossipRouter {
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
                maxMsgSize = maxMsgSize,
                messageFactory = messageFactory,
                seenMessages = seenCache,
                messageValidator = messageValidator
        )

        router.eventBroadcaster.listeners += gossipRouterEventListeners
        return router
    }

    open fun build(): GossipRouter = createGossipRouter()


    // TODO: does it make any sense ?
    open fun build(messageHandlerInitializer: GossipRouter.() -> PubsubMessageHandler): GossipRouter {
        val gossipRouter = createGossipRouter()
        val messageHandler = messageHandlerInitializer(gossipRouter)
        gossipRouter.initHandler(messageHandler)
        return gossipRouter
    }
}
