package io.libp2p.core

import io.libp2p.etc.BroadcastConnectionHandler

/**
 * The same as [P2PAbstractHandler] with the [Connection] specialized [P2PAbstractChannel]
 */
interface ConnectionHandler {

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

        fun createStreamHandlerInitializer(streamHandler: StreamHandler<*>) =
            create { it.muxerSession.inboundStreamHandler = streamHandler }
    }

    interface Broadcast : ConnectionHandler, MutableList<ConnectionHandler>
}
