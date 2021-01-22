package io.libp2p.pubsub.gossip

import io.libp2p.core.Connection
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.P2PChannel
import io.libp2p.core.Stream
import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.core.multistream.ProtocolDescriptor
import io.libp2p.core.pubsub.PubsubApi
import io.libp2p.pubsub.PubsubApiImpl
import io.libp2p.pubsub.PubsubProtocol
import io.netty.channel.ChannelHandler
import java.util.concurrent.CompletableFuture

class Gossip @JvmOverloads constructor(
    private val router: GossipRouter = GossipRouter(),
    private val api: PubsubApi = PubsubApiImpl(router),
    private val debugGossipHandler: ChannelHandler? = null
) :
    ProtocolBinding<Unit>, ConnectionHandler, PubsubApi by api {

    fun updateTopicScoreParams(scoreParams: Map<String, GossipTopicScoreParams>) {
        router.score.updateTopicParams(scoreParams)
    }

    override val protocolDescriptor =
        if (router.protocol == PubsubProtocol.Gossip_V_1_1)
            ProtocolDescriptor(
                PubsubProtocol.Gossip_V_1_1.announceStr,
                PubsubProtocol.Gossip_V_1_0.announceStr
            )
        else
            ProtocolDescriptor(PubsubProtocol.Gossip_V_1_0.announceStr)

    override fun handleConnection(conn: Connection) {
        conn.muxerSession().createStream(listOf(this))
    }

    override fun initChannel(ch: P2PChannel, selectedProtocol: String): CompletableFuture<out Unit> {
        router.addPeerWithDebugHandler(ch as Stream, debugGossipHandler)
        return CompletableFuture.completedFuture(Unit)
    }
}
