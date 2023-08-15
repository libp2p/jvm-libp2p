package io.libp2p.mux.yamux

import io.libp2p.etc.types.toByteArray
import io.libp2p.etc.util.netty.mux.MuxId
import io.netty.buffer.ByteBuf
import io.netty.buffer.DefaultByteBufHolder
import io.netty.buffer.Unpooled

/**
 * Contains the fields that comprise a yamux frame.
 * @param id the ID of the stream.
 * @param flag the flag value for this frame.
 * @param length the length field for this frame.
 * @param data the data segment.
 */
class YamuxFrame(val id: MuxId, val type: Int, val flag: Int, val length: Long, val data: ByteBuf? = null) :
    DefaultByteBufHolder(data ?: Unpooled.EMPTY_BUFFER) {

    override fun toString(): String {
        if (data == null) {
            return "YamuxFrame(id=$id, type=$type, flag=$flag, length=$length)"
        }
        return "YamuxFrame(id=$id, type=$type, flag=$flag, length=$length, data=${String(data.toByteArray())})"
    }
}
