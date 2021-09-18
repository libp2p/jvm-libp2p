package io.libp2p.mux

import io.libp2p.etc.types.toByteArray
import io.libp2p.etc.types.toHex
import io.libp2p.etc.util.netty.mux.MuxId
import io.netty.buffer.ByteBuf
import io.netty.buffer.DefaultByteBufHolder
import io.netty.buffer.Unpooled

open class MuxFrame(val id: MuxId, val flag: Flag, val data: ByteBuf? = null) :
    DefaultByteBufHolder(data ?: Unpooled.EMPTY_BUFFER) {

    enum class Flag {
        OPEN,
        DATA,
        CLOSE,
        RESET
    }

    override fun toString(): String {
        return "MuxFrame(id=$id, flag=$flag, data=${data?.toByteArray()?.toHex()})"
    }
}
