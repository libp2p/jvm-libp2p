package io.libp2p.etc

import io.libp2p.core.Connection
import io.libp2p.core.ConnectionHandler
import java.util.concurrent.CopyOnWriteArrayList

class BroadcastConnectionHandler(
    private val handlers: MutableList<ConnectionHandler> = CopyOnWriteArrayList()
) : ConnectionHandler.Broadcast, MutableList<ConnectionHandler> by handlers {
    override fun handleConnection(conn: Connection) = handlers.forEach { it.handleConnection(conn) }
}
