package io.libp2p.mux.yamux

import io.libp2p.core.StreamHandler
import io.libp2p.core.multistream.MultistreamProtocolV1
import io.libp2p.etc.types.fromHex
import io.libp2p.etc.types.toHex
import io.libp2p.etc.util.netty.mux.MuxId
import io.libp2p.mux.MuxHandler
import io.libp2p.mux.MuxHandlerAbstractTest
import io.libp2p.mux.MuxHandlerAbstractTest.AbstractTestMuxFrame.Flag.*
import io.libp2p.tools.readAllBytesAndRelease
import io.netty.channel.ChannelHandlerContext

class YamuxHandlerTest : MuxHandlerAbstractTest() {

    override val maxFrameDataLength = 256
    private val readFrameQueue = ArrayDeque<AbstractTestMuxFrame>()

    override fun createMuxHandler(streamHandler: StreamHandler<*>): MuxHandler =
        object : YamuxHandler(
            MultistreamProtocolV1, maxFrameDataLength, null, streamHandler, true
        ) {
            // MuxHandler consumes the exception. Override this behaviour for testing
            @Deprecated("Deprecated in Java")
            override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                ctx.fireExceptionCaught(cause)
            }
        }

    override fun writeFrame(frame: AbstractTestMuxFrame) {
        val muxId = MuxId(parentChannelId, frame.streamId, true)
        val yamuxFrame = when (frame.flag) {
            Open -> YamuxFrame(muxId, YamuxType.DATA, YamuxFlags.SYN, 0)
            Data -> YamuxFrame(
                muxId,
                YamuxType.DATA,
                0,
                frame.data.fromHex().size.toLong(),
                frame.data.fromHex().toByteBuf(allocateBuf())
            )
            Close -> YamuxFrame(muxId, YamuxType.DATA, YamuxFlags.FIN, 0)
            Reset -> YamuxFrame(muxId, YamuxType.DATA, YamuxFlags.RST, 0)
        }
        ech.writeInbound(yamuxFrame)
    }

    override fun readFrame(): AbstractTestMuxFrame? {
        val yamuxFrame = ech.readOutbound<YamuxFrame>()
        if (yamuxFrame != null) {
            when (yamuxFrame.flags) {
                YamuxFlags.SYN -> readFrameQueue += AbstractTestMuxFrame(yamuxFrame.id.id, Open)
            }

            val data = yamuxFrame.data?.readAllBytesAndRelease()?.toHex() ?: ""
            when {
                yamuxFrame.type == YamuxType.DATA && data.isNotEmpty() ->
                    readFrameQueue += AbstractTestMuxFrame(yamuxFrame.id.id, Data, data)
            }

            when (yamuxFrame.flags) {
                YamuxFlags.FIN -> readFrameQueue += AbstractTestMuxFrame(yamuxFrame.id.id, Close)
                YamuxFlags.RST -> readFrameQueue += AbstractTestMuxFrame(yamuxFrame.id.id, Reset)
            }
        }

        return readFrameQueue.removeFirstOrNull()
    }
}
