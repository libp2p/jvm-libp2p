package io.libp2p.etc

import io.libp2p.core.ChannelVisitor
import io.libp2p.core.P2PChannel
import java.util.concurrent.CopyOnWriteArrayList

class BroadcastChannelVisitor<TChannel : P2PChannel>(
    private val handlers: MutableList<ChannelVisitor<TChannel>> = CopyOnWriteArrayList()
) : ChannelVisitor.Broadcast<TChannel>, MutableList<ChannelVisitor<TChannel>> by handlers {

    override fun visit(channel: TChannel) {
        handlers.forEach {
            it.visit(channel)
        }
    }
}
