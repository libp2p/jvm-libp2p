package io.libp2p.core

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
        fun createBroadcast(handlers: List<ConnectionHandler>) =
            create { conn -> handlers.forEach { it.handleConnection(conn) } }

        fun createStreamHandlerInitializer(streamHandler: StreamHandler) =
            create { it.muxerSession.inboundStreamHandler = streamHandler }
    }
}