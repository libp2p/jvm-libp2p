package io.libp2p.core.util.netty.multiplex

import io.netty.channel.ChannelId

data class MultiplexId(val id: Long) : ChannelId {
    override fun asShortText() = "" + id
    override fun asLongText() = asShortText()
    override fun compareTo(other: ChannelId?): Int = (id - (other as MultiplexId).id).toInt()
}