package io.libp2p.mux.mplex

import io.libp2p.core.StreamHandler
import io.libp2p.core.multistream.MultistreamProtocolV1
import io.libp2p.etc.types.fromHex
import io.libp2p.etc.types.toByteBuf
import io.libp2p.etc.util.netty.mux.MuxId
import io.libp2p.mux.MuxHandler
import io.libp2p.mux.MuxHandlerAbstractTest
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext

class MplexHandlerTest : MuxHandlerAbstractTest() {

    override fun createMuxHandler(streamHandler: StreamHandler<Unit>): MuxHandler =
        object : MplexHandler(
            MultistreamProtocolV1, DEFAULT_MAX_MPLEX_FRAME_DATA_LENGTH, null, streamHandler
        ) {
            // MuxHandler consumes the exception. Override this behaviour for testing
            @Deprecated("Deprecated in Java")
            override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                ctx.fireExceptionCaught(cause)
            }
        }

    override fun openStream(id: Long) = writeFrame(id, MplexFlag.Type.OPEN)
    override fun writeStream(id: Long, msg: String) = writeFrame(id, MplexFlag.Type.DATA, msg.fromHex().toByteBuf())
    override fun resetStream(id: Long) = writeFrame(id, MplexFlag.Type.RESET)
    fun writeFrame(id: Long, flagType: MplexFlag.Type, data: ByteBuf = Unpooled.EMPTY_BUFFER) =
        ech.writeInbound(MplexFrame(MuxId(dummyParentChannelId, id, true), MplexFlag.getByType(flagType, true), data))
}
