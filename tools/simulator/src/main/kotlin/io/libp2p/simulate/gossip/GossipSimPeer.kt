package io.libp2p.simulate.gossip

import io.libp2p.core.PeerId
import io.libp2p.core.Stream
import io.libp2p.core.pubsub.createPubsubApi
import io.libp2p.etc.types.lazyVar
import io.libp2p.pubsub.PubsubProtocol
import io.libp2p.simulate.gossip.router.SimGossipRouterBuilder
import io.libp2p.simulate.stream.StreamSimPeer
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import java.util.*
import java.util.concurrent.CompletableFuture

class GossipSimPeer(
    override val simPeerId: Int,
    override val random: Random,
    protocol: PubsubProtocol
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
