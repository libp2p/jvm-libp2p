package io.libp2p.protocol

import io.libp2p.core.Stream
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.util.ReferenceCounted

class ProtocolMessageHandlerAdapter<TMessage>(
    private val stream: Stream,
    private val pmh: ProtocolMessageHandler<TMessage>
) : SimpleChannelInboundHandler<Any>() {
    override fun channelActive(ctx: ChannelHandlerContext) {
        pmh.onActivated(stream)
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        var release = true
        var ref = -1
        try {
            if (acceptInboundMessage(msg)) {
                ref = refCount(msg)
                @Suppress("UNCHECKED_CAST")
                channelRead0(ctx, msg)
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

    override fun channelRead0(ctx: ChannelHandlerContext?, msg: Any) {
        pmh.fireMessage(stream, msg)
    }

    override fun channelUnregistered(ctx: ChannelHandlerContext?) {
        pmh.onClosed(stream)
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext?, cause: Throwable?) {
        pmh.onException(cause)
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
