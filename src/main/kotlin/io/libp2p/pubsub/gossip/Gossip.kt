package io.libp2p.pubsub.gossip

import io.libp2p.core.Connection
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.P2PAbstractChannel
import io.libp2p.core.P2PAbstractHandler
import io.libp2p.core.Stream
import io.libp2p.core.multistream.Mode
import io.libp2p.core.multistream.Multistream
import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.core.multistream.ProtocolMatcher
import io.libp2p.pubsub.PubsubApi
import io.libp2p.pubsub.PubsubApiImpl
import io.netty.channel.ChannelHandler
import java.util.concurrent.CompletableFuture

class Gossip(
    val router: GossipRouter = GossipRouter(),
    val api: PubsubApi = PubsubApiImpl(router),
    val debugGossipHandler: ChannelHandler? = null
) :
    ProtocolBinding<Unit>, ConnectionHandler, PubsubApi by api {

    override val announce = "/meshsub/1.0.0"
    override val matcher = ProtocolMatcher(Mode.STRICT, announce)

    override fun handleConnection(conn: Connection) {
        conn.muxerSession.createStream(Multistream.create(listOf(this)))
    }

    override fun initializer(selectedProtocol: String): P2PAbstractHandler<Unit> {
        return object : P2PAbstractHandler<Unit> {
            override fun initChannel(ch: P2PAbstractChannel): CompletableFuture<out Unit> {
                router.addPeerWithDebugHandler(ch as Stream, debugGossipHandler)
                return CompletableFuture.completedFuture(Unit)
            }
        }
    }
}