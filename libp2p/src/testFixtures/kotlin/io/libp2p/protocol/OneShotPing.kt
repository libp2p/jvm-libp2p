package io.libp2p.protocol

import io.libp2p.core.ConnectionClosedException
import io.libp2p.core.Libp2pException
import io.libp2p.core.Stream
import io.libp2p.core.multistream.StrictProtocolBinding
import io.libp2p.etc.types.toByteBuf
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import java.util.concurrent.CompletableFuture

interface OneShotPingController {
    fun ping(): CompletableFuture<Void>
}

/**
 * Ping responder responds only once when initiator closes the stream for write
 */
class OneShotPing(pingSize: Int) : OneShotPingBinding(OneShotPingProtocol(pingSize)) {
    constructor() : this(32)
}

open class OneShotPingBinding(ping: OneShotPingProtocol) :
    StrictProtocolBinding<OneShotPingController>("/ipfs/one-shot-ping/1.0.0", ping)

open class OneShotPingProtocol(var pingSize: Int) : ProtocolHandler<OneShotPingController>(Long.MAX_VALUE, Long.MAX_VALUE) {

    constructor() : this(32)

    override fun onStartInitiator(stream: Stream): CompletableFuture<OneShotPingController> {
        val handler = OneShotPingInitiator()
        stream.pushHandler(handler)
        return handler.activeFuture
    }

    override fun onStartResponder(stream: Stream): CompletableFuture<OneShotPingController> {
        val handler = OneShotPingResponder()
        stream.pushHandler(handler)
        return CompletableFuture.completedFuture(handler)
    }

    open inner class OneShotPingResponder : ProtocolMessageHandler<ByteBuf>, OneShotPingController {
        lateinit var stream: Stream
        val outBuf = Unpooled.buffer()

        override fun onActivated(stream: Stream) {
            println("OneShotPingResponder: onActivated")
            this.stream = stream
        }

        override fun onMessage(stream: Stream, msg: ByteBuf) {
            println("OneShotPingResponder: onMessage $msg")
            outBuf.writeBytes(msg)
        }

        override fun onReadClosed(stream: Stream) {
            println("OneShotPingResponder: onReadClosed")
            stream.writeAndFlush(outBuf)
            stream.closeWrite()
        }

        override fun onClosed(stream: Stream) {
            println("OneShotPingResponder: onClosed")
        }

        override fun onException(cause: Throwable?) {
            println("OneShotPingResponder: onException: $cause")
        }

        override fun ping(): CompletableFuture<Void> {
            throw Libp2pException("This is ping responder only")
        }
    }

    open inner class OneShotPingInitiator : ProtocolMessageHandler<ByteBuf>, OneShotPingController {
        val activeFuture = CompletableFuture<OneShotPingController>()
        val responseFuture = CompletableFuture<Void>()
        lateinit var stream: Stream
        var closed = false

        override fun onActivated(stream: Stream) {
            println("OneShotPingInitiator: onActivated")
            this.stream = stream
            activeFuture.complete(this)
        }

        override fun onMessage(stream: Stream, msg: ByteBuf) {
            println("OneShotPingInitiator: onMessage $msg")
            responseFuture.complete(null)
        }

        override fun onReadClosed(stream: Stream) {
            println("OneShotPingInitiator: onReadClosed")
        }

        override fun onClosed(stream: Stream) {
            println("OneShotPingInitiator: onClosed")
            activeFuture.completeExceptionally(ConnectionClosedException())
        }

        override fun onException(cause: Throwable?) {
            println("OneShotPingInitiator: onException: $cause")
        }

        override fun ping(): CompletableFuture<Void> {
            println("OneShotPingInitiator: ping")
            val data = ByteArray(pingSize)
            stream.writeAndFlush(data.toByteBuf())
            stream.closeWrite()
            return responseFuture
        }
    }
}
