package io.libp2p.pubsub.gossip.builders

import com.google.common.util.concurrent.ThreadFactoryBuilder
import io.libp2p.core.pubsub.ValidationResult
import io.libp2p.etc.types.lazyVar
import io.libp2p.pubsub.*
import io.libp2p.pubsub.gossip.*
import java.time.Duration
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

typealias GossipRouterEventsSubscriber = (GossipRouterEventListener) -> Unit
typealias GossipScoreFactory =
    (GossipScoreParams, ScheduledExecutorService, CurrentTimeSupplier, GossipRouterEventsSubscriber) -> GossipScore

open class GossipRouterBuilder(

    var name: String = "GossipRouter",
    var protocol: PubsubProtocol = PubsubProtocol.Gossip_V_1_2,

    var params: GossipParams = GossipParams(),
    var scoreParams: GossipScoreParams = GossipScoreParams(),

    var scheduledAsyncExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(
        ThreadFactoryBuilder().setDaemon(true).setNameFormat("GossipRouter-event-thread-%d").build()
    ),
    var currentTimeSupplier: CurrentTimeSupplier = { System.currentTimeMillis() },
    var random: Random = Random(),

    var messageFactory: PubsubMessageFactory = { DefaultPubsubMessage(it) },
    var messageValidator: PubsubRouterMessageValidator = NOP_ROUTER_VALIDATOR,

    var subscriptionTopicSubscriptionFilter: TopicSubscriptionFilter = TopicSubscriptionFilter.AllowAllTopicSubscriptionFilter(),

    var scoreFactory: GossipScoreFactory =
        { scoreParams1, scheduledAsyncRxecutor, currentTimeSupplier1, eventsSubscriber ->
            val gossipScore = DefaultGossipScore(scoreParams1, scheduledAsyncRxecutor, currentTimeSupplier1)
            eventsSubscriber(gossipScore)
            gossipScore
        },
    val gossipRouterEventListeners: MutableList<GossipRouterEventListener> = mutableListOf(),
    val enabledGossipExtensions: List<GossipExtension> = mutableListOf(),
) {

    var outboundWriteProgressTimeout: Duration = DEFAULT_OUTBOUND_WRITE_PROGRESS_TIMEOUT
    var maxOutboundRetainedBytesPerPeer: Long? = null
    var maxOutboundRetainedEntriesPerPeer: Int =
        DEFAULT_MAX_OUTBOUND_RETAINED_ENTRIES_PER_PEER
    var seenCache: SeenCache<Optional<ValidationResult>> by lazyVar { TTLSeenCache(SimpleSeenCache(), params.seenTTL, currentTimeSupplier) }
    var mCache: MCache by lazyVar { MCache(params.gossipSize, params.gossipHistoryLength) }

    private var disposed = false

    fun enabledGossipExtensions(vararg gossipExtensions: GossipExtension): GossipRouterBuilder {
        (enabledGossipExtensions as MutableList).addAll(gossipExtensions)
        return this
    }

    protected open fun createGossipRouter(): GossipRouter {
        val gossipScore = scoreFactory(scoreParams, scheduledAsyncExecutor, currentTimeSupplier, { gossipRouterEventListeners += it })

        val router = GossipRouter(
            params = params,
            scoreParams = scoreParams,
            currentTimeSupplier = currentTimeSupplier,
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
            gossipExtensionsConfig = buildGossipExtensionsConfig(),
        )
        return router
    }

    open fun build(): GossipRouter {
        if (disposed) throw RuntimeException("The builder was already used")
        disposed = true
        val outboundLimits = resolveOutboundLimits()
        return createGossipRouter().also { router ->
            router.configureOutboundWriteProgressTimeout(outboundWriteProgressTimeout)
            router.configureOutboundLimits(outboundLimits)
            router.eventBroadcaster.listeners += gossipRouterEventListeners
        }
    }

    private fun resolveOutboundLimits(): PubsubOutboundLimits {
        val defaults = PubsubOutboundLimits.defaults(params.maxGossipMessageSize)
        return PubsubOutboundLimits(
            maxOutboundRetainedBytesPerPeer ?: defaults.maxRetainedBytesPerPeer,
            maxOutboundRetainedEntriesPerPeer
        ).validate(params.maxGossipMessageSize)
    }

    private fun buildGossipExtensionsConfig(): GossipExtensionsConfig {
        return GossipExtensionsConfig(
            partialMessagesEnabled = enabledGossipExtensions.contains(GossipExtension.PARTIAL_MESSAGES),
            testExtensionEnabled = enabledGossipExtensions.contains(GossipExtension.TEST_EXTENSION)
        )
    }
}
