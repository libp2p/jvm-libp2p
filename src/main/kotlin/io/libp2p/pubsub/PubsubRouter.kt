package io.libp2p.pubsub

import io.libp2p.core.Stream
import pubsub.pb.Rpc
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer

interface PubsubMessageRouter {

    fun publish(msg: Rpc.Message): CompletableFuture<Unit>

    fun setHandler(handler: Consumer<Rpc.Message>)

    fun subscribe(vararg topics: ByteArray)

    fun unsubscribe(vararg topics: ByteArray)
}

interface PubsubPeerRouter {

    fun addPeer(peer: Stream)

    fun removePeer(peer: Stream)
}

interface PubsubRouter : PubsubMessageRouter, PubsubPeerRouter