package io.libp2p.core.mux

import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelId

/**
 * Created by Anton Nashatyrev on 09.07.2019.
 */
class MultistreamFrame(val data: ByteBuf, val id: MultistreamId) {

}

class MultistreamId(val id: Long) : ChannelId {
    override fun asShortText(): String {
        TODO("not implemented")
    }

    override fun asLongText(): String {
        TODO("not implemented")
    }

    override fun compareTo(other: ChannelId?): Int {
        TODO("not implemented")
    }
}
