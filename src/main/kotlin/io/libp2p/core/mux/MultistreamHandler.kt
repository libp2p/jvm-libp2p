package io.libp2p.core.mux

import io.libp2p.core.util.netty.multiplex.MultiplexChannel
import io.libp2p.core.util.netty.multiplex.MultiplexHandler
import io.libp2p.core.util.netty.multiplex.MultiplexId
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelHandlerContext
import java.util.Random
import java.util.concurrent.atomic.AtomicLong

class MultistreamHandler(inboundInitializer: ChannelHandler) : MultiplexHandler<ByteBuf>(inboundInitializer) {

    private val idGenerator = AtomicLong(Random().nextLong())

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        msg as MultistreamFrame
        when (msg.flag) {
            MultistreamFrame.Flag.OPEN -> onRemoteOpen(msg.id)
            MultistreamFrame.Flag.CLOSE -> onRemoteDisconnect(msg.id)
            MultistreamFrame.Flag.RESET -> onRemoteClose(msg.id)
            MultistreamFrame.Flag.DATA -> childRead(msg.id, msg.data!!)
        }
    }

    override fun onChildWrite(child: MultiplexChannel<ByteBuf>, data: ByteBuf): Boolean {
        getChannelHandlerContext().writeAndFlush(MultistreamFrame(child.id, MultistreamFrame.Flag.DATA, data))
        return true
    }

    override fun onLocalOpen(child: MultiplexChannel<ByteBuf>) {
        getChannelHandlerContext().writeAndFlush(MultistreamFrame(child.id, MultistreamFrame.Flag.OPEN))
    }

    override fun onLocalDisconnect(child: MultiplexChannel<ByteBuf>) {
        getChannelHandlerContext().writeAndFlush(MultistreamFrame(child.id, MultistreamFrame.Flag.CLOSE))
    }

    override fun onLocalClose(child: MultiplexChannel<ByteBuf>) {
        getChannelHandlerContext().writeAndFlush(MultistreamFrame(child.id, MultistreamFrame.Flag.RESET))
    }

    override fun generateNextId() = MultiplexId(idGenerator.incrementAndGet())
}