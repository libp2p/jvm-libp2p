package io.libp2p.pubsub.gossip.builders

import com.google.common.util.concurrent.ThreadFactoryBuilder
import io.libp2p.core.PeerId
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
     *
     * Prefer [withPartialMessagesHandler] over setting this directly — the typed method
     * captures the [PeerState] class token for runtime type validation of
     * [io.libp2p.pubsub.gossip.Gossip.publishPartial] calls.
     */
    var partialMessagesHandler: PartialMessagesHandler<*>? = null,
) {

    @PublishedApi
    internal var partialMessagesPeerStateClass: Class<*>? = null

    /**
     * Sets the partial-messages handler and captures [PeerState] as a runtime class token.
     * The captured class is used to validate [io.libp2p.pubsub.gossip.Gossip.publishPartial] /
     * [io.libp2p.pubsub.gossip.GossipRouter.publishPartial] calls at runtime, preventing
     * silent type corruption from mismatched [PublishActionsFn] types.
     *
     * Prefer this method over directly setting [partialMessagesHandler] when the [PeerState]
     * type is known at the call site.
     */
    inline fun <reified PeerState> withPartialMessagesHandler(handler: PartialMessagesHandler<PeerState>) {
        partialMessagesHandler = handler
        partialMessagesPeerStateClass = PeerState::class.java
    }

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
        return router
    }

    @Suppress("UNCHECKED_CAST")
    private fun buildPartialMessagesAdapter(router: GossipRouter): PartialMessagesAdapter? {
        val handler = partialMessagesHandler ?: return null
        return PartialMessagesAdapterImpl(
            handler = handler as PartialMessagesHandler<Any?>,
            stateStore = PartialGroupStateStore(),
            feedback = RouterBackedPartialMessagesFeedback(router),
            peerStateClass = partialMessagesPeerStateClass,
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
        val router = createGossipRouter()
        router.partialMessages = buildPartialMessagesAdapter(router)
        return router
    }

    protected fun buildGossipExtensionsConfig(): GossipExtensionsConfig {
        return GossipExtensionsConfig(
            partialMessagesEnabled = enabledGossipExtensions.contains(GossipExtension.PARTIAL_MESSAGES),
            testExtensionEnabled = enabledGossipExtensions.contains(GossipExtension.TEST_EXTENSION)
        )
    }
}

internal class RouterBackedPartialMessagesFeedback(
    private val router: GossipRouter
) : PartialMessagesPeerFeedback {
    override fun reportFeedback(topic: Topic, peer: PeerId, kind: FeedbackKind) {
        if (kind == FeedbackKind.INVALID) {
            router.runOnEventThread {
                router.eventBroadcaster.notifyRouterMisbehavior(peer, 1)
            }
        }
    }
}
