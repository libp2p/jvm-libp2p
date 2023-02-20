package io.libp2p.simulate.util

import io.netty.buffer.ByteBuf

typealias MsgSizeEstimator = (Any) -> Long

val GeneralSizeEstimator: MsgSizeEstimator = { msg ->
    when (msg) {
        is ByteBuf -> msg.readableBytes().toLong()
        else -> 0
    }
}
