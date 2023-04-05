package io.libp2p.tools

import io.libp2p.core.Connection
import io.libp2p.core.PeerId
import io.libp2p.core.Stream
import io.libp2p.core.multistream.MultistreamProtocol
import io.libp2p.core.multistream.MultistreamProtocolV1
import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.etc.PROTOCOL
import io.libp2p.etc.STREAM
import io.libp2p.etc.getP2PChannel
import io.libp2p.etc.types.forward
import io.libp2p.etc.types.toVoidCompletableFuture
import io.libp2p.etc.util.netty.nettyInitializer
import io.libp2p.transport.implementation.P2PChannelOverNetty
import io.netty.channel.Channel
import io.netty.channel.ChannelHandler
import io.netty.channel.EventLoop
import io.netty.channel.embedded.EmbeddedChannel
import java.util.concurrent.CompletableFuture

class TestStreamChannel<TController>(
    initiator: Boolean,
    binding: ProtocolBinding<TController>? = null,
    vararg handlers: ChannelHandler,
    val controllerFuture: CompletableFuture<TController> = CompletableFuture(),
    val multistreamProtocol: MultistreamProtocol = MultistreamProtocolV1
) :
    EmbeddedChannel(
        nettyInitializer {
            it.channel.attr(STREAM).set(TestStream(it.channel, initiator))
        },
        *handlers,
        nettyInitializer {
            if (binding != null) {
                multistreamProtocol.createMultistream(listOf(binding)).initChannel(it.channel.getP2PChannel()).forward(controllerFuture)
            }
        }
    )

private class TestStream(ch: Channel, initiator: Boolean) : P2PChannelOverNetty(ch, initiator), Stream {
    init {
        nettyChannel.attr(PROTOCOL).set(CompletableFuture())
    }

    override fun eventLoop(): EventLoop {
        TODO("Not yet implemented")
    }

    override fun remotePeerId(): PeerId {
        return PeerId(ByteArray(32))
    }

    override val connection: Connection
        get() = TODO()

    override fun getProtocol(): CompletableFuture<String> = nettyChannel.attr(PROTOCOL).get()

    override fun writeAndFlush(msg: Any) {
        nettyChannel.writeAndFlush(msg)
    }

    override fun closeWrite(): CompletableFuture<Unit> =
        nettyChannel.disconnect().toVoidCompletableFuture()
}
