package io.libp2p.mux.yamux

import io.libp2p.etc.util.netty.mux.MuxId
import io.netty.channel.ChannelId

class YamuxId(
    parentId: ChannelId,
    id: Long,
) : MuxId(parentId, id) {

    override fun asShortText() = "${parentId.asShortText()}/$id"
    override fun asLongText() = "${parentId.asLongText()}/$id"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        return (other as YamuxId).id == id
    }

    override fun hashCode(): Int = id.hashCode()

    companion object {
        val SESSION_STREAM_ID = 0L
        fun sessionId(parentId: ChannelId) = YamuxId(parentId, SESSION_STREAM_ID)
    }
}