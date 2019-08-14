package io.libp2p.core

import io.netty.channel.Channel

class Stream(ch: Channel, val conn: Connection) : P2PAbstractChannel(ch) {
    fun remotePeerId() = conn.secureSession.get().remoteId
}