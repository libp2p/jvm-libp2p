package io.libp2p.pubsub.gossip

import io.libp2p.core.InternalErrorException
import io.libp2p.core.PeerId
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.pubsub.ValidationResult
import io.libp2p.etc.types.*
import io.libp2p.etc.util.P2PService
import io.libp2p.pubsub.*
import org.apache.logging.log4j.LogManager
import pubsub.pb.Rpc
import java.time.Duration
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.collections.Collection
import kotlin.collections.List
import kotlin.collections.MutableMap
import kotlin.collections.MutableSet
import kotlin.collections.any
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.count
import kotlin.collections.distinct
import kotlin.collections.drop
import kotlin.collections.filter
import kotlin.collections.filterNot
import kotlin.collections.flatMap
import kotlin.collections.flatten
import kotlin.collections.forEach
import kotlin.collections.getOrPut
import kotlin.collections.isNotEmpty
import kotlin.collections.linkedMapOf
import kotlin.collections.map
import kotlin.collections.mapNotNull
import kotlin.collections.minus
import kotlin.collections.minusAssign
import kotlin.collections.mutableMapOf
import kotlin.collections.mutableSetOf
import kotlin.collections.none
import kotlin.collections.plus
import kotlin.collections.plusAssign
import kotlin.collections.reversed
import kotlin.collections.set
import kotlin.collections.shuffled
import kotlin.collections.sortedBy
import kotlin.collections.sum
import kotlin.collections.take
import kotlin.collections.toMutableSet
import kotlin.math.max
import kotlin.math.min

const val MaxBackoffEntries = 10 * 1024
const val MaxIAskedEntries = 256
const val MaxPeerIHaveEntries = 256
const val MaxIWantRequestsEntries = 10 * 1024

typealias CurrentTimeSupplier = () -> Long

fun P2PService.PeerHandler.getRemoteAddress(): Multiaddr = streamHandler.stream.connection.remoteAddress()
fun P2PService.PeerHandler.isOutbound() = streamHandler.stream.connection.isInitiator

fun P2PService.PeerHandler.getPeerProtocol(): PubsubProtocol {
    fun P2PService.StreamHandler.getProtocol(): String? = stream.getProtocol().getNow(null)
    val proto =
        getOutboundHandler()?.getProtocol()
            ?: getInboundHandler()?.getProtocol()
            ?: throw InternalErrorException("Couldn't get peer gossip protocol")
    return PubsubProtocol.fromProtocol(proto)
}

private val logger = LogManager.getLogger(GossipRouter::class.java)

/**
 * Router implementing this protocol: https://github.com/libp2p/specs/tree/master/pubsub/gossipsub
 */
open class GossipRouter(
    val params: GossipParams,
    val scoreParams: GossipScoreParams,
    val currentTimeSupplier: CurrentTimeSupplier,
    val random: Random,
    val name: String,
    val mCache: MCache,
    val score: GossipScore,

    subscriptionTopicSubscriptionFilter: TopicSubscriptionFilter,
    protocol: PubsubProtocol,
    executor: ScheduledExecutorService,
    messageFactory: PubsubMessageFactory,
    seenMessages: SeenCache<Optional<ValidationResult>>,
    messageValidator: PubsubRouterMessageValidator,
) : AbstractRouter(
    executor,
    protocol,
    subscriptionTopicSubscriptionFilter,
    params.maxGossipMessageSize,
    messageFactory,
    seenMessages,
    messageValidator
) {

    // The idea behind choosing these specific default values for acceptRequestsWhitelist was
    // - from one side are pretty small and safe: peer unlikely be able to drop its score to `graylist`
    //   with 128 messages. But even if so then it's not critical to accept some extra messages before
    //   blocking - not too much space for DoS here
    // - from the other side param values are pretty high to yield good performance gain
    val acceptRequestsWhitelistThresholdScore = 0
    val acceptRequestsWhitelistMaxMessages = 128
    val acceptRequestsWhitelistDuration = 1.seconds

    val fanout: MutableMap<Topic, MutableSet<PeerHandler>> = linkedMapOf()
    val mesh: MutableMap<Topic, MutableSet<PeerHandler>> = linkedMapOf()
    val eventBroadcaster = GossipRouterEventBroadcaster()

    open val heartbeatInitialDelay: Duration = params.heartbeatInterval

    private val lastPublished = linkedMapOf<Topic, Long>()
    private var heartbeatsCount = 0
    private val backoffExpireTimes = createLRUMap<Pair<PeerId, Topic>, Long>(MaxBackoffEntries)
    private val iAsked = createLRUMap<PeerHandler, AtomicInteger>(MaxIAskedEntries)
    private val peerIHave = createLRUMap<PeerHandler, AtomicInteger>(MaxPeerIHaveEntries)
    private val iWantRequests = createLRUMap<Pair<PeerHandler, MessageId>, Long>(MaxIWantRequestsEntries)
    private val heartbeatTask by lazy {
        executor.scheduleWithFixedDelay(
            ::catchingHeartbeat,
            heartbeatInitialDelay.toMillis(),
            params.heartbeatInterval.toMillis(),
            TimeUnit.MILLISECONDS
        )
    }
    private val acceptRequestsWhitelist = mutableMapOf<PeerHandler, AcceptRequestsWhitelistEntry>()
    override val pendingRpcParts = PendingRpcPartsMap<GossipRpcPartsQueue> { DefaultGossipRpcPartsQueue(params) }

    private fun setBackOff(peer: PeerHandler, topic: Topic) = setBackOff(peer, topic, params.pruneBackoff.toMillis())
    private fun setBackOff(peer: PeerHandler, topic: Topic, delay: Long) {
        backoffExpireTimes[peer.peerId to topic] = currentTimeSupplier() + delay
    }

    private fun isBackOff(peer: PeerHandler, topic: Topic) =
        currentTimeSupplier() < (backoffExpireTimes[peer.peerId to topic] ?: 0)

    private fun isBackOffFlood(peer: PeerHandler, topic: Topic): Boolean {
        val expire = backoffExpireTimes[peer.peerId to topic] ?: return false
        return currentTimeSupplier() < expire - (params.pruneBackoff + params.graftFloodThreshold).toMillis()
    }

    private fun getDirectPeers() = peers.filter(::isDirect)
    private fun isDirect(peer: PeerHandler) = scoreParams.peerScoreParams.isDirect(peer.peerId)
    private fun isConnected(peerId: PeerId) = peers.any { it.peerId == peerId }

    override fun onPeerDisconnected(peer: PeerHandler) {
        eventBroadcaster.notifyDisconnected(peer.peerId)
        mesh.values.forEach { it.remove(peer) }
        fanout.values.forEach { it.remove(peer) }
        acceptRequestsWhitelist -= peer
        pendingRpcParts.popQueue(peer) // discard them
        super.onPeerDisconnected(peer)
    }

    override fun onPeerActive(peer: PeerHandler) {
        super.onPeerActive(peer)
        eventBroadcaster.notifyConnected(peer.peerId, peer.getRemoteAddress())
        heartbeatTask.hashCode() // force lazy initialization
    }

    override fun notifyUnseenMessage(peer: PeerHandler, msg: PubsubMessage) {
        eventBroadcaster.notifyUnseenMessage(peer.peerId, msg)
        notifyAnyMessage(peer, msg)
    }

    override fun notifySeenMessage(
        peer: PeerHandler,
        msg: PubsubMessage,
        validationResult: Optional<ValidationResult>
    ) {
        eventBroadcaster.notifySeenMessage(peer.peerId, msg, validationResult)
        notifyAnyMessage(peer, msg)
        if (validationResult.isPresent && validationResult.get() != ValidationResult.Invalid) {
            notifyAnyValidMessage(peer, msg)
        }
    }

    override fun notifyUnseenInvalidMessage(peer: PeerHandler, msg: PubsubMessage) {
        eventBroadcaster.notifyUnseenInvalidMessage(peer.peerId, msg)
    }

    override fun notifyUnseenValidMessage(peer: PeerHandler, msg: PubsubMessage) {
        eventBroadcaster.notifyUnseenValidMessage(peer.peerId, msg)
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
        eventBroadcaster.notifyMeshed(peer.peerId, topic)
    }

    fun notifyPruned(peer: PeerHandler, topic: Topic) {
        eventBroadcaster.notifyPruned(peer.peerId, topic)
    }

    fun notifyRouterMisbehavior(peer: PeerHandler, penalty: Int) {
        eventBroadcaster.notifyRouterMisbehavior(peer.peerId, penalty)
    }

    override fun acceptRequestsFrom(peer: PeerHandler): Boolean {
        if (isDirect(peer)) {
            return true
        }

        val curTime = currentTimeSupplier()
        val whitelistEntry = acceptRequestsWhitelist[peer]
        if (whitelistEntry != null &&
            curTime <= whitelistEntry.whitelistedTill &&
            whitelistEntry.messagesAccepted < acceptRequestsWhitelistMaxMessages
        ) {

            acceptRequestsWhitelist[peer] = whitelistEntry.incrementMessageCount()
            return true
        }

        val peerScore = score.score(peer.peerId)
        if (peerScore >= acceptRequestsWhitelistThresholdScore) {
            acceptRequestsWhitelist[peer] =
                AcceptRequestsWhitelistEntry(curTime + acceptRequestsWhitelistDuration.toMillis())
        } else {
            acceptRequestsWhitelist -= peer
        }

        return peerScore >= scoreParams.graylistThreshold
    }

    override fun validateMessageListLimits(msg: Rpc.RPCOrBuilder): Boolean {
        val iWantMessageIdCount = msg.control?.iwantList?.map { w -> w.messageIDsCount }?.sum() ?: 0
        val iHaveMessageIdCount = msg.control?.ihaveList?.map { w -> w.messageIDsCount }?.sum() ?: 0

        return params.maxPublishedMessages?.let { msg.publishCount <= it } ?: true &&
            params.maxTopicsPerPublishedMessage?.let { msg.publishList.none { m -> m.topicIDsCount > it } } ?: true &&
            params.maxSubscriptions?.let { msg.subscriptionsCount <= it } ?: true &&
            params.maxIHaveLength.let { iHaveMessageIdCount <= it } &&
            params.maxIWantMessageIds?.let { iWantMessageIdCount <= it } ?: true &&
            params.maxGraftMessages?.let { (msg.control?.graftCount ?: 0) <= it } ?: true &&
            params.maxPruneMessages?.let { (msg.control?.pruneCount ?: 0) <= it } ?: true &&
            params.maxPeersPerPruneMessage?.let { msg.control?.pruneList?.none { p -> p.peersCount > it } } ?: true
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
            score.score(peer.peerId) < 0 ->
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
            if (score.score(peer.peerId) >= scoreParams.acceptPXThreshold) {
                processPrunePeers(msg.peersList)
            }
        } else {
            if (msg.hasBackoff() || msg.peersCount > 0) {
                notifyRouterMisbehavior(peer, 1)
            }
        }
    }

    private fun handleIHave(msg: Rpc.ControlIHave, peer: PeerHandler) {
        val peerScore = score.score(peer.peerId)
        // we ignore IHAVE gossip from any peer whose score is below the gossip threshold
        if (peerScore < scoreParams.gossipThreshold) return
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
        val peerScore = score.score(peer.peerId)
        if (peerScore < scoreParams.gossipThreshold) return
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
        msg.topics.forEach { lastPublished[it] = currentTimeSupplier() }

        val peers =
            if (params.floodPublish) {
                msg.topics
                    .flatMap { getTopicPeers(it) }
                    .filter { score.score(it.peerId) >= scoreParams.publishThreshold }
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

        if (list.isNotEmpty()) {
            return anyComplete(list)
        } else {
            return completedExceptionally(
                NoPeersForOutboundMessageException("No peers for message topics ${msg.topics} found")
            )
        }
    }

    override fun subscribe(topic: Topic) {
        super.subscribe(topic)
        val fanoutPeers = (fanout[topic] ?: mutableSetOf())
            .filter { score.score(it.peerId) >= 0 && !isDirect(it) }
        val meshPeers = mesh.getOrPut(topic) { mutableSetOf() }
        val otherPeers = (getTopicPeers(topic) - meshPeers - fanoutPeers)
            .filter { score.score(it.peerId) >= 0 && !isDirect(it) }

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

        val staleIWantTime = this.currentTimeSupplier() - params.iWantFollowupTime.toMillis()
        iWantRequests.entries.removeIf { (key, time) ->
            (time < staleIWantTime)
                .whenTrue { notifyIWantTimeout(key.first, key.second) }
        }

        try {
            mesh.entries.forEach { (topic, peers) ->

                // drop underscored peers from mesh
                peers.filter { score.score(it.peerId) < 0 }
                    .forEach { prune(it, topic) }

                if (peers.size < params.DLow) {
                    // need more mesh peers
                    (getTopicPeers(topic) - peers)
                        .filter { score.score(it.peerId) >= 0 && !isDirect(it) && !isBackOff(it, topic) }
                        .shuffled(random)
                        .take(params.D - peers.size)
                        .forEach { graft(it, topic) }
                } else if (peers.size > params.DHigh) {
                    // too many mesh peers
                    val sortedPeers = peers
                        .shuffled(random)
                        .sortedBy { score.score(it.peerId) }
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
                    .filter { it.isOutbound() && score.score(it.peerId) >= 0 && !isDirect(it) && !isBackOff(it, topic) }
                    .shuffled(random)
                    .take(max(0, params.DOut - outboundCount))
                    .forEach { graft(it, topic) }

                // opportunistic grafting
                if (heartbeatsCount % params.opportunisticGraftTicks == 0 && peers.size > 1) {
                    val scoreMedian = peers.map { score.score(it.peerId) }.median()
                    if (scoreMedian < scoreParams.opportunisticGraftThreshold) {
                        (getTopicPeers(topic) - peers)
                            .filter { score.score(it.peerId) > scoreMedian && !isDirect(it) && !isBackOff(it, topic) }
                            .take(params.opportunisticGraftPeers)
                            .forEach { graft(it, topic) }
                    }
                }

                emitGossip(topic, peers)
            }
            fanout.entries.forEach { (topic, peers) ->
                peers.removeIf {
                    it !in getTopicPeers(topic) || score.score(it.peerId) < scoreParams.publishThreshold
                }
                val needMore = params.D - peers.size
                if (needMore > 0) {
                    peers += (getTopicPeers(topic) - peers)
                        .filter { score.score(it.peerId) >= scoreParams.publishThreshold && !isDirect(it) }
                        .shuffled(random)
                        .take(needMore)
                }
                emitGossip(topic, peers)
            }
            lastPublished.entries.removeIf { (topic, lastPub) ->
                (currentTimeSupplier() - lastPub > params.fanoutTTL.toMillis())
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
            .filter { score.score(it.peerId) >= scoreParams.gossipThreshold && !isDirect(it) }

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
            .also { iWantRequests[peer to it] = currentTimeSupplier() }
        enqueueIwant(peer, messageIds)
    }

    private fun enqueuePrune(peer: PeerHandler, topic: Topic) {
        val peerQueue = pendingRpcParts.getQueue(peer)
        if (peer.getPeerProtocol() == PubsubProtocol.Gossip_V_1_1 && this.protocol == PubsubProtocol.Gossip_V_1_1) {
            val backoffPeers = (getTopicPeers(topic) - peer)
                .filter { score.score(it.peerId) >= 0 }
                .map { it.peerId }
            peerQueue.addPrune(topic, params.pruneBackoff.seconds, backoffPeers)
        } else {
            peerQueue.addPrune(topic)
        }
    }

    private fun enqueueGraft(peer: PeerHandler, topic: Topic) =
        pendingRpcParts.getQueue(peer).addGraft(topic)

    private fun enqueueIwant(peer: PeerHandler, messageIds: List<MessageId>) =
        pendingRpcParts.getQueue(peer).addIWants(messageIds)

    private fun enqueueIhave(peer: PeerHandler, messageIds: List<MessageId>) =
        pendingRpcParts.getQueue(peer).addIHaves(messageIds)

    data class AcceptRequestsWhitelistEntry(val whitelistedTill: Long, val messagesAccepted: Int = 0) {
        fun incrementMessageCount() = AcceptRequestsWhitelistEntry(whitelistedTill, messagesAccepted + 1)
    }
}
