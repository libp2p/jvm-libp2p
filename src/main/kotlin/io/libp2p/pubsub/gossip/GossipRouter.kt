package io.libp2p.pubsub.gossip

import io.libp2p.core.pubsub.ValidationResult
import io.libp2p.core.pubsub.ValidationResult.Invalid
import io.libp2p.core.pubsub.ValidationResult.Pending
import io.libp2p.etc.types.LRUCollections
import io.libp2p.etc.types.anyComplete
import io.libp2p.etc.types.millis
import io.libp2p.etc.types.seconds
import io.libp2p.etc.types.whenTrue
import io.libp2p.pubsub.AbstractRouter
import io.libp2p.pubsub.MessageId
import pubsub.pb.Rpc
import java.time.Duration
import java.util.concurrent.CompletableFuture

typealias Topic = String

/**
 * Router implementing this protocol: https://github.com/libp2p/specs/tree/master/pubsub/gossipsub
 */
open class GossipRouter(
    val params: GossipParamsCore = GossipParamsCore()
) : AbstractRouter() {

    class TopicScores {
        var meshTime: Duration = 0.seconds
        var firstMessageDeliveries: Double = 0.0
        var meshMessageDeliveries: Double = 0.0
        var meshFailurePenalty: Double = 0.0
        var invalidMessages: Double = 0.0
    }

    class PeerScores {
        val topicScores = mutableMapOf<Topic, TopicScores>()
    }

    val peerScores = mutableMapOf<PeerHandler, PeerScores>()
    val topicsParams = GossipParamsExtTopics()

    val heartbeat by lazy {
        Heartbeat.create(executor, params.heartbeatInterval, curTime)
            .apply { listeners.add(::heartBeat) }
    }

    val mCache = MCache(params.gossipSize, params.gossipHistoryLength)
    val fanout: MutableMap<Topic, MutableSet<PeerHandler>> = linkedMapOf()
    val mesh: MutableMap<Topic, MutableSet<PeerHandler>> = linkedMapOf()
    val lastPublished = linkedMapOf<Topic, Long>()

    private fun submitGossip(topic: Topic, peers: Collection<PeerHandler>) {
        val ids = mCache.getMessageIds(topic)
        if (ids.isNotEmpty()) {
            (peers - (mesh[topic] ?: emptySet())).forEach { ihave(it, ids) }
        }
    }

    private fun getTopicScores(peer: PeerHandler, topic: Topic) =
        peerScores.computeIfAbsent(peer) { PeerScores() }
            .topicScores.computeIfAbsent(topic) { TopicScores() }

    override fun onPeerDisconnected(peer: PeerHandler) {
        mesh.values.forEach { it.remove(peer) }
        fanout.values.forEach { it.remove(peer) }
        collectPeerMessage(peer) // discard them
        super.onPeerDisconnected(peer)
    }

    override fun onPeerActive(peer: PeerHandler) {
        super.onPeerActive(peer)
        heartbeat.hashCode() // force lazy initialization
    }

    private val validationTime: MutableMap<MessageId, Long> = LRUCollections.createMap(1024)
    override fun notifyUnseenMessage(peer: PeerHandler, msg: Rpc.Message) {
    }

    override fun notifySeenMessage(peer: PeerHandler, msg: Rpc.Message, validationResult: ValidationResult) {
        msg.topicIDsList
            .filter { mesh[it]?.contains(peer) ?: false }
            .forEach { topic ->
                val topicScores = getTopicScores(peer, topic)
                val durationAfterValidation =
                    (curTime() - validationTime.getOrDefault(getMessageId(msg), 0)).millis
                when {
                    validationResult == Invalid -> topicScores.invalidMessages++
                    validationResult == Pending
                            || durationAfterValidation < topicsParams[topic].MeshMessageDeliveryWindow ->
                        topicScores.meshMessageDeliveries++
                }
            }
    }

    override fun notifyUnseenInvalidMessage(peer: PeerHandler, msg: Rpc.Message) {
        validationTime[getMessageId(msg)] = curTime()
        msg.topicIDsList.forEach { getTopicScores(peer, it).invalidMessages++ }
    }

    override fun notifyUnseenValidMessage(peer: PeerHandler, msg: Rpc.Message) {
        validationTime[getMessageId(msg)] = curTime()
        msg.topicIDsList
            .onEach { getTopicScores(peer, it).firstMessageDeliveries++ }
            .filter { mesh[it] != null }
            .onEach { getTopicScores(peer, it).meshMessageDeliveries++ }
    }

    fun notifyPruned(peer: PeerHandler, topic: Topic) {
    }

    private fun processControlMessage(controlMsg: Any, receivedFrom: PeerHandler) {
        when (controlMsg) {
            is Rpc.ControlGraft ->
                mesh[controlMsg.topicID]?.add(receivedFrom) ?: prune(receivedFrom, controlMsg.topicID)
            is Rpc.ControlPrune ->
                mesh[controlMsg.topicID]?.remove(receivedFrom)
            is Rpc.ControlIHave ->
                iwant(receivedFrom, controlMsg.messageIDsList - seenMessages.keys)
            is Rpc.ControlIWant ->
                controlMsg.messageIDsList
                    .mapNotNull { mCache.getMessage(it) }
                    .forEach { submitPublishMessage(receivedFrom, it) }
        }
    }

    override fun processControl(ctrl: Rpc.ControlMessage, receivedFrom: PeerHandler) {
        ctrl.run {
            (graftList + pruneList + ihaveList + iwantList)
        }.forEach { processControlMessage(it, receivedFrom) }
    }

    override fun broadcastInbound(msgs: List<Rpc.Message>, receivedFrom: PeerHandler) {
        msgs.forEach { pubMsg ->
            pubMsg.topicIDsList
                .mapNotNull { mesh[it] }
                .flatten()
                .distinct()
                .filter { it != receivedFrom }
                .forEach { submitPublishMessage(it, pubMsg) }
            mCache.put(getMessageId(pubMsg), pubMsg)
        }
        flushAllPending()
    }

    override fun broadcastOutbound(msg: Rpc.Message): CompletableFuture<Unit> {
        msg.topicIDsList.forEach { lastPublished[it] = heartbeat.currentTime() }

        val list = msg.topicIDsList
            .mapNotNull { topic ->
                mesh[topic] ?: fanout[topic] ?: getTopicPeers(topic).shuffled(random).take(params.D)
                    .also {
                        if (it.isNotEmpty()) fanout[topic] = it.toMutableSet()
                    }
            }
            .flatten()
            .map { submitPublishMessage(it, msg) }

        mCache.put(getMessageId(msg), msg)
        flushAllPending()
        return anyComplete(list)
    }

    override fun subscribe(topic: Topic) {
        super.subscribe(topic)
        val fanoutPeers = fanout[topic] ?: mutableSetOf()
        val meshPeers = mesh.getOrPut(topic) { mutableSetOf() }
        val otherPeers = getTopicPeers(topic) - meshPeers - fanoutPeers
        if (meshPeers.size < params.D) {
            val addFromFanout = fanoutPeers.shuffled(random).take(params.D - meshPeers.size)
            val addFromOthers = otherPeers.shuffled(random).take(params.D - meshPeers.size - addFromFanout.size)

            meshPeers += (addFromFanout + addFromOthers)
            (addFromFanout + addFromOthers).forEach {
                graft(it, topic)
            }
        }
    }

    override fun unsubscribe(topic: Topic) {
        super.unsubscribe(topic)
        mesh.remove(topic)?.forEach { prune(it, topic) }
    }

    private fun heartBeat(time: Long) {
        try {
            mesh.entries.forEach { (topic, peers) ->
                if (peers.size < params.DLow) {
                    (getTopicPeers(topic) - peers).shuffled(random).take(params.D - peers.size).forEach { newPeer ->
                        peers += newPeer
                        graft(newPeer, topic)
                    }
                } else if (peers.size > params.DHigh) {
                    peers.shuffled(random).take(peers.size - params.D).forEach { dropPeer ->
                        peers -= dropPeer
                        prune(dropPeer, topic)
                    }
                }
                submitGossip(topic, peers)
            }
            fanout.entries.forEach { (topic, peers) ->
                peers.removeIf { it in getTopicPeers(topic) }
                val needMore = params.D - peers.size
                if (needMore > 0) {
                    peers += (getTopicPeers(topic) - peers).shuffled(random).take(needMore)
                }
                submitGossip(topic, peers)
            }
            lastPublished.entries.removeIf { (topic, lastPub) ->
                (time - lastPub > params.fanoutTTL.toMillis())
                    .whenTrue { fanout.remove(topic) }
            }

            (mesh.keys.toSet() + fanout.keys).forEach { topic ->
                val gossipPeers = (getTopicPeers(topic) - mesh[topic]!! - (fanout[topic] ?: emptyList()))
                    .shuffled(random).take(params.DGossip)
                submitGossip(topic, gossipPeers)
            }

            mCache.shift()
            updateScoresOnHeartbeat(time)

            flushAllPending()
        } catch (t: Exception) {
            logger.warn("Exception in gossipsub heartbeat", t)
        }
    }

    private fun updateScoresOnHeartbeat(time: Long) {
        mesh.forEach { (topic, peers) ->
            peers.forEach { getTopicScores(it, topic).meshTime += params.heartbeatInterval }
        }
    }

    private fun prune(peer: PeerHandler, topic: Topic) = addPendingRpcPart(
        peer,
        Rpc.RPC.newBuilder().setControl(
            Rpc.ControlMessage.newBuilder().addPrune(
                Rpc.ControlPrune.newBuilder().setTopicID(topic)
            )
        ).build()
    )

    private fun graft(peer: PeerHandler, topic: Topic) = addPendingRpcPart(
        peer,
        Rpc.RPC.newBuilder().setControl(
            Rpc.ControlMessage.newBuilder().addGraft(
                Rpc.ControlGraft.newBuilder().setTopicID(topic)
            )
        ).build()
    )

    private fun iwant(peer: PeerHandler, topics: List<Topic>) {
        if (topics.isNotEmpty()) {
            addPendingRpcPart(
                peer,
                Rpc.RPC.newBuilder().setControl(
                    Rpc.ControlMessage.newBuilder().addIwant(
                        Rpc.ControlIWant.newBuilder().addAllMessageIDs(topics)
                    )
                ).build()
            )
        }
    }

    private fun ihave(peer: PeerHandler, topics: List<Topic>) {
        addPendingRpcPart(
            peer,
            Rpc.RPC.newBuilder().setControl(
                Rpc.ControlMessage.newBuilder().addIhave(
                    Rpc.ControlIHave.newBuilder().addAllMessageIDs(topics)
                )
            ).build()
        )
    }
}