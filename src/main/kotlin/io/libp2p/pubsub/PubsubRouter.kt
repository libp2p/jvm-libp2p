package io.libp2p.pubsub

import io.libp2p.core.PeerId
import io.libp2p.core.Stream
import io.libp2p.core.crypto.sha256
import io.libp2p.core.pubsub.ValidationResult
import io.libp2p.etc.types.WBytes
import io.libp2p.etc.types.toWBytes
import io.netty.channel.ChannelHandler
import pubsub.pb.Rpc
import java.util.Random
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledExecutorService

typealias Topic = String
typealias MessageId = WBytes
typealias PubsubMessageFactory = (Rpc.Message) -> PubsubMessage

data class PubsubSubscription(val topic: Topic, val subscribe: Boolean)

interface PubsubMessage {
    val protobufMessage: Rpc.Message
    val messageId: MessageId

    @JvmDefault
    val topics: List<Topic>
        get() = protobufMessage.topicIDsList

    override fun equals(other: Any?): Boolean

    /**
     * WARNING: Use collision free functions only
     * Else the HashMap collision attack vector is open
     */
    override fun hashCode(): Int
}

abstract class AbstractPubsubMessage : PubsubMessage {
    override fun equals(other: Any?) = protobufMessage == (other as? PubsubMessage)?.protobufMessage
    override fun hashCode() = sha256(protobufMessage.toByteArray()).toWBytes().hashCode()
    override fun toString() = "PubsubMessage{$protobufMessage}"
}

/**
 * Represents internal pubsub router component to interact with the client API
 * Might be though of as `low-level` [PubsubApi]
 *
 * All the implementation methods should be thread-safe
 */
interface PubsubMessageRouter {

    val protocol: PubsubProtocol
    var messageFactory: PubsubMessageFactory
    var messageValidator: PubsubRouterMessageValidator

    /**
     * Validates and broadcasts the message to suitable peers
     * @return a future which can be used to detect errors during send,
     * like e.g. absence of peers to publish or internal errors
     * The future completes normally when the message
     * is transmitted to at least one peer
     */
    fun publish(msg: PubsubMessage): CompletableFuture<Unit>

    /**
     * Initializes the inbound messages [handler]
     * The method must be called once
     * All the messages received by the router are forwarded to the [handler] independently
     * of any client subscriptions. Is it up to the client API to sort out subscriptions
     */
    fun initHandler(handler: (PubsubMessage) -> CompletableFuture<ValidationResult>)

    /**
     * Notifies the router that a client wants to receive messages on the following topics
     * Calling subscribe several times for a single topic have no cumulative effect and thus
     * would be canceled with a single [unsubscribe] call for that topic
     */
    fun subscribe(vararg topics: Topic)

    /**
     * Notifies the router that a client doesn't want
     * to receive messages on the following topics any more
     */
    fun unsubscribe(vararg topics: Topic)

    /**
     * Get the topics each peer is subscribed to
     *
     * @return a map of the peer's {@link PeerId} to the set of topics it is subscribed to
     */
    fun getPeerTopics(): CompletableFuture<Map<PeerId, Set<Topic>>>
}

/**
 * Represents a pubsub router API from the network side
 */
interface PubsubPeerRouter {

    /**
     * Adds a new [Stream] which was negotiated and agreed on any supported protocol
     * Withing method call the underlying Stream [io.netty.channel.Channel] should
     * be initialized *synchronously on the caller thread*
     */
    fun addPeer(peer: Stream)

    /**
     * Removes the stream added with [addPeer]
     * Normally the underlying [Stream] [io.netty.channel.Channel] is tracked
     * by the router on close event and the [Stream] is removed upon channel close
     * but there might be the case when the [Stream] needs to be removed explicitly
     */
    fun removePeer(peer: Stream)
}

/**
 * The main Router interface which just joins two Router aspects
 */
interface PubsubRouter : PubsubMessageRouter, PubsubPeerRouter

/**
 * The router may optionally implement this extension interface which is
 * helpful for testing and debugging
 */
interface PubsubRouterDebug : PubsubRouter {

    /**
     * Adds ability to substitute the scheduler which is used for all async and periodic
     * tasks within the router
     */
    var executor: ScheduledExecutorService

    /**
     * System time supplier. Normally defaults to [System.currentTimeMillis]
     * If router needs system time it should refer to this supplier
     */
    var curTimeMillis: () -> Long

    /**
     * Randomness supplier
     * Whenever router implementation needs random data it must refer to this var
     * Tests may substitute this instance with a fixed-seed [Random]
     * to perform deterministic testing
     */
    var random: Random

    var name: String

    /**
     * The same as [PubsubRouter.addPeer] but adds the [debugHandler] right before
     * the terminal handler
     * This is useful for example to log decoded pubsub wire messages
     */
    fun addPeerWithDebugHandler(peer: Stream, debugHandler: ChannelHandler? = null) = addPeer(peer)
}

/**
 * Validates pubsub messages
 */
fun interface PubsubRouterMessageValidator {

    /**
     * Validates a single publish. Basically this is just a signature validation
     * @throws InvalidMessageException when the message is not valid
     */
    fun validate(msg: PubsubMessage)
}

val NOP_ROUTER_VALIDATOR = PubsubRouterMessageValidator {}
val SIGNATURE_ROUTER_VALIDATOR = PubsubRouterMessageValidator {
    if (!pubsubValidate(it.protobufMessage)) {
        throw InvalidMessageException(it.toString())
    }
}
