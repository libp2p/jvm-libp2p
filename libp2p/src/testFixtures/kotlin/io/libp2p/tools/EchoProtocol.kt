package io.libp2p.tools

import io.libp2p.core.Libp2pException
import io.libp2p.core.Stream
import io.libp2p.core.multistream.StrictProtocolBinding
import io.libp2p.etc.types.toByteArray
import io.libp2p.etc.types.toByteBuf
import io.libp2p.protocol.ProtocolHandler
import io.libp2p.protocol.ProtocolMessageHandler
import io.netty.buffer.ByteBuf
import java.nio.charset.Charset
import java.util.concurrent.CompletableFuture

interface EchoController {
    fun echo(message: String): CompletableFuture<String>
}

class Echo : EchoBinding(EchoProtocol())

open class EchoBinding(echo: EchoProtocol) :
    StrictProtocolBinding<EchoController>("/test/echo", echo)

open class EchoProtocol : ProtocolHandler<EchoController>(Long.MAX_VALUE, Long.MAX_VALUE) {
    override fun onStartInitiator(stream: Stream): CompletableFuture<EchoController> {
        val ready = CompletableFuture<Void>()
        val handler = EchoInitiator(ready)
        stream.pushHandler(handler)
        return ready.thenApply { handler }
    }

    override fun onStartResponder(stream: Stream): CompletableFuture<EchoController> {
        val handler = EchoResponder()
        stream.pushHandler(handler)
        return CompletableFuture.completedFuture(handler)
    }

    open inner class EchoResponder : ProtocolMessageHandler<ByteBuf>, EchoController {
        override fun onMessage(stream: Stream, msg: ByteBuf) {
            stream.writeAndFlush(msg.retain())
        }

        override fun echo(message: String): Nothing {
            throw Libp2pException("This is echo responder only")
        }
    }

    open inner class EchoInitiator(val ready: CompletableFuture<Void>) :
        ProtocolMessageHandler<ByteBuf>, EchoController {
        lateinit var stream: Stream
        var ret = CompletableFuture<String>()

        override fun onActivated(stream: Stream) {
            this.stream = stream
            ready.complete(null)
        }

        override fun onMessage(stream: Stream, msg: ByteBuf) {
            val message = String(msg.toByteArray(), Charset.defaultCharset())
            ret.complete(message)
        }

        override fun echo(message: String): CompletableFuture<String> {
            ret = CompletableFuture()

            val data = message.toByteArray(Charset.defaultCharset())
            stream.writeAndFlush(data.toByteBuf())

            return ret
        }
    }
}
