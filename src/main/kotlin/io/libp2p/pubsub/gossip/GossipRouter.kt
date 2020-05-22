package io.libp2p.pubsub.gossip

import io.libp2p.core.multiformats.Protocol
import io.libp2p.core.pubsub.ValidationResult
import io.libp2p.etc.types.anyComplete
import io.libp2p.etc.types.median
import io.libp2p.etc.types.whenTrue
import io.libp2p.etc.util.P2PService
import io.libp2p.pubsub.AbstractRouter
import pubsub.pb.Rpc
import java.util.concurrent.CompletableFuture
import kotlin.math.max

typealias Topic = String

fun P2PService.PeerHandler.getIP(): String? =
    streamHandler.stream.connection.remoteAddress().getStringComponent(Protocol.IP4)

fun P2PService.PeerHandler.isOutbound() = streamHandler.stream.connection.isInitiator
/**
 * Router implementing this protocol: https://github.com/libp2p/specs/tree/master/pubsub/gossipsub
 */
open class GossipRouter(
    val params: GossipParamsV1_1 = GossipParamsV1_1(),
    val scoreParams: GossipScoreParams = GossipScoreParams()
) : AbstractRouter() {

    private val coreParams: GossipParamsCore = params.coreParams

    val score by lazy { GossipScore(scoreParams, executor, curTime) }

    val heartbeat by lazy {
        Heartbeat.create(executor, coreParams.heartbeatInterval, curTime)
            .apply { listeners.add(::heartBeat) }
    }

    val mCache = MCache(coreParams.gossipSize, coreParams.gossipHistoryLength)
    val fanout: MutableMap<Topic, MutableSet<PeerHandler>> = linkedMapOf()
    val mesh: MutableMap<Topic, MutableSet<PeerHandler>> = linkedMapOf()
    val lastPublished = linkedMapOf<Topic, Long>()
    var heartbeatsCount = 0

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

    override fun acceptRequestsFrom(peer: PeerHandler): Boolean {
        return score.score(peer) >= score.params.graylistThreshold
    }

    private fun processControlMessage(controlMsg: Any, receivedFrom: PeerHandler) {
        val peerScore = score.score(receivedFrom)
        when (controlMsg) {
            is Rpc.ControlGraft -> {
                if (controlMsg.topicID in mesh) {
                    mesh[controlMsg.topicID]!! += receivedFrom
                    notifyMeshed(receivedFrom, controlMsg.topicID)
                } else {
                    enqueuePrune(receivedFrom, controlMsg.topicID)
                }
            }
            is Rpc.ControlPrune -> {
                mesh[controlMsg.topicID]?.remove(receivedFrom)?.also {
                    notifyPruned(receivedFrom, controlMsg.topicID)
                }
            }
            is Rpc.ControlIHave -> {
                if (peerScore < score.params.gossipThreshold) return
                enqueueIwant(receivedFrom, controlMsg.messageIDsList - seenMessages.keys)
            }
            is Rpc.ControlIWant -> {
                if (peerScore < score.params.gossipThreshold) return
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

        val peers =
            if (params.floodPublish) {
                msg.topicIDsList
                    .flatMap { getTopicPeers(it) }
                    .filter { score.score(it) >= score.params.publishThreshold }
            } else {
                msg.topicIDsList
                    .mapNotNull { topic ->
                        mesh[topic] ?: fanout[topic] ?: getTopicPeers(topic).shuffled(random).take(coreParams.D)
                            .also {
                                if (it.isNotEmpty()) fanout[topic] = it.toMutableSet()
                            }
                    }
                    .flatten()
            }
        val list = peers.map { submitPublishMessage(it, msg) }

        mCache.put(getMessageId(msg), msg)
        flushAllPending()
        return anyComplete(list)
    }

    override fun subscribe(topic: Topic) {
        super.subscribe(topic)
        val fanoutPeers = fanout[topic] ?: mutableSetOf()
        val meshPeers = mesh.getOrPut(topic) { mutableSetOf() }
        val otherPeers = getTopicPeers(topic) - meshPeers - fanoutPeers
        if (meshPeers.size < coreParams.D) {
            val addFromFanout = fanoutPeers.shuffled(random).take(coreParams.D - meshPeers.size)
            val addFromOthers = otherPeers.shuffled(random).take(coreParams.D - meshPeers.size - addFromFanout.size)

            meshPeers += (addFromFanout + addFromOthers)
            (addFromFanout + addFromOthers).forEach {
                enqueueGraft(it, topic)
            }
        }
    }

    override fun unsubscribe(topic: Topic) {
        super.unsubscribe(topic)
        mesh.remove(topic)?.forEach { enqueuePrune(it, topic) }
    }

    private fun heartBeat(time: Long) {
        heartbeatsCount++
        try {
            mesh.entries.forEach { (topic, peers) ->

                // drop underscored peers from mesh
                peers.filter { score.score(it) < 0 }
                    .forEach { prune(it, topic) }

                if (peers.size < coreParams.DLow) {
                    // need more mesh peers
                    (getTopicPeers(topic) - peers)
                        .filter { score.score(it) >= 0 }
                        .shuffled(random)
                        .take(coreParams.D - peers.size)
                        .forEach { graft(it, topic) }
                } else if (peers.size > coreParams.DHigh) {
                    // too many mesh peers
                    val sortedPeers = peers
                        .shuffled(random)
                        .sortedBy { score.score(it) }
                        .reversed()

                    val bestDPeers = sortedPeers.take(coreParams.DScore)
                    val restPeers = sortedPeers.drop(coreParams.DScore).shuffled(random)
                    val outboundCount = bestDPeers.count { it.isOutbound() }
                    val outPeers = restPeers
                        .filter { it.isOutbound() }
                        .take(max(0, coreParams.DOut - outboundCount))

                    val toDropPeers = (outPeers + bestDPeers).drop(coreParams.DScore)
                    toDropPeers.forEach { prune(it, topic) }
                }

                // keep outbound peers > DOut
                val outboundCount = peers.count { it.isOutbound() }
                (getTopicPeers(topic) - peers)
                    .filter { score.score(it) >= 0 }
                    .shuffled(random)
                    .take(max(0, coreParams.DOut - outboundCount))
                    .forEach { graft(it, topic) }


                // opportunistic grafting
                if (heartbeatsCount % scoreParams.opportunisticGraftTicks == 0 && peers.size > 1) {
                    val scoreMedian = peers.map { score.score(it) }.median()
                    if (scoreMedian < scoreParams.opportunisticGraftThreshold) {
                        (getTopicPeers(topic) - peers)
                            .filter { score.score(it) > scoreMedian }
                            .forEach { graft(it, topic) }
                    }
                }

                emitGossip(topic, peers)
            }
            fanout.entries.forEach { (topic, peers) ->
                peers.removeIf {
                    it !in getTopicPeers(topic) || score.score(it) < scoreParams.publishThreshold
                }
                val needMore = coreParams.D - peers.size
                if (needMore > 0) {
                    peers += (getTopicPeers(topic) - peers)
                        .filter { score.score(it) >= scoreParams.publishThreshold }
                        .shuffled(random)
                        .take(needMore)
                }
                emitGossip(topic, peers)
            }
            lastPublished.entries.removeIf { (topic, lastPub) ->
                (time - lastPub > coreParams.fanoutTTL.toMillis())
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
            .filter { score.score(it) >= score.params.gossipThreshold }

        peers.shuffled(random)
            .take(max((params.gossipFactor * peers.size).toInt(), coreParams.DGossip))
            .forEach { enqueueIhave(it, shuffledMessageIds) }
    }

    private fun graft(peer: PeerHandler, topic: Topic) {
        mesh.getOrPut(topic) { mutableSetOf() }.add(peer)
        enqueueGraft(peer, topic)
        notifyMeshed(peer, topic)
    }
    private fun prune(peer: PeerHandler, topic: Topic) {
        mesh[topic]?.remove(peer)
        enqueuePrune(peer, topic)
        notifyPruned(peer, topic)
    }

    private fun enqueuePrune(peer: PeerHandler, topic: Topic) = addPendingRpcPart(
        peer,
        Rpc.RPC.newBuilder().setControl(
            Rpc.ControlMessage.newBuilder().addPrune(
                Rpc.ControlPrune.newBuilder().setTopicID(topic)
            )
        ).build()
    )

    private fun enqueueGraft(peer: PeerHandler, topic: Topic) = addPendingRpcPart(
        peer,
        Rpc.RPC.newBuilder().setControl(
            Rpc.ControlMessage.newBuilder().addGraft(
                Rpc.ControlGraft.newBuilder().setTopicID(topic)
            )
        ).build()
    )

    private fun enqueueIwant(peer: PeerHandler, topics: List<Topic>) {
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

    private fun enqueueIhave(peer: PeerHandler, topics: List<Topic>) {
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