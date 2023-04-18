package io.libp2p.mux.yamux

import io.libp2p.etc.types.toByteArray
import io.libp2p.etc.types.toHex
import io.libp2p.etc.util.netty.mux.MuxId
import io.netty.buffer.ByteBuf
import io.netty.buffer.DefaultByteBufHolder
import io.netty.buffer.Unpooled

/**
 * Contains the fields that comprise a yamux frame.
 * @param streamId the ID of the stream.
 * @param flag the flag value for this frame.
 * @param data the data segment.
 */
class YamuxFrame(val id: MuxId, val type: Int, val flags: Int, val lenData: Int, val data: ByteBuf? = null) :
    DefaultByteBufHolder(data ?: Unpooled.EMPTY_BUFFER) {

    override fun toString(): String {
        if (data == null)
            return "YamuxFrame(id=$id, type=$type, flag=$flags)"
        return "YamuxFrame(id=$id, type=$type, flag=$flags, data=${String(data.toByteArray())})"
    }
}
