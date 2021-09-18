package io.libp2p.tools

import io.libp2p.core.Stream
import io.libp2p.core.multistream.StrictProtocolBinding
import io.libp2p.protocol.ProtocolHandler
import io.libp2p.protocol.ProtocolMessageHandler
import io.netty.buffer.ByteBuf
import java.util.concurrent.CompletableFuture

interface DoNothingController

class DoNothing : DoNothingBinding(DoNothingProtocol())

open class DoNothingBinding(nullProtocol: DoNothingProtocol) :
    StrictProtocolBinding<DoNothingController>("/ipfs/do-nothing/1.0.0", nullProtocol)

class DoNothingProtocol : ProtocolHandler<DoNothingController>(Long.MAX_VALUE, Long.MAX_VALUE) {
    override fun onStartInitiator(stream: Stream) = addDoNothingHandler(stream)
    override fun onStartResponder(stream: Stream) = addDoNothingHandler(stream)

    private fun addDoNothingHandler(stream: Stream): CompletableFuture<DoNothingController> {
        val handler = DoNothingHandler()
        stream.pushHandler(handler)
        return CompletableFuture.completedFuture(handler)
    }

    inner class DoNothingHandler : ProtocolMessageHandler<ByteBuf>, DoNothingController
}
