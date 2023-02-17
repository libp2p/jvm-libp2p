package io.libp2p.core

import io.libp2p.etc.BroadcastConnectionHandler

/**
 * The same as [P2PChannelHandler] with the [Connection] specialized [P2PChannel]
 */
fun interface ConnectionHandler {

    fun handleConnection(conn: Connection)

    companion object {
        fun create(handler: (Connection) -> Unit): ConnectionHandler {
            return object : ConnectionHandler {
                override fun handleConnection(conn: Connection) {
                    handler(conn)
                }
            }
        }
        fun createBroadcast(handlers: List<ConnectionHandler> = listOf()): Broadcast =
            BroadcastConnectionHandler().also { it += handlers }
    }

    interface Broadcast : ConnectionHandler, MutableList<ConnectionHandler>
}
