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

    protected open inner class PublisherImpl(val privKey: PrivKey?, val seqIdGenerator: () -> Long?) :
        PubsubPublisherApi {

        val from = privKey?.let { PeerId.fromPubKey(it.publicKey()).bytes.toProtobuf() }

        override fun publishExt(
            data: ByteBuf,
            from: ByteArray?,
            seqId: Long?,
            vararg topics: Topic
        ): CompletableFuture<Unit> {
            val mFrom = from?.toProtobuf() ?: this.from
            val mSeqId = seqId ?: seqIdGenerator()

            val msgToSign = Rpc.Message.newBuilder()
                .addAllTopicIDs(topics.map { it.topic })
                .setData(data.toByteArray().toProtobuf())
            mSeqId?.also {
                msgToSign.setSeqno(it.toBytesBigEndian().toProtobuf())
            }
            mFrom?.also {
                msgToSign.setFrom(it)
            }

            return router.publish(router.messageFactory(sign(msgToSign.build())))
        }

        private fun sign(msg: Rpc.Message) = if (privKey != null) pubsubSign(msg, privKey) else msg
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

    private fun onNewMessage(msg: PubsubMessage): CompletableFuture<ValidationResult> {
        val validationFuts = synchronized(this) {
            msg.topics.mapNotNull { subscriptions[Topic(it)] }.flatten().distinct()
        }.map {
            it.receiver.apply(rpc2Msg(msg))
        }
        return validationFuts.thenApplyAll {
            if (it.isEmpty()) ValidationResult.Ignore
            else it.reduce(validationResultReduce)
        }
    }

    private fun rpc2Msg(msg: PubsubMessage) = MessageImpl(msg)

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
                topicNames.value.mapTo(HashSet()) { topicName -> Topic(topicName) }
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

    override fun createPublisher(privKey: PrivKey?, seqIdGenerator: () -> Long?): PubsubPublisherApi =
        PublisherImpl(privKey, seqIdGenerator)
}

class MessageImpl(override val originalMessage: PubsubMessage) : MessageApi {
    private val msg = originalMessage.protobufMessage
    override val data = msg.data.toByteArray().toByteBuf()
    override val from = if (msg.hasFrom()) msg.from.toByteArray() else null
    override val seqId = if (msg.hasSeqno() && msg.seqno.size() >= 8)
        msg.seqno.toByteArray().copyOfRange(0, 8).toLongBigEndian()
    else null
    override val topics = msg.topicIDsList.map { Topic(it) }
}
