package io.libp2p.core

import io.netty.channel.Channel
import java.util.concurrent.CompletableFuture

class Stream(ch: Channel, val conn: Connection) : P2PAbstractChannel(ch) {

    init {
        nettyChannel.attr(PROTOCOL).set(CompletableFuture())
    }

    fun remotePeerId() = conn.secureSession.remoteId

    /**
     * @return negotiated protocol
     */
    fun getProtocol(): CompletableFuture<String> = nettyChannel.attr(PROTOCOL).get()
}