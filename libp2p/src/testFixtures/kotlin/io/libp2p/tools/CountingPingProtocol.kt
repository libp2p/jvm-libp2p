package io.libp2p.tools

import io.libp2p.core.Stream
import io.libp2p.protocol.PingController
import io.libp2p.protocol.PingProtocol
import io.netty.buffer.ByteBuf
import java.util.concurrent.CompletableFuture

class CountingPingProtocol : PingProtocol() {
    var pingsReceived: Int = 0

    override fun onStartResponder(stream: Stream): CompletableFuture<PingController> {
        val handler = CountingPingResponderChannelHandler()
        stream.pushHandler(handler)
        return CompletableFuture.completedFuture(handler)
    }

    inner class CountingPingResponderChannelHandler : PingResponder() {
        override fun onMessage(stream: Stream, msg: ByteBuf) {
            ++pingsReceived
            super.onMessage(stream, msg)
        }
    }
}
