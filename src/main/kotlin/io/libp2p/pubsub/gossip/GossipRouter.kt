package io.libp2p.pubsub.gossip

import io.libp2p.core.types.LimitedList
import io.libp2p.core.types.anyComplete
import io.libp2p.core.types.lazyVar
import io.libp2p.core.types.toHex
import io.libp2p.core.types.whenTrue
import io.libp2p.pubsub.AbstractRouter
import pubsub.pb.Rpc
import java.time.Duration
import java.util.concurrent.CompletableFuture

open class GossipRouter : AbstractRouter() {

    data class CacheEntry(val msgId: String, val topics: Set<String>)

    inner class MCache(val gossipSize: Int, historyLength: Int) {

        val messages = mutableMapOf<String, Rpc.Message>()
        private val history = LimitedList<MutableList<CacheEntry>>(historyLength)
            .also { it.add(mutableListOf()) }
            .also { it.onDrop { it.forEach { messages.remove(it.msgId) } } }

        fun put(msg: Rpc.Message) = getGossipId(msg).also {
                messages[it] = msg
                history[0].add(CacheEntry(it, msg.topicIDsList.toSet()))
            }

        fun getMessageIds(topic: String) =
            history.take(gossipSize).flatten().filter { topic in it.topics }.map { it.msgId }.distinct()

        fun shift() = history.add(mutableListOf())
    }

    var heartbeatInterval by lazyVar { Duration.ofSeconds(1) }
    var heartbeat by lazyVar { Heartbeat.create(executor, heartbeatInterval, curTime) }
    var D = 3
    var DLow = 2
    var DHigh = 4
    var fanoutTTL = 60 * 1000L
    var gossipSize by lazyVar { 3 }
    var gossipHistoryLength by lazyVar { 5 }
    var mCache by lazyVar { MCache(gossipSize, gossipHistoryLength) }
    val fanout: MutableMap<String, MutableList<StreamHandler>> = linkedMapOf()
    val mesh: MutableMap<String, MutableList<StreamHandler>> = linkedMapOf()
    val lastPublished = linkedMapOf<String, Long>()
    private var inited = false

    private fun getGossipId(msg: Rpc.Message): String = msg.from.toByteArray().toHex() + msg.seqno.toByteArray().toHex()

    private fun submitGossip(topic: String, peers: Collection<StreamHandler>) {
        val ids = mCache.getMessageIds(topic)
        if (ids.isNotEmpty()) {
            (peers - (mesh[topic] ?: emptySet())).forEach { ihave(it, ids) }
        }
    }

    override fun onPeerDisconnected(peer: StreamHandler) {
        mesh.values.forEach { it.remove(peer) }
        fanout.values.forEach { it.remove(peer) }
        collectPeerMessage(peer) // discard them
        super.onPeerDisconnected(peer)
    }

    override fun onPeerActive(peer: StreamHandler) {
        super.onPeerActive(peer)
        if (!inited) {
            heartbeat.listeners.add(::heartBeat)
            inited = true
        }
    }

    private fun processControlMessage(controlMsg: Any, receivedFrom: StreamHandler) {
        when(controlMsg) {
            is Rpc.ControlGraft ->
                mesh[controlMsg.topicID]?.add(receivedFrom) ?: prune(receivedFrom, controlMsg.topicID)
            is Rpc.ControlPrune ->
                mesh[controlMsg.topicID]?.remove(receivedFrom)
            is Rpc.ControlIHave ->
                iwant(receivedFrom, controlMsg.messageIDsList - seenMessages.map { it.getGossipID() })
            is Rpc.ControlIWant ->
                controlMsg.messageIDsList
                    .mapNotNull { mCache.messages[it] }
                    .forEach { submitPublishMessage(receivedFrom, it) }
        }
    }

    override fun processControl(ctrl: Rpc.ControlMessage, receivedFrom: StreamHandler) {
        ctrl.run {
            (graftList + pruneList + ihaveList + iwantList)
        }.forEach { processControlMessage(it, receivedFrom) }
    }

    override fun broadcastInbound(msg: Rpc.RPC, receivedFrom: StreamHandler) {
        msg.publishList.forEach { pubMsg ->
            pubMsg.topicIDsList
                .mapNotNull { mesh[it] }
                .flatten()
                .distinct()
                .filter { it != receivedFrom }
                .forEach { submitPublishMessage(it, pubMsg) }
            mCache.put(pubMsg)
        }
        flushAllPending()
    }

    override fun broadcastOutbound(msg: Rpc.Message): CompletableFuture<Unit> {
        msg.topicIDsList.forEach { lastPublished[it] = heartbeat.currentTime() }

        val list = msg.topicIDsList
            .mapNotNull { topic ->
                mesh[topic] ?: fanout[topic] ?: getTopicPeers(topic).shuffled(random).take(D)
                    .also {
                        if (it.isNotEmpty()) fanout[topic] = it.toMutableList()
                    }
            }
            .flatten()
            .map { submitPublishMessage(it, msg) }

        mCache.put(msg)
        flushAllPending()
        return anyComplete(list)
    }

    override fun subscribe(topic: String) {
        super.subscribe(topic)
        val fanoutPeers = fanout[topic] ?: mutableListOf()
        val meshPeers = mesh.getOrPut(topic) { mutableListOf() }
        val otherPeers = getTopicPeers(topic) - meshPeers - fanoutPeers
        if (meshPeers.size < D) {
            val addFromFanout = fanoutPeers.shuffled(random).take(D - meshPeers.size)
            val addFromOthers = otherPeers.shuffled(random).take(D - meshPeers.size - addFromFanout.size)

            meshPeers += (addFromFanout + addFromOthers)
            (addFromFanout + addFromOthers).forEach {
                graft(it, topic)
            }
        }
    }

    override fun unsubscribe(topic: String) {
        super.unsubscribe(topic)
        mesh.remove(topic)?.forEach { prune(it, topic) }
    }

    private fun heartBeat(time: Long) {
        mesh.entries.forEach { (topic, peers) ->
            if (peers.size < DLow) {
                (getTopicPeers(topic) - peers).shuffled(random).take(D - peers.size).forEach { newPeer ->
                    peers += newPeer
                    graft(newPeer, topic)
                }
            } else if (peers.size > DHigh) {
                peers.shuffled(random).take(peers.size - D).forEach { dropPeer ->
                    peers -= dropPeer
                    prune(dropPeer, topic)
                }
            }
            submitGossip(topic, peers)
        }
        fanout.entries.forEach { (topic, peers) ->
            peers.removeIf { it in getTopicPeers(topic) }
            val needMore = D - peers.size
            if (needMore > 0) {
                peers += (getTopicPeers(topic) - peers).shuffled(random).take(needMore)
            }
            submitGossip(topic, peers)
        }
        lastPublished.entries.removeIf { (topic, lastPub) ->
            (time - lastPub > fanoutTTL)
                .whenTrue { fanout.remove(topic) } }

        mCache.shift()
        flushAllPending()
    }

    private fun prune(peer: StreamHandler, topic: String) = addPendingRpcPart(
        peer,
        Rpc.RPC.newBuilder().setControl(
            Rpc.ControlMessage.newBuilder().addPrune(
                Rpc.ControlPrune.newBuilder().setTopicID(topic)
            )
        ).build()
    )

    private fun graft(peer: StreamHandler, topic: String) = addPendingRpcPart(
        peer,
        Rpc.RPC.newBuilder().setControl(
            Rpc.ControlMessage.newBuilder().addGraft(
                Rpc.ControlGraft.newBuilder().setTopicID(topic)
            )
        ).build()
    )

    private fun iwant(peer: StreamHandler, topics: List<String>) {
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
    private fun ihave(peer: StreamHandler, topics: List<String>) {
        addPendingRpcPart(
            peer,
            Rpc.RPC.newBuilder().setControl(
                Rpc.ControlMessage.newBuilder().addIhave(
                    Rpc.ControlIHave.newBuilder().addAllMessageIDs(topics)
                )
            ).build())
    }

    fun withDConstants(D: Int, DLow: Int = D * 2 / 3, DHigh: Int = D * 2): GossipRouter {
        this.D = D
        this.DLow = DLow
        this.DHigh = DHigh
        return this
    }
}