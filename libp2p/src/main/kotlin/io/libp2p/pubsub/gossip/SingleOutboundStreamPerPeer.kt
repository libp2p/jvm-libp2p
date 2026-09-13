package io.libp2p.pubsub.gossip

import io.libp2p.core.Connection
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.PeerId
import io.libp2p.core.Stream
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture

/**
 * Maintains a single outbound [Stream] per remote peer id, whatever number of [Connection]s
 * there is to that peer.
 *
 * libp2p may hold more than one connection to the same peer - most commonly when both sides dial
 * each other at the same time - while gossipsub, like go-libp2p-pubsub, expects a single outbound
 * stream per peer id. Opening a stream on every connection makes both peers see same-direction
 * duplicates and drop them, which leaves the peering permanently unable to write.
 *
 * [handleConnection] opens a stream when this is the first connection to the peer. Every stream
 * opened is then watched and reopened over any connection to the peer which is still alive, be it
 * closed together with the connection carrying it or reset by the remote peer.
 *
 * The [streamOpener] future is expected to complete only when the stream is ready to be used, so
 * that a peer not supporting the protocol doesn't make us reopen the stream over and over.
 *
 * Thread safe.
 */
class SingleOutboundStreamPerPeer(
    private val streamOpener: (Connection) -> CompletableFuture<Stream>
) : ConnectionHandler {

    private val connectionsByPeer = mutableMapOf<PeerId, MutableList<Connection>>()

    override fun handleConnection(conn: Connection) {
        val peerId = conn.remotePeerId
        val isFirstConnection = synchronized(connectionsByPeer) {
            val connections = connectionsByPeer.getOrPut(peerId) { mutableListOf() }
            connections += conn
            connections.size == 1
        }
        conn.closeFuture().thenRun { forgetConnection(peerId, conn) }
        if (isFirstConnection) {
            openStream(conn)
        }
    }

    private fun forgetConnection(peerId: PeerId, conn: Connection) {
        synchronized(connectionsByPeer) {
            val connections = connectionsByPeer[peerId] ?: return
            connections -= conn
            if (connections.isEmpty()) {
                connectionsByPeer -= peerId
            }
        }
    }

    private fun reopenStream(peerId: PeerId) {
        val conn = aliveConnection(peerId) ?: return
        logger.debug("Reopening the outbound stream to {}", peerId)
        openStream(conn)
    }

    private fun aliveConnection(peerId: PeerId): Connection? =
        synchronized(connectionsByPeer) {
            connectionsByPeer[peerId]?.firstOrNull { !it.closeFuture().isDone }
        }

    private fun openStream(conn: Connection) {
        streamOpener(conn).whenComplete { stream, err ->
            if (err != null) {
                logger.debug("Couldn't open an outbound stream to {}", conn.remotePeerId, err)
            } else {
                stream.closeFuture().thenRun { reopenStream(conn.remotePeerId) }
            }
        }
    }

    private val Connection.remotePeerId: PeerId get() = secureSession().remoteId

    companion object {
        private val logger = LoggerFactory.getLogger(SingleOutboundStreamPerPeer::class.java)
    }
}
