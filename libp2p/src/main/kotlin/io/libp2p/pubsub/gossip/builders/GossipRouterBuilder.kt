package io.libp2p.pubsub.gossip.builders

import com.google.common.util.concurrent.ThreadFactoryBuilder
import io.libp2p.core.pubsub.ValidationResult
import io.libp2p.etc.types.lazyVar
import io.libp2p.pubsub.*
import io.libp2p.pubsub.gossip.*
import io.libp2p.pubsub.gossip.partialmessages.*
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

    /**
     * Client-supplied handler for the partial-messages extension.
     * Required when [GossipExtension.PARTIAL_MESSAGES] is enabled; a build-time
     * error is thrown if the extension is enabled without a handler.
     */
    var partialMessagesHandler: PartialMessagesHandler<*>? = null,
) {

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

        router.eventBroadcaster.listeners += gossipRouterEventListeners
        router.partialMessages = buildPartialMessagesAdapter()
        return router
    }

    @Suppress("UNCHECKED_CAST")
    private fun buildPartialMessagesAdapter(): PartialMessagesAdapter? {
        val handler = partialMessagesHandler ?: return null
        return PartialMessagesAdapterImpl(
            handler = handler as PartialMessagesHandler<Any?>,
            stateStore = PartialGroupStateStore(),
            feedback = NopPartialMessagesFeedback,
        )
    }

    open fun build(): GossipRouter {
        if (disposed) throw RuntimeException("The builder was already used")
        disposed = true
        if (enabledGossipExtensions.contains(GossipExtension.PARTIAL_MESSAGES) && partialMessagesHandler == null) {
            throw IllegalStateException(
                "GossipExtension.PARTIAL_MESSAGES is enabled but no partialMessagesHandler was provided"
            )
        }
        return createGossipRouter()
    }

    private fun buildGossipExtensionsConfig(): GossipExtensionsConfig {
        return GossipExtensionsConfig(
            partialMessagesEnabled = enabledGossipExtensions.contains(GossipExtension.PARTIAL_MESSAGES),
            testExtensionEnabled = enabledGossipExtensions.contains(GossipExtension.TEST_EXTENSION)
        )
    }
}
