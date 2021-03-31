package io.libp2p.pubsub.gossip

import io.libp2p.core.InternalErrorException
import io.libp2p.core.PeerId
import io.libp2p.core.pubsub.ValidationResult
import io.libp2p.etc.types.anyComplete
import io.libp2p.etc.types.copy
import io.libp2p.etc.types.createLRUMap
import io.libp2p.etc.types.median
import io.libp2p.etc.types.seconds
import io.libp2p.etc.types.toProtobuf
import io.libp2p.etc.types.toWBytes
import io.libp2p.etc.types.whenTrue
import io.libp2p.etc.util.P2PService
import io.libp2p.pubsub.AbstractRouter
import io.libp2p.pubsub.MessageId
import io.libp2p.pubsub.PubsubMessage
import io.libp2p.pubsub.PubsubProtocol
import io.libp2p.pubsub.SeenCache
import io.libp2p.pubsub.SimpleSeenCache
import io.libp2p.pubsub.TTLSeenCache
import io.libp2p.pubsub.Topic
import io.libp2p.pubsub.TopicSubscriptionFilter
import pubsub.pb.Rpc
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

const val MaxBackoffEntries = 10 * 1024
const val MaxIAskedEntries = 256
const val MaxPeerIHaveEntries = 256
const val MaxIWantRequestsEntries = 10 * 1024

fun P2PService.PeerHandler.isOutbound() = streamHandler.stream.connection.isInitiator

fun P2PService.PeerHandler.getPeerProtocol(): PubsubProtocol {
    fun P2PService.StreamHandler.getProtocol(): String? = stream.getProtocol().getNow(null)
    val proto =
        getOutboundHandler()?.getProtocol()
            ?: getInboundHandler()?.getProtocol()
            ?: throw InternalErrorException("Couldn't get peer gossip protocol")
    return PubsubProtocol.fromProtocol(proto)
}

/**
 * Router implementing this protocol: https://github.com/libp2p/specs/tree/master/pubsub/gossipsub
 */
open class GossipRouter @JvmOverloads constructor(
    val params: GossipParams = GossipParams(),
    val scoreParams: GossipScoreParams = GossipScoreParams(),
    override val protocol: PubsubProtocol = PubsubProtocol.Gossip_V_1_1,
    subscriptionTopicSubscriptionFilter: TopicSubscriptionFilter = TopicSubscriptionFilter.AllowAllTopicSubscriptionFilter()
) : AbstractRouter(subscriptionTopicSubscriptionFilter, params.maxGossipMessageSize) {

    // The idea behind choosing these specific default values for acceptRequestsWhitelist was
    // - from one side are pretty small and safe: peer unlikely be able to drop its score to `graylist`
    //   with 128 messages. But even if so then it's not critical to accept some extra messages before
    //   blocking - not too much space for DoS here
    // - from the other side param values are pretty high to yield good performance gain
    val acceptRequestsWhitelistThresholdScore = 0
    val acceptRequestsWhitelistMaxMessages = 128
    val acceptRequestsWhitelistDuration = 1.seconds

    val score by lazy { GossipScore(scoreParams, executor, curTimeMillis) }
    val fanout: MutableMap<Topic, MutableSet<PeerHandler>> = linkedMapOf()
    val mesh: MutableMap<Topic, MutableSet<PeerHandler>> = linkedMapOf()

    private val mCache = MCache(params.gossipSize, params.gossipHistoryLength)
    private val lastPublished = linkedMapOf<Topic, Long>()
    private var heartbeatsCount = 0
    private val backoffExpireTimes = createLRUMap<Pair<PeerId, Topic>, Long>(MaxBackoffEntries)
    private val iAsked = createLRUMap<PeerHandler, AtomicInteger>(MaxIAskedEntries)
    private val peerIHave = createLRUMap<PeerHandler, AtomicInteger>(MaxPeerIHaveEntries)
    private val iWantRequests = createLRUMap<Pair<PeerHandler, MessageId>, Long>(MaxIWantRequestsEntries)
    private val heartbeatTask by lazy {
        executor.scheduleWithFixedDelay(
            ::catchingHeartbeat,
            params.heartbeatInterval.toMillis(),
            params.heartbeatInterval.toMillis(),
            TimeUnit.MILLISECONDS
        )
    }
    private val acceptRequestsWhitelist = mutableMapOf<PeerHandler, AcceptRequestsWhitelistEntry>()

    override val seenMessages: SeenCache<Optional<ValidationResult>> by lazy {
        TTLSeenCache(SimpleSeenCache(), params.seenTTL, curTimeMillis)
    }

    private fun setBackOff(peer: PeerHandler, topic: Topic) = setBackOff(peer, topic, params.pruneBackoff.toMillis())
    private fun setBackOff(peer: PeerHandler, topic: Topic, delay: Long) {
        backoffExpireTimes[peer.peerId to topic] = curTimeMillis() + delay
    }

    private fun isBackOff(peer: PeerHandler, topic: Topic) =
        curTimeMillis() < (backoffExpireTimes[peer.peerId to topic] ?: 0)

    private fun isBackOffFlood(peer: PeerHandler, topic: Topic): Boolean {
        val expire = backoffExpireTimes[peer.peerId to topic] ?: return false
        return curTimeMillis() < expire - (params.pruneBackoff + params.graftFloodThreshold).toMillis()
    }

    private fun getDirectPeers() = peers.filter(::isDirect)
    private fun isDirect(peer: PeerHandler) = score.peerParams.isDirect(peer.peerId)
    private fun isConnected(peerId: PeerId) = peers.any { it.peerId == peerId }

    override fun onPeerDisconnected(peer: PeerHandler) {
        score.notifyDisconnected(peer)
        mesh.values.forEach { it.remove(peer) }
        fanout.values.forEach { it.remove(peer) }
        acceptRequestsWhitelist -= peer
        collectPeerMessage(peer) // discard them
        super.onPeerDisconnected(peer)
    }

    override fun onPeerActive(peer: PeerHandler) {
        super.onPeerActive(peer)
        score.notifyConnected(peer)
        heartbeatTask.hashCode() // force lazy initialization
    }

    override fun notifyUnseenMessage(peer: PeerHandler, msg: PubsubMessage) {
        score.notifyUnseenMessage(peer, msg)
        notifyAnyMessage(peer, msg)
    }

    override fun notifySeenMessage(
        peer: PeerHandler,
        msg: PubsubMessage,
        validationResult: Optional<ValidationResult>
    ) {
        score.notifySeenMessage(peer, msg, validationResult)
        notifyAnyMessage(peer, msg)
        if (validationResult.isPresent && validationResult.get() != ValidationResult.Invalid) {
            notifyAnyValidMessage(peer, msg)
        }
    }

    override fun notifyUnseenInvalidMessage(peer: PeerHandler, msg: PubsubMessage) {
        score.notifyUnseenInvalidMessage(peer, msg)
    }

    override fun notifyUnseenValidMessage(peer: PeerHandler, msg: PubsubMessage) {
        score.notifyUnseenValidMessage(peer, msg)
        notifyAnyValidMessage(peer, msg)
    }

    override fun notifyMalformedMessage(peer: PeerHandler) {
        notifyRouterMisbehavior(peer, 1)
    }

    protected open fun notifyAnyMessage(peer: PeerHandler, msg: PubsubMessage) {
        if (iWantRequests.remove(peer to msg.messageId) != null) {
            notifyIWantComplete(peer, msg)
        }
    }

    protected open fun notifyAnyValidMessage(peer: PeerHandler, msg: PubsubMessage) {
    }

    protected open fun notifyIWantComplete(peer: PeerHandler, msg: PubsubMessage) {
    }

    protected open fun notifyIWantTimeout(peer: PeerHandler, msgId: MessageId) {
        notifyRouterMisbehavior(peer, 1)
    }

    protected open fun notifyMeshed(peer: PeerHandler, topic: Topic) {
        score.notifyMeshed(peer, topic)
    }

    fun notifyPruned(peer: PeerHandler, topic: Topic) {
        score.notifyPruned(peer, topic)
    }

    fun notifyRouterMisbehavior(peer: PeerHandler, penalty: Int) {
        score.notifyRouterMisbehavior(peer, penalty)
    }

    override fun acceptRequestsFrom(peer: PeerHandler): Boolean {
        if (isDirect(peer)) {
            return true
        }

        val curTime = curTimeMillis()
        val whitelistEntry = acceptRequestsWhitelist[peer]
        if (whitelistEntry != null &&
            curTime <= whitelistEntry.whitelistedTill &&
            whitelistEntry.messagesAccepted < acceptRequestsWhitelistMaxMessages
        ) {

            acceptRequestsWhitelist[peer] = whitelistEntry.incrementMessageCount()
            return true
        }

        val peerScore = score.score(peer)
        if (peerScore >= acceptRequestsWhitelistThresholdScore) {
            acceptRequestsWhitelist[peer] =
                AcceptRequestsWhitelistEntry(curTime + acceptRequestsWhitelistDuration.toMillis())
        } else {
            acceptRequestsWhitelist -= peer
        }

        return peerScore >= score.params.graylistThreshold
    }

    override fun validateMessageListLimits(msg: Rpc.RPC): Boolean {
        return params.maxPublishedMessages?.let { msg.publishCount <= it } ?: true &&
            params.maxTopicsPerPublishedMessage?.let { msg.publishList.none { m -> m.topicIDsCount > it } } ?: true &&
            params.maxSubscriptions?.let { msg.subscriptionsCount <= it } ?: true &&
            params.maxIHaveLength.let { countIHaveMessageIds(msg) <= it } &&
            params.maxIWantMessageIds?.let { countIWantMessageIds(msg) <= it } ?: true &&
            params.maxGraftMessages?.let { msg.control?.graftCount ?: 0 <= it } ?: true &&
            params.maxPruneMessages?.let { msg.control?.pruneCount ?: 0 <= it } ?: true &&
            params.maxPeersPerPruneMessage?.let { msg.control?.pruneList?.none { p -> p.peersCount > it } } ?: true
    }

    private fun countIWantMessageIds(msg: Rpc.RPC): Int {
        return msg.control?.iwantList?.map { w -> w.messageIDsCount }?.sum() ?: 0
    }

    private fun countIHaveMessageIds(msg: Rpc.RPC): Int {
        return msg.control?.ihaveList?.map { w -> w.messageIDsCount }?.sum() ?: 0
    }

    private fun processControlMessage(controlMsg: Any, receivedFrom: PeerHandler) {
        when (controlMsg) {
            is Rpc.ControlGraft -> handleGraft(controlMsg, receivedFrom)
            is Rpc.ControlPrune -> handlePrune(controlMsg, receivedFrom)
            is Rpc.ControlIHave -> handleIHave(controlMsg, receivedFrom)
            is Rpc.ControlIWant -> handleIWant(controlMsg, receivedFrom)
        }
    }

    private fun handleGraft(msg: Rpc.ControlGraft, peer: PeerHandler) {
        val topic = msg.topicID
        // ignore GRAFT for unknown topics
        val meshPeers = mesh[topic] ?: return
        when {
            isDirect(peer) ->
                prune(peer, topic)
            isBackOff(peer, topic) -> {
                notifyRouterMisbehavior(peer, 1)
                if (isBackOffFlood(peer, topic)) {
                    notifyRouterMisbehavior(peer, 1)
                }
                prune(peer, topic)
            }
            score.score(peer) < 0 ->
                prune(peer, topic)
            meshPeers.size >= params.DHigh && !peer.isOutbound() ->
                prune(peer, topic)
            peer !in meshPeers ->
                graft(peer, topic)
        }
    }

    private fun handlePrune(msg: Rpc.ControlPrune, peer: PeerHandler) {
        val topic = msg.topicID
        mesh[topic]?.remove(peer)?.also {
            notifyPruned(peer, topic)
        }
        if (this.protocol == PubsubProtocol.Gossip_V_1_1) {
            if (msg.hasBackoff()) {
                setBackOff(peer, topic, msg.backoff.seconds.toMillis())
            } else {
                setBackOff(peer, topic)
            }
            if (score.score(peer) >= scoreParams.acceptPXThreshold) {
                processPrunePeers(msg.peersList)
            }
        } else {
            if (msg.hasBackoff() || msg.peersCount > 0) {
                notifyRouterMisbehavior(peer, 1)
            }
        }
    }

    private fun handleIHave(msg: Rpc.ControlIHave, peer: PeerHandler) {
        val peerScore = score.score(peer)
        // we ignore IHAVE gossip from any peer whose score is below the gossip threshold
        if (peerScore < score.params.gossipThreshold) return
        if (peerIHave.computeIfAbsent(peer) { AtomicInteger() }.incrementAndGet() > params.maxIHaveMessages) {
            // peer has advertised too many times within this heartbeat interval, ignoring
            return
        }
        val asked = iAsked.computeIfAbsent(peer) { AtomicInteger() }
        if (asked.get() >= params.maxIHaveLength) {
            // peer has already advertised too many messages, ignoring
            return
        }

        val iWant = msg.messageIDsList
            .map { it.toWBytes() }
            .filterNot { seenMessages.isSeen(it) }
        val maxToAsk = min(iWant.size, params.maxIHaveLength - asked.get())
        asked.addAndGet(maxToAsk)
        iWant(peer, iWant.shuffled(random).subList(0, maxToAsk))
    }

    private fun handleIWant(msg: Rpc.ControlIWant, peer: PeerHandler) {
        val peerScore = score.score(peer)
        if (peerScore < score.params.gossipThreshold) return
        msg.messageIDsList
            .mapNotNull { mCache.getMessageForPeer(peer.peerId, it.toWBytes()) }
            .filter { it.sentCount < params.gossipRetransmission }
            .map { it.msg }
            .forEach { submitPublishMessage(peer, it) }
    }

    private fun processPrunePeers(peersList: List<Rpc.PeerInfo>) {
        peersList.shuffled(random).take(params.maxPrunePeers)
            .map { PeerId(it.peerID.toByteArray()) to it.signedPeerRecord.toByteArray() }
            .filter { (id, _) -> !isConnected(id) }
            .forEach { (id, record) -> params.connectCallback(id, record) }
    }

    override fun processControl(ctrl: Rpc.ControlMessage, receivedFrom: PeerHandler) {
        ctrl.run {
            (graftList + pruneList + ihaveList + iwantList)
        }.forEach { processControlMessage(it, receivedFrom) }
    }

    override fun broadcastInbound(msgs: List<PubsubMessage>, receivedFrom: PeerHandler) {
        msgs.forEach { pubMsg ->
            pubMsg.topics
                .mapNotNull { mesh[it] }
                .flatten()
                .distinct()
                .plus(getDirectPeers())
                .filter { it != receivedFrom }
                .forEach { submitPublishMessage(it, pubMsg) }
            mCache += pubMsg
        }
        flushAllPending()
    }

    override fun broadcastOutbound(msg: PubsubMessage): CompletableFuture<Unit> {
        msg.topics.forEach { lastPublished[it] = curTimeMillis() }

        val peers =
            if (params.floodPublish) {
                msg.topics
                    .flatMap { getTopicPeers(it) }
                    .filter { score.score(it) >= score.params.publishThreshold }
                    .plus(getDirectPeers())
            } else {
                msg.topics
                    .mapNotNull { topic ->
                        mesh[topic] ?: fanout[topic] ?: getTopicPeers(topic).shuffled(random).take(params.D)
                            .also {
                                if (it.isNotEmpty()) fanout[topic] = it.toMutableSet()
                            }
                    }
                    .flatten()
            }
        val list = peers.map { submitPublishMessage(it, msg) }

        mCache += msg
        flushAllPending()
        return anyComplete(list)
    }

    override fun subscribe(topic: Topic) {
        super.subscribe(topic)
        val fanoutPeers = (fanout[topic] ?: mutableSetOf())
            .filter { score.score(it) >= 0 && !isDirect(it) }
        val meshPeers = mesh.getOrPut(topic) { mutableSetOf() }
        val otherPeers = (getTopicPeers(topic) - meshPeers - fanoutPeers)
            .filter { score.score(it) >= 0 && !isDirect(it) }

        if (meshPeers.size < params.D) {
            val addFromFanout = fanoutPeers.shuffled(random)
                .take(params.D - meshPeers.size)
            val addFromOthers = otherPeers.shuffled(random)
                .take(params.D - meshPeers.size - addFromFanout.size)

            (addFromFanout + addFromOthers).forEach {
                graft(it, topic)
            }
            fanout -= topic
            lastPublished -= topic
        }
    }

    override fun unsubscribe(topic: Topic) {
        super.unsubscribe(topic)
        mesh[topic]?.copy()?.forEach { prune(it, topic) }
        mesh -= topic
    }

    private fun catchingHeartbeat() {
        try {
            heartbeat()
        } catch (e: Exception) {
            onServiceException(null, null, e)
        }
    }

    private fun heartbeat() {
        heartbeatsCount++
        iAsked.clear()
        peerIHave.clear()

        val staleIWantTime = this.curTimeMillis() - params.iWantFollowupTime.toMillis()
        iWantRequests.entries.removeIf { (key, time) ->
            (time < staleIWantTime)
                .whenTrue { notifyIWantTimeout(key.first, key.second) }
        }

        try {
            mesh.entries.forEach { (topic, peers) ->

                // drop underscored peers from mesh
                peers.filter { score.score(it) < 0 }
                    .forEach { prune(it, topic) }

                if (peers.size < params.DLow) {
                    // need more mesh peers
                    (getTopicPeers(topic) - peers)
                        .filter { score.score(it) >= 0 && !isDirect(it) && !isBackOff(it, topic) }
                        .shuffled(random)
                        .take(params.D - peers.size)
                        .forEach { graft(it, topic) }
                } else if (peers.size > params.DHigh) {
                    // too many mesh peers
                    val sortedPeers = peers
                        .shuffled(random)
                        .sortedBy { score.score(it) }
                        .reversed()

                    val bestDPeers = sortedPeers.take(params.DScore)
                    val restPeers = sortedPeers.drop(params.DScore).shuffled(random)
                    val outboundCount = (bestDPeers + restPeers).take(params.D).count { it.isOutbound() }
                    val outPeers = restPeers
                        .filter { it.isOutbound() }
                        .take(max(0, params.DOut - outboundCount))

                    val toDropPeers = (outPeers + bestDPeers + restPeers).drop(params.D)
                    toDropPeers.forEach { prune(it, topic) }
                }

                // keep outbound peers > DOut
                val outboundCount = peers.count { it.isOutbound() }
                (getTopicPeers(topic) - peers)
                    .filter { it.isOutbound() && score.score(it) >= 0 && !isDirect(it) && !isBackOff(it, topic) }
                    .shuffled(random)
                    .take(max(0, params.DOut - outboundCount))
                    .forEach { graft(it, topic) }

                // opportunistic grafting
                if (heartbeatsCount % params.opportunisticGraftTicks == 0 && peers.size > 1) {
                    val scoreMedian = peers.map { score.score(it) }.median()
                    if (scoreMedian < scoreParams.opportunisticGraftThreshold) {
                        (getTopicPeers(topic) - peers)
                            .filter { score.score(it) > scoreMedian && !isDirect(it) && !isBackOff(it, topic) }
                            .take(params.opportunisticGraftPeers)
                            .forEach { graft(it, topic) }
                    }
                }

                emitGossip(topic, peers)
            }
            fanout.entries.forEach { (topic, peers) ->
                peers.removeIf {
                    it !in getTopicPeers(topic) || score.score(it) < scoreParams.publishThreshold
                }
                val needMore = params.D - peers.size
                if (needMore > 0) {
                    peers += (getTopicPeers(topic) - peers)
                        .filter { score.score(it) >= scoreParams.publishThreshold && !isDirect(it) }
                        .shuffled(random)
                        .take(needMore)
                }
                emitGossip(topic, peers)
            }
            lastPublished.entries.removeIf { (topic, lastPub) ->
                (curTimeMillis() - lastPub > params.fanoutTTL.toMillis())
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

        val shuffledMessageIds = ids.shuffled(random).take(params.maxIHaveLength)
        val peers = (getTopicPeers(topic) - excludePeers)
            .filter { score.score(it) >= score.params.gossipThreshold && !isDirect(it) }

        peers.shuffled(random)
            .take(max((params.gossipFactor * peers.size).toInt(), params.DLazy))
            .forEach { enqueueIhave(it, shuffledMessageIds) }
    }

    private fun graft(peer: PeerHandler, topic: Topic) {
        mesh.getOrPut(topic) { mutableSetOf() }.add(peer)
        enqueueGraft(peer, topic)
        notifyMeshed(peer, topic)
    }

    private fun prune(peer: PeerHandler, topic: Topic) {
        setBackOff(peer, topic)
        enqueuePrune(peer, topic)
        if (mesh[topic]?.remove(peer) == true) {
            notifyPruned(peer, topic)
        }
    }

    private fun iWant(peer: PeerHandler, messageIds: List<MessageId>) {
        if (messageIds.isEmpty()) return
        messageIds[random.nextInt(messageIds.size)]
            .also { iWantRequests[peer to it] = curTimeMillis() }
        enqueueIwant(peer, messageIds)
    }

    private fun enqueuePrune(peer: PeerHandler, topic: Topic) {
        val pruneBuilder = Rpc.ControlPrune.newBuilder().setTopicID(topic)
        if (peer.getPeerProtocol() == PubsubProtocol.Gossip_V_1_1 && this.protocol == PubsubProtocol.Gossip_V_1_1) {
            // add v1.1 specific fields
            pruneBuilder.backoff = params.pruneBackoff.seconds
            (getTopicPeers(topic) - peer)
                .filter { score.score(it) >= 0 }
                .forEach {
                    pruneBuilder.addPeers(
                        Rpc.PeerInfo.newBuilder()
                            .setPeerID(it.peerId.bytes.toProtobuf())
                        // TODO skipping address record for now
                    )
                }
        }
        addPendingRpcPart(
            peer,
            Rpc.RPC.newBuilder().setControl(
                Rpc.ControlMessage.newBuilder().addPrune(
                    pruneBuilder
                )
            ).build()
        )
    }

    private fun enqueueGraft(peer: PeerHandler, topic: Topic) = addPendingRpcPart(
        peer,
        Rpc.RPC.newBuilder().setControl(
            Rpc.ControlMessage.newBuilder().addGraft(
                Rpc.ControlGraft.newBuilder().setTopicID(topic)
            )
        ).build()
    )

    private fun enqueueIwant(peer: PeerHandler, messageIds: List<MessageId>) {
        if (messageIds.isNotEmpty()) {
            addPendingRpcPart(
                peer,
                Rpc.RPC.newBuilder().setControl(
                    Rpc.ControlMessage.newBuilder().addIwant(
                        Rpc.ControlIWant.newBuilder().addAllMessageIDs(messageIds.map { it.toProtobuf() })
                    )
                ).build()
            )
        }
    }

    private fun enqueueIhave(peer: PeerHandler, messageIds: List<MessageId>) {
        addPendingRpcPart(
            peer,
            Rpc.RPC.newBuilder().setControl(
                Rpc.ControlMessage.newBuilder().addIhave(
                    Rpc.ControlIHave.newBuilder().addAllMessageIDs(messageIds.map { it.toProtobuf() })
                )
            ).build()
        )
    }

    data class AcceptRequestsWhitelistEntry(val whitelistedTill: Long, val messagesAccepted: Int = 0) {
        fun incrementMessageCount() = AcceptRequestsWhitelistEntry(whitelistedTill, messagesAccepted + 1)
    }
}
