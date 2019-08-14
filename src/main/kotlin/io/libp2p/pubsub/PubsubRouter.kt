package io.libp2p.pubsub

import io.libp2p.core.Stream
import io.netty.channel.ChannelHandler
import pubsub.pb.Rpc
import java.util.Random
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledExecutorService

interface PubsubMessageRouter {

    fun publish(msg: Rpc.Message): CompletableFuture<Unit>

    fun setHandler(handler: (Rpc.Message) -> Unit)

    fun subscribe(vararg topics: String)

    fun unsubscribe(vararg topics: String)
}

interface PubsubPeerRouter {

    fun addPeer(peer: Stream)

    fun removePeer(peer: Stream)
}

interface PubsubRouter : PubsubMessageRouter, PubsubPeerRouter

interface PubsubRouterDebug : PubsubRouter {

    var executor: ScheduledExecutorService

    var curTime: () -> Long

    var random: Random

    fun addPeerWithDebugHandler(peer: Stream, debugHandler: ChannelHandler? = null) = addPeer(peer)
}