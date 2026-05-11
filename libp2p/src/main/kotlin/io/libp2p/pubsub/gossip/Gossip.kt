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
import io.libp2p.pubsub.Topic
import io.libp2p.pubsub.gossip.builders.GossipRouterBuilder
import io.libp2p.pubsub.gossip.partialmessages.PublishActionsFn
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

    fun updateTopicScoreParams(scoreParams: Map<String, GossipTopicScoreParams>) {
        router.score.updateTopicParams(scoreParams)
    }

    fun getGossipScore(peerId: PeerId): Double {
        return router.score.getCachedScore(peerId)
    }

    /**
     * Queues outbound [pubsub.pb.Rpc.PartialMessagesExtension] RPCs for [topic]/[groupId]
     * by invoking the client's [actionsFn] on the current group state.
     *
     * Submits to the pubsub event thread; the returned future completes when the RPCs
     * have been enqueued and flushed.
     */
    fun publishPartial(
        topic: Topic,
        groupId: ByteArray,
        actionsFn: PublishActionsFn<*>
    ): CompletableFuture<Unit> =
        router.submitOnEventThread { router.publishPartial(topic, groupId, actionsFn) }

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
        conn.muxerSession().createStream(listOf(this))
    }

    override fun initChannel(ch: P2PChannel, selectedProtocol: String): CompletableFuture<out Unit> {
        logger.trace("Gossip initChannel - selected protocol: {}", selectedProtocol)
        router.addPeerWithDebugHandler(ch as Stream, debugGossipHandler)
        return CompletableFuture.completedFuture(Unit)
    }
}
