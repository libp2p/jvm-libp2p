package io.libp2p.mux.mplex

import io.libp2p.etc.util.netty.mux.MuxId
import io.netty.channel.ChannelId

class MplexId(
    parentId: ChannelId,
    id: Long,
    val initiator: Boolean
) : MuxId(parentId, id) {

    override fun asShortText() = "${parentId.asShortText()}/$id/$initiator"
    override fun asLongText() = "${parentId.asLongText()}/$id/$initiator"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as MplexId
        return id == other.id && initiator == other.initiator
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + initiator.hashCode()
        return result
    }
}