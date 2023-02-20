package io.libp2p.simulate.gossip

import io.libp2p.core.PeerId
import io.libp2p.core.Stream
import io.libp2p.core.pubsub.MessageApi
import io.libp2p.core.pubsub.PubsubSubscription
import io.libp2p.core.pubsub.RESULT_VALID
import io.libp2p.core.pubsub.Topic
import io.libp2p.core.pubsub.ValidationResult
import io.libp2p.core.pubsub.Validator
import io.libp2p.core.pubsub.createPubsubApi
import io.libp2p.etc.types.lazyVar
import io.libp2p.pubsub.PubsubProtocol
import io.libp2p.pubsub.gossip.builders.GossipRouterBuilder
import io.libp2p.simulate.gossip.router.SimGossipRouterBuilder
import io.libp2p.simulate.stream.StreamSimPeer
import io.libp2p.simulate.util.millis
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import java.util.Random
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class GossipSimPeer(
    val topics: List<Topic>,
    override val name: String,
    override val random: Random,
    protocol: PubsubProtocol = PubsubProtocol.Gossip_V_1_1
) : StreamSimPeer<Unit>(true, protocol.announceStr) {

    var routerBuilder = SimGossipRouterBuilder()
    var router by lazyVar {
        routerBuilder.also {
            it.name = name
            it.scheduledAsyncExecutor = simExecutor
            it.currentTimeSuppluer = { currentTime() }
            it.random = random
        }.build()
    }

    val api by lazy { createPubsubApi(router) }
    val apiPublisher by lazy { api.createPublisher(keyPair.first, 0L) }
    var pubsubLogs: (PeerId) -> Boolean = { false }

    var validationDelay = 0.millis
    var validationResult = RESULT_VALID
    val subscriptions = mutableListOf<PubsubSubscription>()

    val inboundMessages = mutableListOf<Pair<MessageApi, Long>>()

    val lastMsg: MessageApi?
        get() = inboundMessages.lastOrNull()?.first
    val lastMsgTime
        get() = inboundMessages.last().second

    fun onNewMsg(msg: MessageApi) {
        inboundMessages += msg to router.currentTimeSupplier()
    }

    override fun start(): CompletableFuture<Unit> {
        val subs = topics.map { topic ->
            api.subscribe(
                Validator {
                    onNewMsg(it)
                    if (validationDelay.toMillis() == 0L) {
                        validationResult
                    } else {
                        val ret = CompletableFuture<ValidationResult>()
                        simExecutor.schedule({ ret.complete(validationResult.get()) }, validationDelay.toMillis(), TimeUnit.MILLISECONDS)
                        ret
                    }
                },
                topic
            )
        }
        subscriptions += subs
        return super.start()
    }

    override fun toString(): String {
        return name
    }

    override fun handleStream(stream: Stream): CompletableFuture<Unit> {
        stream.getProtocol()
        val logConnection = pubsubLogs(stream.remotePeerId())
        router.addPeerWithDebugHandler(
            stream,
            if (logConnection)
                LoggingHandler(name, LogLevel.ERROR) else null
        )
        return dummy
    }

    companion object {
        private val dummy = CompletableFuture.completedFuture(Unit)
    }
}
