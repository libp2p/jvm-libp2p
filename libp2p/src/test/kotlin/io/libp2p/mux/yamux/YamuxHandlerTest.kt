package io.libp2p.mux.yamux

import io.libp2p.core.StreamHandler
import io.libp2p.core.multistream.MultistreamProtocolV1
import io.libp2p.etc.types.fromHex
import io.libp2p.etc.types.toByteBuf
import io.libp2p.etc.util.netty.mux.MuxId
import io.libp2p.mux.MuxHandler
import io.libp2p.mux.MuxHandlerAbstractTest
import io.netty.channel.ChannelHandlerContext

class YamuxHandlerTest : MuxHandlerAbstractTest() {

    override val maxFrameDataLength = 256

    override fun createMuxHandler(streamHandler: StreamHandler<Unit>): MuxHandler =
        object : YamuxHandler(
            MultistreamProtocolV1, maxFrameDataLength, null, streamHandler, true
        ) {
            // MuxHandler consumes the exception. Override this behaviour for testing
            @Deprecated("Deprecated in Java")
            override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                ctx.fireExceptionCaught(cause)
            }
        }

    override fun openStream(id: Long) =
        ech.writeInbound(YamuxFrame(MuxId(dummyParentChannelId, id, true), YamuxType.DATA, YamuxFlags.SYN, 0))

    override fun writeStream(id: Long, msg: String) =
        ech.writeInbound(
            YamuxFrame(
                MuxId(dummyParentChannelId, id, true),
                YamuxType.DATA,
                0,
                msg.fromHex().size.toLong(),
                msg.fromHex().toByteBuf(allocateBuf())
            )
        )

    override fun resetStream(id: Long) =
        ech.writeInbound(YamuxFrame(MuxId(dummyParentChannelId, id, true), YamuxType.GO_AWAY, 0, 0))
}
