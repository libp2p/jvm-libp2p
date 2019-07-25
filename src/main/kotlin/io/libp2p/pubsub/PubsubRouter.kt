package io.libp2p.pubsub

import io.libp2p.core.Stream
import pubsub.pb.Rpc
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer

interface PubsubMessageRouter {

    fun publish(msg: Rpc.Message): CompletableFuture<Void>

    fun setHandler(handler: Consumer<Rpc.Message>)

    fun subscribe(vararg topics: ByteArray)

    fun unsubscribe(vararg topics: ByteArray)
}

interface PubsubPeerRouter {

    fun peerConnected(peer: Stream)

    fun peerDisconnected(peer: Stream)
}

interface PubsubRouter: PubsubMessageRouter, PubsubPeerRouter