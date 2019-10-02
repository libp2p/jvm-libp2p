package io.libp2p.protocol

import io.libp2p.core.Stream
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.util.ReferenceCounted

class ProtocolMessageHandlerAdapter<TMessage>(
    private val stream: Stream,
    private val pmh: ProtocolMessageHandler<TMessage>
) : SimpleChannelInboundHandler<ByteBuf>() { // This ByteBuf is a horrible hack just to get me going for now
    override fun channelActive(ctx: ChannelHandlerContext) {
        super.channelActive(ctx)
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        var release = true
        var ref = -1
        try {
            if (acceptInboundMessage(msg)) {
                ref = refCount(msg)
                @Suppress("UNCHECKED_CAST")
                channelRead0(ctx, msg as ByteBuf)
            } else {
                release = false
                ctx.fireChannelRead(msg)
            }
        } finally {
            if (release) {
                checkedRelease(ref, msg)
            }
        }
    }

    override fun channelRead0(ctx: ChannelHandlerContext?, msg: ByteBuf) {
        pmh.onMessage(stream, msg as TMessage)
    }

    override fun channelUnregistered(ctx: ChannelHandlerContext?) {
        super.channelUnregistered(ctx)
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext?, cause: Throwable?) {
        super.exceptionCaught(ctx, cause)
    }

    // ///////////////////////
    private fun refCount(obj: Any): Int {
        return if (obj is ReferenceCounted) {
            obj.refCnt()
        } else {
            -1
        }
    }

    private fun checkedRelease(count: Int, obj: Any) {
        if (count == -1)
            return
        val rc = obj as ReferenceCounted
        if (count == rc.refCnt())
            rc.release()
    }
}