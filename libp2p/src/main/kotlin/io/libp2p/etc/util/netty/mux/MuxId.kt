package io.libp2p.etc.util.netty.mux

import io.netty.channel.ChannelId

abstract class MuxId(
    val parentId: ChannelId,
    val id: Long
) : ChannelId {

    override fun compareTo(other: ChannelId?) = asShortText().compareTo(other?.asShortText() ?: "")
    override fun toString() = asLongText()

    abstract override fun hashCode(): Int
    abstract override fun equals(other: Any?): Boolean
}
