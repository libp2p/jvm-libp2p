package io.libp2p.etc.util

import io.libp2p.core.BadPeerException
import io.libp2p.core.SemiDuplexNoOutboundStreamException
import io.libp2p.etc.types.completedExceptionally
import io.libp2p.etc.types.toVoidCompletableFuture
import java.util.concurrent.CompletableFuture

/**
 * The service where communication between peers is performed via two [io.libp2p.core.Stream]s
 * They are initiated asynchronously by each peer. Initiated stream is used solely for writing data
 * and accepted steam is used solely for reading
 */
abstract class P2PServiceSemiDuplex : P2PService() {

    inner class SDPeerHandler(streamHandler: StreamHandler) : PeerHandler(streamHandler) {

        var otherStreamHandler: StreamHandler? = null

        override fun writeAndFlush(msg: Any): CompletableFuture<Unit> =
            getOutboundHandler()?.ctx?.writeAndFlush(msg)?.toVoidCompletableFuture() ?: completedExceptionally(
                SemiDuplexNoOutboundStreamException("No active outbound stream to write data $msg")
            )

        override fun isActive() = getOutboundHandler()?.ctx != null

        override fun getInboundHandler() = if (streamHandler.stream.isInitiator) otherStreamHandler else streamHandler
        override fun getOutboundHandler() = if (streamHandler.stream.isInitiator) streamHandler else otherStreamHandler
    }

    override fun createPeerHandler(streamHandler: StreamHandler) = SDPeerHandler(streamHandler)

    override fun streamAdded(streamHandler: StreamHandler) {
        val stream = streamHandler.stream
        val peerHandler = peers.find { it.peerId == stream.remotePeerId() }
        if (peerHandler == null) {
            super.streamAdded(streamHandler)
        } else {
            peerHandler as SDPeerHandler
            when {
                peerHandler.otherStreamHandler != null -> {
                    streamHandler.closeAbruptly()
                    throw BadPeerException("Duplicate steam for peer ${peerHandler.peerId}. Closing it silently")
                }
                peerHandler.streamHandler.stream.isInitiator == stream.isInitiator -> {
                    streamHandler.closeAbruptly()
                    throw BadPeerException("Duplicate stream with initiator = ${stream.isInitiator} for peer ${peerHandler.peerId}")
                }
                else -> {
                    peerHandler.otherStreamHandler = streamHandler
                    streamHandler.initPeerHandler(peerHandler)
                }
            }
        }
    }

    override fun streamActive(stream: StreamHandler) {
        if (!stream.aborted && stream == (stream.getPeerHandler() as SDPeerHandler).getOutboundHandler()) {
            // invoke streamActive only when outbound handler is activated
            super.streamActive(stream)
        }
    }

    override fun onPeerDisconnected(peer: PeerHandler) {
        // close stream for the same peer
        peer as SDPeerHandler
        if (!peer.streamHandler.closed) peer.streamHandler.ctx?.close()
        if (peer.otherStreamHandler?.closed == false) peer.otherStreamHandler?.ctx?.close()
    }
}
