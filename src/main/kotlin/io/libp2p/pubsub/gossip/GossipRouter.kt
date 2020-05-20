package io.libp2p.pubsub.gossip

import io.libp2p.core.multiformats.Protocol
import io.libp2p.core.pubsub.ValidationResult
import io.libp2p.etc.types.anyComplete
import io.libp2p.etc.types.whenTrue
import io.libp2p.etc.util.P2PService
import io.libp2p.pubsub.AbstractRouter
import pubsub.pb.Rpc
import java.util.concurrent.CompletableFuture

typealias Topic = String

fun P2PService.PeerHandler.getIP(): String? =
    this.streamHandler.stream.connection.remoteAddress().getStringComponent(Protocol.IP4)

/**
 * Router implementing this protocol: https://github.com/libp2p/specs/tree/master/pubsub/gossipsub
 */
open class GossipRouter(
    val params: GossipParamsCore = GossipParamsCore()
) : AbstractRouter() {

    val score by lazy { GossipScore(executor, curTime) }

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

    override fun onPeerDisconnected(peer: PeerHandler) {
        score.notifyDisconnected(peer)
        mesh.values.forEach { it.remove(peer) }
        fanout.values.forEach { it.remove(peer) }
        collectPeerMessage(peer) // discard them
        super.onPeerDisconnected(peer)
    }

    override fun onPeerActive(peer: PeerHandler) {
        super.onPeerActive(peer)
        score.notifyConnected(peer)
        heartbeat.hashCode() // force lazy initialization
    }

    override fun notifyUnseenMessage(peer: PeerHandler, msg: Rpc.Message) {
        score.notifyUnseenMessage(peer, msg)
    }

    override fun notifySeenMessage(peer: PeerHandler, msg: Rpc.Message, validationResult: ValidationResult) {
        score.notifySeenMessage(peer, msg, validationResult)
    }

    override fun notifyUnseenInvalidMessage(peer: PeerHandler, msg: Rpc.Message) {
        score.notifyUnseenInvalidMessage(peer, msg)
    }

    override fun notifyUnseenValidMessage(peer: PeerHandler, msg: Rpc.Message) {
        score.notifyUnseenValidMessage(peer, msg)
    }

    fun notifyMeshed(peer: PeerHandler, topic: Topic) {
        score.notifyMeshed(peer, topic)
    }

    fun notifyPruned(peer: PeerHandler, topic: Topic) {
        score.notifyPruned(peer, topic)
    }

    private fun processControlMessage(controlMsg: Any, receivedFrom: PeerHandler) {
        val peerScore = score.score(receivedFrom)
        when (controlMsg) {
            is Rpc.ControlGraft -> {
                if (controlMsg.topicID in mesh) {
                    mesh[controlMsg.topicID]!! += receivedFrom
                    notifyMeshed(receivedFrom, controlMsg.topicID)
                } else {
                    prune(receivedFrom, controlMsg.topicID)
                }
            }
            is Rpc.ControlPrune -> {
                mesh[controlMsg.topicID]?.remove(receivedFrom)?.also {
                    notifyPruned(receivedFrom, controlMsg.topicID)
                }
            }
            is Rpc.ControlIHave -> {
                if (peerScore < score.globalParams.gossipThreshold) return
                iwant(receivedFrom, controlMsg.messageIDsList - seenMessages.keys)
            }
            is Rpc.ControlIWant -> {
                if (peerScore < score.globalParams.gossipThreshold) return
                controlMsg.messageIDsList
                    .mapNotNull { mCache.getMessage(it) }
                    .forEach { submitPublishMessage(receivedFrom, it) }
            }
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
                emitGossip(topic, peers)
            }
            fanout.entries.forEach { (topic, peers) ->
                peers.removeIf { it in getTopicPeers(topic) }
                val needMore = params.D - peers.size
                if (needMore > 0) {
                    peers += (getTopicPeers(topic) - peers).shuffled(random).take(needMore)
                }
                emitGossip(topic, peers)
            }
            lastPublished.entries.removeIf { (topic, lastPub) ->
                (time - lastPub > params.fanoutTTL.toMillis())
                    .whenTrue { fanout.remove(topic) }
            }

            mCache.shift()

            flushAllPending()
        } catch (t: Exception) {
            logger.warn("Exception in gossipsub heartbeat", t)
        }
    }

    private fun emitGossip(topic: Topic, excludePeers: Collection<PeerHandler>) {
        val ids = mCache.getMessageIds(topic)
        if (ids.isEmpty()) return

        val shuffledMessageIds = ids.shuffled(random)
        val peers = (getTopicPeers(topic) - excludePeers)
            .filter { score.score(it) >= score.globalParams.gossipThreshold }
            .shuffled(random)
            .take(params.DGossip)
        peers.forEach { ihave(it, shuffledMessageIds) }
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