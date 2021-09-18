package io.libp2p.example.chat

import io.libp2p.core.PeerId
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

typealias OnChatMessage = (PeerId, String) -> Unit
class Chat(chatCallback: OnChatMessage) : ChatBinding(ChatProtocol(chatCallback))

open class ChatBinding(echo: ChatProtocol) : StrictProtocolBinding<ChatController>(
  announce = ChatProtocol.announce,
  protocol = echo
)

open class ChatProtocol(
        private val chatCallback : OnChatMessage
) : ProtocolHandler<ChatController>(
  initiatorTrafficLimit = Long.MAX_VALUE,
  responderTrafficLimit = Long.MAX_VALUE
) {
    companion object {
        val announce = "/example/chat/0.1.0"
    }

    override fun onStartInitiator(stream: Stream)= onStart(stream)
    override fun onStartResponder(stream: Stream) = onStart(stream)

    private fun onStart(stream: Stream): CompletableFuture<ChatController> {
        val ready = CompletableFuture<Void>()
        val handler = Chatter(chatCallback, ready)
        stream.pushHandler(handler)
        return ready.thenApply { handler }
    }

    open inner class Chatter(
            private val chatCallback: OnChatMessage,
            val ready: CompletableFuture<Void>
    ) : ProtocolMessageHandler<ByteBuf>, ChatController {
        lateinit var stream: Stream

        override fun onActivated(stream: Stream) {
            this.stream = stream
            ready.complete(null)
        }

        override fun onMessage(stream: Stream, msg: ByteBuf) {
            val msgStr = msg.toString(Charset.defaultCharset())
            chatCallback(stream.remotePeerId(), msgStr)
        }

        override fun send(message: String) {
            val data = message.toByteArray(Charset.defaultCharset())
            stream.writeAndFlush(data.toByteBuf())
        }
    }
}