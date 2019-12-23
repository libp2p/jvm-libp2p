package io.libp2p.example.chat

import io.libp2p.core.Stream
import io.libp2p.core.multistream.StrictProtocolBinding
import io.libp2p.etc.types.toByteBuf
import io.libp2p.protocol.ProtocolHandler
import io.libp2p.protocol.ProtocolMessageHandler
import io.netty.buffer.ByteBuf
import java.nio.charset.Charset
import java.util.concurrent.CompletableFuture

interface ChatController {
    fun send(message: String)
}

class Chat : ChatBinding(ChatProtocol())

open class ChatBinding(echo: ChatProtocol) : StrictProtocolBinding<ChatController>(echo) {
    override val announce = ChatProtocol.announce
}

open class ChatProtocol : ProtocolHandler<ChatController>() {
    companion object {
        val announce = "/example/chat/0.1.0"
    }

    override fun onStartInitiator(stream: Stream)= onStart(stream)
    override fun onStartResponder(stream: Stream) = onStart(stream)

    private fun onStart(stream: Stream): CompletableFuture<ChatController> {
        val ready = CompletableFuture<Void>()
        val handler = Chatter(ready)
        stream.pushHandler(handler)
        return ready.thenApply { handler }
    }

    open inner class Chatter(val ready: CompletableFuture<Void>) : ProtocolMessageHandler<ByteBuf>, ChatController {
        lateinit var stream: Stream

        override fun onActivated(stream: Stream) {
            this.stream = stream
            ready.complete(null)
        }

        override fun onMessage(stream: Stream, msg: ByteBuf) {
            print(stream.remotePeerId())
            print(" > ")
            println(msg.toString(Charset.defaultCharset()))
        }

        override fun send(message: String) {
            val data = message.toByteArray(Charset.defaultCharset())
            stream.writeAndFlush(data.toByteBuf())
        }
    }
}