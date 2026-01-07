package io.libp2p.pubsub.gossip

import io.libp2p.core.Connection
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.P2PChannel
import io.libp2p.core.PeerId
import io.libp2p.core.Stream
import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.core.multistream.ProtocolDescriptor
import io.libp2p.core.pubsub.PubsubApi
import io.libp2p.core.pubsub.Topic
import io.libp2p.pubsub.PubsubApiImpl
import io.libp2p.pubsub.PubsubProtocol
import io.libp2p.pubsub.gossip.builders.GossipRouterBuilder
import io.libp2p.pubsub.partial.PartialMessage
import io.libp2p.pubsub.partial.PartialPublishOptions
import io.netty.channel.ChannelHandler
import java.util.concurrent.CompletableFuture

class Gossip @JvmOverloads constructor(
    private val router: GossipRouter = GossipRouterBuilder().build(),
    private val api: PubsubApi = PubsubApiImpl(router),
    private val debugGossipHandler: ChannelHandler? = null
) :
    ProtocolBinding<Unit>, ConnectionHandler, PubsubApi by api {

    fun updateTopicScoreParams(scoreParams: Map<String, GossipTopicScoreParams>) {
        router.score.updateTopicParams(scoreParams)
    }

    fun getGossipScore(peerId: PeerId): Double {
        return router.score.getCachedScore(peerId)
    }

    /**
     * Subscribes to a topic with partial message support.
     * This sends the subscription with requestsPartial=true flag to peers.
     *
     * @param topics The topics to subscribe to with partial message support
     */
    fun subscribePartial(vararg topics: Topic) {
        router.subscribePartial(*topics.map { it.topic }.toTypedArray())
    }

    /**
     * Publishes a partial message to mesh peers.
     *
     * @param message The partial message to publish
     * @param topic The topic to publish to
     * @param opts Publishing options
     * @return CompletableFuture completing when sent to at least one peer
     */
    @JvmOverloads
    fun publishPartial(
        message: PartialMessage,
        topic: Topic,
        opts: PartialPublishOptions = PartialPublishOptions()
    ): CompletableFuture<Unit> {
        return router.publishPartial(topic.topic, message, opts)
    }

    override val protocolDescriptor =
        when (router.protocol) {
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
        router.addPeerWithDebugHandler(ch as Stream, debugGossipHandler)
        return CompletableFuture.completedFuture(Unit)
    }
}
