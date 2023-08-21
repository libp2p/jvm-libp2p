package io.libp2p.etc.util.netty.mux

import io.netty.channel.ChannelId

data class MuxId(val parentId: ChannelId, val id: Long, val initiator: Boolean) : ChannelId {
    override fun asShortText() = "${parentId.asShortText()}/$id/$initiator"
    override fun asLongText() = "${parentId.asLongText()}/$id/$initiator"
    override fun compareTo(other: ChannelId?) = asShortText().compareTo(other?.asShortText() ?: "")
    override fun toString() = asLongText()
}
