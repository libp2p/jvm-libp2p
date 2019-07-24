package io.libp2p.core.util.netty.mux

import io.netty.channel.ChannelId

data class MuxId(val id: Long) : ChannelId {
    override fun asShortText() = "" + id
    override fun asLongText() = asShortText()
    override fun compareTo(other: ChannelId?): Int = (id - (other as MuxId).id).toInt()
}