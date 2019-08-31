package io.libp2p.core

import java.util.concurrent.CopyOnWriteArrayList

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
        fun createBroadcast(handlers: List<ConnectionHandler> = listOf()) =
            BroadcastConnectionHandler().also { it += handlers }

        fun createStreamHandlerInitializer(streamHandler: StreamHandler<*>) =
            create { it.muxerSession.inboundStreamHandler = streamHandler }
    }
}

class BroadcastConnectionHandler(
    private val handlers: MutableList<ConnectionHandler> = CopyOnWriteArrayList()
) : ConnectionHandler, MutableList<ConnectionHandler> by handlers {
    override fun handleConnection(conn: Connection) = handlers.forEach { it.handleConnection(conn) }
}