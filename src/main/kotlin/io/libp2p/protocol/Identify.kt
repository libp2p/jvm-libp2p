package io.libp2p.protocol

import identify.pb.IdentifyOuterClass
import io.libp2p.core.ConnectionClosedException
import io.libp2p.core.Libp2pException
import io.libp2p.core.Stream
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multistream.StrictProtocolBinding
import io.libp2p.etc.types.toProtobuf
import java.util.concurrent.CompletableFuture

interface IdentifyController {
    fun id(): CompletableFuture<IdentifyOuterClass.Identify>
}

class Identify(idMessage: IdentifyOuterClass.Identify? = null) : IdentifyBinding(IdentifyProtocol(idMessage))

open class IdentifyBinding(override val protocol: IdentifyProtocol) : StrictProtocolBinding<IdentifyController>(protocol) {
    override val announce = "/ipfs/id/1.0.0"
}

class IdentifyProtocol(var idMessage: IdentifyOuterClass.Identify? = null) :
    ProtobufProtocolHandler<IdentifyController>(IdentifyOuterClass.Identify.getDefaultInstance()) {

    override fun onStartInitiator(stream: Stream): CompletableFuture<IdentifyController> {
        val handler = IdentifyRequesterChannelHandler()
        stream.pushHandler(handler)
        return CompletableFuture.completedFuture(handler)
    }

    override fun onStartResponder(stream: Stream): CompletableFuture<IdentifyController> {
        val handler = IdentifyResponderChannelHandler(stream.connection.remoteAddress())
        stream.pushHandler(handler)
        return CompletableFuture.completedFuture(handler)
    }

    interface IdentifyHandler : ProtocolMessageHandler<IdentifyOuterClass.Identify>, IdentifyController

    inner class IdentifyRequesterChannelHandler : IdentifyHandler {
        private val resp = CompletableFuture<IdentifyOuterClass.Identify>()

        override fun onMessage(stream: Stream, msg: IdentifyOuterClass.Identify) {
            resp.complete(msg)
        }

        override fun onClosed(stream: Stream) {
            resp.completeExceptionally(ConnectionClosedException())
        }

        override fun onException(cause: Throwable?) {
            resp.completeExceptionally(cause)
        }

        override fun id(): CompletableFuture<IdentifyOuterClass.Identify> = resp
    }

    inner class IdentifyResponderChannelHandler(val remoteAddr: Multiaddr) : IdentifyHandler {
        override fun onActivated(stream: Stream) {
            val msg = idMessage ?: IdentifyOuterClass.Identify.newBuilder()
                .setAgentVersion("jvm/0.1")
                .build()

            val msgWithAddr = msg.toBuilder()
                .setObservedAddr(remoteAddr.getBytes().toProtobuf())
                .build()

            stream.writeAndFlush(msgWithAddr)
            stream.close()
        }

        override fun id(): CompletableFuture<IdentifyOuterClass.Identify> {
            throw Libp2pException("This is Identify responder only")
        }
    }
}