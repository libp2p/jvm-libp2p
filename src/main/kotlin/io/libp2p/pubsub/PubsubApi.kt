package io.libp2p.pubsub

import io.libp2p.core.crypto.PrivKey
import io.netty.buffer.ByteBuf
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer
import kotlin.random.Random.Default.nextLong

fun createPubsubApi(router: PubsubRouter) : PubsubApi = PubsubApiImpl(router)

interface PubsubSubscriberApi {

    fun subscribe(receiver: Consumer<MessageApi>, vararg topics: Topic): PubsubSubscription
}

interface PubsubSubscription {
    fun unsubscribe()
}

interface PubsubPublisherApi {

    fun publish(data: ByteBuf, vararg topics: Topic): CompletableFuture<Unit>
}

interface PubsubApi : PubsubSubscriberApi {

    fun createPublisher(privKey: PrivKey, seqId: Long = nextLong()): PubsubPublisherApi
}

interface MessageApi {
    val data: ByteBuf
    val from: ByteArray
    val seqId: Long
    val topics: List<Topic>
}

data class Topic(val topic: String)
