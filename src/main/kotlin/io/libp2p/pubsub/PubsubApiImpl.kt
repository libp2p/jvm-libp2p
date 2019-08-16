package io.libp2p.pubsub

import io.libp2p.core.PeerId
import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.types.toByteArray
import io.libp2p.core.types.toByteBuf
import io.libp2p.core.types.toBytesBigEndian
import io.libp2p.core.types.toLongBigEndian
import io.libp2p.core.types.toProtobuf
import io.netty.buffer.ByteBuf
import pubsub.pb.Rpc
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Consumer

class PubsubApiImpl(val router: PubsubRouter) : PubsubApi {

    inner class SubscriptionImpl(val topics: Array<out Topic>, val receiver: Consumer<MessageApi>) : PubsubSubscription {
        var unsubscribed = false
        override fun unsubscribe() {
            if (unsubscribed) throw PubsubException("Already unsubscribed")
            unsubscribed = true
            unsubscribeImpl(this)
        }
    }

    inner class PublisherImpl(val privKey: PrivKey, seqId: Long) : PubsubPublisherApi {
        val from = PeerId.fromPubKey(privKey.publicKey()).b.toProtobuf()
        val seqCounter = AtomicLong(seqId)
        override fun publish(data: ByteBuf, vararg topics: Topic): CompletableFuture<Unit> {
            val msgToSign = Rpc.Message.newBuilder()
                .setFrom(from)
                .addAllTopicIDs(topics.map { it.topic })
                .setData(data.toByteArray().toProtobuf())
                .setSeqno(seqCounter.incrementAndGet().toBytesBigEndian().toProtobuf())
                .build()
            val signedMsg = pubsubSign(msgToSign, privKey)
            return router.publish(signedMsg)
        }
    }

    init {
        router.initHandler { onNewMessage(it) }
    }

    val subscriptions: MutableMap<Topic, MutableList<SubscriptionImpl>> = mutableMapOf()

    private fun onNewMessage(msg: Rpc.Message) {
        synchronized(this) {
            msg.topicIDsList.mapNotNull { subscriptions[Topic(it)] }.flatten().distinct()
        }.forEach {
            it.receiver.accept(rpc2Msg(msg))
        }
    }

    private fun rpc2Msg(msg: Rpc.Message): MessageApi {
        return MessageImpl(
            msg.data.toByteArray().toByteBuf(),
            msg.from.toByteArray(),
            msg.seqno.toByteArray().toLongBigEndian(),
            msg.topicIDsList.map { Topic(it) }
        )
    }

    override fun subscribe(receiver: Consumer<MessageApi>, vararg topics: Topic): PubsubSubscription {
        val subscription = SubscriptionImpl(topics, receiver)
        val routerToSubscribe = mutableListOf<String>()

        synchronized(this) {
            for (topic in topics) {
                val list = subscriptions.getOrPut(topic, { mutableListOf() })
                if (list.isEmpty()) {
                    routerToSubscribe += topic.topic
                }
                list += subscription
            }
        }

        router.subscribe(*routerToSubscribe.toTypedArray())

        return subscription
    }

    private fun unsubscribeImpl(sub: SubscriptionImpl) {
        val routerToUnsubscribe = mutableListOf<String>()

        synchronized(this) {
            for (topic in sub.topics) {
                val list = subscriptions[topic] ?: throw IllegalStateException()
                if (!list.remove(sub)) throw IllegalStateException()
                if (list.isEmpty()) {
                    routerToUnsubscribe += topic.topic
                }
            }
        }

        router.unsubscribe(*routerToUnsubscribe.toTypedArray())
    }

    override fun createPublisher(privKey: PrivKey, seqId: Long): PubsubPublisherApi = PublisherImpl(privKey, seqId)
}

class MessageImpl(
    override val data: ByteBuf,
    override val from: ByteArray,
    override val seqId: Long,
    override val topics: List<Topic>
) : MessageApi