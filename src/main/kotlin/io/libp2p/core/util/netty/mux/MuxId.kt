package io.libp2p.core.util.netty.mux

import io.netty.channel.ChannelId

data class MuxId(val id: Long, val initiator: Boolean) : ChannelId {
    override fun asShortText() = "$id/$initiator"
    override fun asLongText() = asShortText()
    override fun compareTo(other: ChannelId?) = asShortText().compareTo(other?.asShortText() ?: "")
}