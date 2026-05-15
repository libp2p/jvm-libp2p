package io.libp2p.pubsub

import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.ByteToMessageDecoder
import org.slf4j.LoggerFactory

/**
 * Pre-decode count cap for inbound pubsub RPC frames. Sits between
 * [io.libp2p.etc.util.netty.protobuf.LimitedProtobufVarint32FrameDecoder] (byte-size
 * cap) and [io.netty.handler.codec.protobuf.ProtobufDecoder] (materialisation).
 *
 * For each frame, delegates to [RpcMessageCountValidator]. Accepted frames are
 * forwarded unchanged as a `ByteBuf` to the next handler. Rejected frames are
 * dropped with a debug log; no `Rpc$Message` is allocated for them.
 *
 * When [limits] has every count null and `rejectEmptyPublishEntries == false`
 * (e.g. [PubsubRpcLimits.NONE]) the validator walks the buffer once and accepts
 * every frame — there is no special-cased fast-path.
 */
class RpcCountFrameDecoder(private val limits: PubsubRpcLimits) : ByteToMessageDecoder() {

    override fun decode(ctx: ChannelHandlerContext, msg: ByteBuf, out: MutableList<Any>) {
        val readable = msg.readableBytes()
        if (readable == 0) return

        val result = try {
            RpcMessageCountValidator.validate(msg, limits)
        } catch (e: Exception) {
            logger.debug("Dropping pubsub RPC frame due to unexpected validator error", e)
            msg.skipBytes(readable)
            return
        }

        when (result) {
            RpcMessageCountValidator.Result.Accepted -> {
                out.add(msg.readRetainedSlice(readable))
            }
            is RpcMessageCountValidator.Result.Rejected -> {
                logger.debug("Dropping pubsub RPC frame: {}", result.reason)
                msg.skipBytes(readable)
            }
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(RpcCountFrameDecoder::class.java)
    }
}
