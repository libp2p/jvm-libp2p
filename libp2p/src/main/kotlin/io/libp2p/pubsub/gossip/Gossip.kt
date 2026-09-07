package io.libp2p.pubsub.gossip

import io.libp2p.core.Connection
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.P2PChannel
import io.libp2p.core.PeerId
import io.libp2p.core.Stream
import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.core.multistream.ProtocolDescriptor
import io.libp2p.core.pubsub.PubsubApi
import io.libp2p.pubsub.PubsubApiImpl
import io.libp2p.pubsub.PubsubProtocol
import io.libp2p.pubsub.gossip.builders.GossipRouterBuilder
import io.netty.channel.ChannelHandler
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture

class Gossip @JvmOverloads constructor(
    private val router: GossipRouter = GossipRouterBuilder().build(),
    private val api: PubsubApi = PubsubApiImpl(router),
    private val debugGossipHandler: ChannelHandler? = null
) :
    ProtocolBinding<Unit>, ConnectionHandler, PubsubApi by api {

    private val logger = LoggerFactory.getLogger(Gossip::class.java)

    /**
     * Live connections per peer.
     *
     * libp2p allows more than one connection to the same peer - most commonly when both sides dial
     * each other at the same time - while gossipsub, like go-libp2p-pubsub, keeps a single outbound
     * stream per peer id. Opening a stream on every connection makes both peers see same-direction
     * duplicates and drop them, which leaves the peering permanently unable to write. So the stream
     * is opened on one connection only and reopened on another one when that connection goes away.
     */
    private val peerConnections = mutableMapOf<PeerId, MutableList<Connection>>()

    fun updateTopicScoreParams(scoreParams: Map<String, GossipTopicScoreParams>) {
        router.score.updateTopicParams(scoreParams)
    }

    fun getGossipScore(peerId: PeerId): Double {
        return router.score.getCachedScore(peerId)
    }

    override val protocolDescriptor =
        when (router.protocol) {
            PubsubProtocol.Gossip_V_1_3 -> {
                ProtocolDescriptor(
                    PubsubProtocol.Gossip_V_1_3.announceStr,
                    PubsubProtocol.Gossip_V_1_2.announceStr,
                    PubsubProtocol.Gossip_V_1_1.announceStr,
                    PubsubProtocol.Gossip_V_1_0.announceStr
                )
            }
            PubsubProtocol.Gossip_V_1_2 -> {
                ProtocolDescriptor(
                    PubsubProtocol.Gossip_V_1_2.announceStr,
                    PubsubProtocol.Gossip_V_1_1.announceStr,
                    PubsubProtocol.Gossip_V_1_0.announceStr
                )
            }
            PubsubProtocol.Gossip_V_1_1 -> {
                ProtocolDescriptor(
                    PubsubProtocol.Gossip_V_1_1.announceStr,
                    PubsubProtocol.Gossip_V_1_0.announceStr
                )
            }
            else -> {
                ProtocolDescriptor(PubsubProtocol.Gossip_V_1_0.announceStr)
            }
        }

    override fun handleConnection(conn: Connection) {
        val peerId = conn.secureSession().remoteId
        val isOnlyConnection = synchronized(peerConnections) {
            val connections = peerConnections.getOrPut(peerId) { mutableListOf() }
            connections += conn
            connections.size == 1
        }
        conn.closeFuture().thenRun {
            synchronized(peerConnections) {
                peerConnections[peerId]?.also { it -= conn }?.takeIf { it.isEmpty() }
                    ?.also { peerConnections -= peerId }
            }
        }
        // Gossipsub keeps a single outbound stream per peer, so a second connection to a peer
        // we already opened a stream to doesn't get one of its own.
        if (isOnlyConnection) {
            createStream(conn)
        }
    }

    override fun initChannel(ch: P2PChannel, selectedProtocol: String): CompletableFuture<out Unit> {
        logger.trace("Gossip initChannel - selected protocol: {}", selectedProtocol)
        val stream = ch as Stream
        router.addPeerWithDebugHandler(stream, debugGossipHandler)
        if (stream.isInitiator) {
            stream.closeFuture().thenRun { reopenStream(stream.remotePeerId()) }
        }
        return CompletableFuture.completedFuture(Unit)
    }

    /**
     * Reopens our outbound stream to [peerId] if we are still connected to it. This is what keeps
     * the peering alive when the connection carrying the stream is closed while another connection
     * to the same peer remains, and when the peer resets the stream on its side.
     */
    private fun reopenStream(peerId: PeerId) {
        val conn = synchronized(peerConnections) {
            peerConnections[peerId]?.firstOrNull { !it.closeFuture().isDone }
        } ?: return
        logger.debug("Reopening the gossip stream to {}", peerId)
        createStream(conn)
    }

    private fun createStream(conn: Connection) {
        conn.muxerSession().createStream(listOf(this)).stream.whenComplete { _, err ->
            if (err != null) {
                logger.debug("Couldn't open a gossip stream to {}", conn.secureSession().remoteId, err)
            }
        }
    }
}
