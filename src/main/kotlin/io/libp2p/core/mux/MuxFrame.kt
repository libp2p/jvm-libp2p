package io.libp2p.core.mux

import io.libp2p.core.types.toByteArray
import io.libp2p.core.types.toHex
import io.libp2p.core.util.netty.mux.MuxId
import io.netty.buffer.ByteBuf

open class MuxFrame(val id: MuxId, val flag: Flag, val data: ByteBuf? = null) {
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
