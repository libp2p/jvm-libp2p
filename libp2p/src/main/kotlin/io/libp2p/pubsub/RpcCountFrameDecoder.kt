package io.libp2p.pubsub

import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.CorruptedFrameException
import io.netty.handler.codec.MessageToMessageDecoder
import org.slf4j.LoggerFactory

/**
 * Pre-decode count cap for inbound pubsub RPC frames. Sits between
 * [io.libp2p.etc.util.netty.protobuf.LimitedProtobufVarint32FrameDecoder] (byte-size
 * cap) and [io.netty.handler.codec.protobuf.ProtobufDecoder] (materialisation).
 *
 * For each frame, delegates to [RpcMessageCountValidator]. Accepted frames are
 * forwarded unchanged as a `ByteBuf` to the next handler. Frames rejected because
 * a configured count limit was exceeded are dropped with a debug log; no
 * `Rpc$Message` is allocated for them. Frames rejected because the protobuf bytes
 * themselves are malformed propagate a [CorruptedFrameException] so that
 * downstream handlers (e.g. [io.libp2p.pubsub.AbstractRouter.onPeerWireException])
 * can apply the same behaviour penalty they would have on a [ProtobufDecoder]
 * failure.
 *
 * When [limits] is a no-op (see [PubsubRpcLimits.isNoop], e.g. [PubsubRpcLimits.NONE])
 * the validator is skipped entirely and the buffer is forwarded as-is. Malformed
 * bytes still surface downstream from [ProtobufDecoder], which already triggers
 * the same wire-exception path the validator would have used.
 */
class RpcCountFrameDecoder(private val limits: PubsubRpcLimits) : MessageToMessageDecoder<ByteBuf>() {

    override fun decode(ctx: ChannelHandlerContext, msg: ByteBuf, out: MutableList<Any>) {
        if (limits.isNoop) {
            out.add(msg.retain())
            return
        }

        val result = try {
            RpcMessageCountValidator.validate(msg, limits)
        } catch (e: Exception) {
            logger.debug("Dropping pubsub RPC frame due to unexpected validator error", e)
            return
        }

        when (result) {
            RpcMessageCountValidator.Result.Accepted -> {
                out.add(msg.retain())
            }
            is RpcMessageCountValidator.Result.Malformed -> {
                throw CorruptedFrameException(result.reason)
            }
            is RpcMessageCountValidator.Result.Rejected -> {
                logger.debug("Dropping pubsub RPC frame: {}", result.reason)
            }
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(RpcCountFrameDecoder::class.java)
    }
}
