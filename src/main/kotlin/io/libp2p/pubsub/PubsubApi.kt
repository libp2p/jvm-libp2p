package io.libp2p.pubsub

import io.libp2p.core.crypto.PrivKey
import io.netty.buffer.ByteBuf
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer
import kotlin.random.Random.Default.nextLong

fun createPubsubApi(router: PubsubRouter): PubsubApi = PubsubApiImpl(router)

/**
 * API interface for Pubsub subscriber
 */
interface PubsubSubscriberApi {

    /**
     * Subscribes the [receiver] callback to one or more topics
     * The callback is invoked once per message if the message contains one of
     * the specified topics. Own published messages are not replayed
     *
     * The whole subscription is cancelled via returned [PubsubSubscription] instance
     *
     * The [receiver] callback is invoked on the [PubsubRouter] event thread
     * thus it is not recommended to run any time consuming task withing callback
     */
    fun subscribe(receiver: Consumer<MessageApi>, vararg topics: Topic): PubsubSubscription
}

/**
 * Represents a single subscription
 */
interface PubsubSubscription {

    /**
     * Cancels subscription
     * @see PubsubSubscriberApi.subscribe
     */
    fun unsubscribe()
}

/**
 * Represents a publisher API for a single sender. The implementation should
 * keep the senders private key for signing messages and keep track of
 * the sender's `seqId`
 * @see PubsubApi.createPublisher
 */
interface PubsubPublisherApi {

    /**
     * Publishes a message with [data] body and specified [topics]
     * @return a future which can be used to detect errors during send,
     * like e.g. absence of peers to publish or internal errors
     * The future completes normally when the message
     * is transmitted to at least one peer
     */
    fun publish(data: ByteBuf, vararg topics: Topic): CompletableFuture<Unit>
}

/**
 * The main Pubsub API for subscribing and publishing messages
 */
interface PubsubApi : PubsubSubscriberApi {

    /**
     * Creates a Publisher instance for a single sender identified by [privKey]
     * @param privKey The sender's private key for singing published messages
     * @param seqId Initial sequence id for the sender. Since messages are
     * uniquely identified by a pair of `sender + seqId` it is recommended to
     * initialize the id with the `lastUsedId + 1`
     * Initialized with random value by default
     */
    fun createPublisher(privKey: PrivKey, seqId: Long = nextLong()): PubsubPublisherApi
}

/**
 * Abstract Pubsub Message API
 */
interface MessageApi {
    /**
     * Message body
     */
    val data: ByteBuf
    /**
     * Sender identity. Usually it a [PeerId] derived from the sender's public key
     */
    val from: ByteArray
    /**
     * Sequence id for the sender. A pair [from]` + `[seqId] should be globally unique
     */
    val seqId: Long
    /**
     * A set of message topics
     */
    val topics: List<Topic>
}

/**
 * Abstract topic representation
 */
data class Topic(val topic: String)
