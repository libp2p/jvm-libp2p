package io.libp2p.mux

import io.libp2p.core.Stream
import io.libp2p.core.StreamHandler
import io.libp2p.core.StreamPromise
import io.libp2p.core.multistream.MultistreamProtocol
import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.core.mux.StreamMuxer
import io.libp2p.etc.CONNECTION
import io.libp2p.etc.STREAM
import io.libp2p.etc.types.forward
import io.libp2p.etc.util.netty.mux.AbstractMuxHandler
import io.libp2p.etc.util.netty.mux.MuxChannel
import io.libp2p.etc.util.netty.mux.MuxChannelInitializer
import io.libp2p.transport.implementation.StreamOverNetty
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import java.util.concurrent.CompletableFuture

abstract class MuxHandler(
    private val ready: CompletableFuture<StreamMuxer.Session>?,
    inboundStreamHandler: StreamHandler<*>
) : AbstractMuxHandler<ByteBuf>(), StreamMuxer.Session {

    protected abstract val multistreamProtocol: MultistreamProtocol
    protected abstract val maxFrameDataLength: Int

    override val inboundInitializer: MuxChannelInitializer<ByteBuf> = {
        inboundStreamHandler.handleStream(createStream(it))
    }

    override fun handlerAdded(ctx: ChannelHandlerContext) {
        super.handlerAdded(ctx)
        ready?.complete(this)
    }

    private fun createStream(channel: MuxChannel<ByteBuf>): Stream {
        val connection = ctx!!.channel().attr(CONNECTION).get()
        val stream = StreamOverNetty(channel, connection, channel.initiator)
        channel.attr(STREAM).set(stream)
        return stream
    }

    override fun <T> createStream(protocols: List<ProtocolBinding<T>>): StreamPromise<T> {
        return createStream(multistreamProtocol.createMultistream(protocols).toStreamHandler())
    }

    fun <T> createStream(streamHandler: StreamHandler<T>): StreamPromise<T> {
        val controller = CompletableFuture<T>()
        val stream = newStream {
            streamHandler.handleStream(createStream(it)).forward(controller)
        }.thenApply { it.attr(STREAM).get() }
        return StreamPromise(stream, controller)
    }

    override fun releaseMessage(msg: ByteBuf) {
        msg.release()
    }
}
