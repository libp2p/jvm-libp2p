package io.libp2p.pubsub.flood

import io.libp2p.core.Stream
import io.libp2p.pubsub.MessageAlreadySeenException
import io.libp2p.pubsub.PubsubMessageValidator
import io.libp2p.pubsub.PubsubRouter
import pubsub.pb.Rpc
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Consumer

class FloodRouter: PubsubRouter {

    private var msgHandler: Consumer<Rpc.Message> = Consumer { }
    var validator: PubsubMessageValidator = object: PubsubMessageValidator {}
    val peers = CopyOnWriteArrayList<Stream>()
    val seenMessages = mutableSetOf<Rpc.Message>() // TODO

    override fun publish(msg: Rpc.Message): CompletableFuture<Void> {
        validator.validate(msg)  // check ourselves not to be a bad peer
        return broadcastIfUnseen(msg, null)
    }

    override fun peerConnected(peer: Stream) {
        peers += peer
        // TODO setup handler
    }

    override fun peerDisconnected(peer: Stream) {
        peers -= peer
    }

    private fun broadcastIfUnseen(msg: Rpc.Message, receivedFrom: Stream?): CompletableFuture<Void> {
        if (seenMessages.add(msg)) {
            return CompletableFuture.anyOf(*
                peers.filter { it != receivedFrom }
                    .map { send(it, msg) }.toTypedArray()
                ).thenApply { }
        } else {
           return CompletableFuture<Void>().also { it.completeExceptionally(MessageAlreadySeenException("Msg: $msg")) }
        }
    }

    private fun onInbound(peer: Stream, msg: Rpc.Message) {
        validator.validate(msg)
        broadcastIfUnseen(msg, peer)
    }

    private fun send(peer: Stream, msg: Rpc.Message): CompletableFuture<Void> {
        TODO()
    }

    override fun setHandler(handler: Consumer<Rpc.Message>) {
        msgHandler = handler
    }

    override fun subscribe(vararg topics: ByteArray) {
        // NOP
    }

    override fun unsubscribe(vararg topics: ByteArray) {
        // NOP
    }
}