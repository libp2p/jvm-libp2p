package io.libp2p.pubsub

import io.libp2p.core.PeerId
import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.pubsub.MessageApi
import io.libp2p.core.pubsub.PubsubApi
import io.libp2p.core.pubsub.PubsubPublisherApi
import io.libp2p.core.pubsub.PubsubSubscription
import io.libp2p.core.pubsub.Topic
import io.libp2p.core.pubsub.ValidationResult
import io.libp2p.core.pubsub.Validator
import io.libp2p.etc.types.thenApplyAll
import io.libp2p.etc.types.toByteArray
import io.libp2p.etc.types.toByteBuf
import io.libp2p.etc.types.toBytesBigEndian
import io.libp2p.etc.types.toLongBigEndian
import io.libp2p.etc.types.toProtobuf
import io.netty.buffer.ByteBuf
import pubsub.pb.Rpc
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicLong

open class PubsubApiImpl(val router: PubsubRouter) : PubsubApi {

    inner class SubscriptionImpl(val topics: Array<out Topic>, val receiver: Validator) :
        PubsubSubscription {
        var unsubscribed = false
        override fun unsubscribe() {
            if (unsubscribed) throw PubsubException("Already unsubscribed")
            unsubscribed = true
            unsubscribeImpl(this)
        }
    }

    protected open inner class PublisherImpl(val privKey: PrivKey, seqId: Long) : PubsubPublisherApi {
        val from = PeerId.fromPubKey(privKey.publicKey()).bytes.toProtobuf()
        val seqCounter = AtomicLong(seqId)

        override fun publish(data: ByteBuf, vararg topics: Topic): CompletableFuture<Unit> {
            val msgToSign = createMessageToSign(data, *topics)
            val signedMsg = pubsubSign(msgToSign, privKey)
            return router.publish(signedMsg)
        }

        protected open fun createMessageToSign(data: ByteBuf, vararg topics: Topic): Rpc.Message =
            Rpc.Message.newBuilder()
                .setFrom(from)
                .addAllTopicIDs(topics.map { it.topic })
                .setData(data.toByteArray().toProtobuf())
                .setSeqno(seqCounter.incrementAndGet().toBytesBigEndian().toProtobuf())
                .build()
    }

    init {
        router.initHandler { onNewMessage(it) }
    }

    val subscriptions: MutableMap<Topic, MutableList<SubscriptionImpl>> = mutableMapOf()
    private val validationResultReduce = { r1: ValidationResult, r2: ValidationResult ->
        when {
            r1 == ValidationResult.Invalid || r2 == ValidationResult.Invalid -> ValidationResult.Invalid
            r1 == ValidationResult.Ignore || r2 == ValidationResult.Ignore -> ValidationResult.Ignore
            else -> ValidationResult.Valid
        }
    }

    private fun onNewMessage(msg: Rpc.Message): CompletableFuture<ValidationResult> {
        val validationFuts = synchronized(this) {
            msg.topicIDsList.mapNotNull { subscriptions[Topic(it)] }.flatten().distinct()
        }.map {
            it.receiver.apply(rpc2Msg(msg))
        }
        return validationFuts.thenApplyAll {
            if (it.isEmpty())ValidationResult.Ignore
            else it.reduce(validationResultReduce)
        }
    }

    private fun rpc2Msg(msg: Rpc.Message): MessageApi {
        return MessageImpl(
            msg.data.toByteArray().toByteBuf(),
            msg.from.toByteArray(),
            msg.seqno.toByteArray().copyOfRange(0, 8).toLongBigEndian(),
            msg.topicIDsList.map { Topic(it) }
        )
    }

    override fun subscribe(receiver: Validator, vararg topics: Topic): PubsubSubscription {
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

    override fun getPeerTopics(): CompletableFuture<Map<PeerId, Set<Topic>>> {
        return router.getPeerTopics().thenApply { peerTopics ->
            peerTopics.mapValues { topicNames ->
                topicNames.value.mapTo(HashSet()) { topicName -> Topic(topicName) } as Set<Topic>
            }
        }
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