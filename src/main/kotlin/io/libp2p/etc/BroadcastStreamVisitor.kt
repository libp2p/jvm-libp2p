package io.libp2p.etc

import io.libp2p.core.Stream
import io.libp2p.core.StreamVisitor
import java.util.concurrent.CopyOnWriteArrayList

class BroadcastStreamVisitor(
    private val handlers: MutableList<StreamVisitor> = CopyOnWriteArrayList()
) : StreamVisitor.Broadcast, MutableList<StreamVisitor> by handlers {
    override fun onNewStream(stream: Stream) {
        handlers.forEach {
            it.onNewStream(stream)
        }
    }
}
