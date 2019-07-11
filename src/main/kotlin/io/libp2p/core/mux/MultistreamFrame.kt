package io.libp2p.core.mux

import io.libp2p.core.util.netty.multiplex.MultiplexId
import io.netty.buffer.ByteBuf

class MultistreamFrame(val id: MultiplexId, val flag: Flag, val data: ByteBuf? = null) {
    enum class Flag {
        OPEN,
        DATA,
        CLOSE
    }
}
