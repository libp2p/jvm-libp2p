package io.libp2p.core.util

import io.libp2p.core.Stream
import io.libp2p.core.types.toVoidCompletableFuture
import io.libp2p.pubsub.PubsubException
import java.util.concurrent.CompletableFuture

abstract class P2PServiceSemiDuplex : P2PService() {

    inner class SDPeerHandler(streamHandler: StreamHandler) : PeerHandler(streamHandler) {

        var otherStreamHandler: StreamHandler? = null

        override fun writeAndFlush(msg: Any): CompletableFuture<Unit> =
            getOutboundHandler()?.ctx?.writeAndFlush(msg)?.toVoidCompletableFuture() ?: throw PubsubException("No active outbound stream to write data $msg")

        override fun isActive() = getOutboundHandler()?.ctx != null

        fun getInboundHandler() = if (streamHandler.stream.isInitiator) otherStreamHandler else streamHandler
        fun getOutboundHandler() = if (streamHandler.stream.isInitiator) streamHandler else otherStreamHandler
    }

    override fun createPeerHandler(streamHandler: StreamHandler) = SDPeerHandler(streamHandler)

    override fun addNewStream(stream: Stream) {
        runOnEventThread {
            val peerHandler = peers.find { it.peerId() == stream.remotePeerId() }
            if (peerHandler == null) {
                addNewStreamEDT(stream)
            } else {
                peerHandler as SDPeerHandler
                if (peerHandler.otherStreamHandler != null) {
                    logger.warn("Duplicate steam for peer ${peerHandler.peerId()}. Closing it silently")
                    stream.ch.close()
                } else if (peerHandler.streamHandler.stream.isInitiator == stream.isInitiator) {
                    logger.warn("Duplicate stream with initiator = ${stream.isInitiator} for peer ${peerHandler.peerId()}")
                    stream.ch.close()
                } else {
                    val streamHandler = StreamHandler(stream)
                    peerHandler.otherStreamHandler = streamHandler
                    streamHandler.peerHandler = peerHandler
                    initChannel(streamHandler)
                }
            }
        }
    }

    override fun streamActive(stream: StreamHandler) {
        if (stream == (stream.peerHandler as SDPeerHandler).getOutboundHandler()) {
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