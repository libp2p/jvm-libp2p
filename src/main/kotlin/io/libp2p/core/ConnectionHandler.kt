package io.libp2p.core

interface ConnectionHandler {
    fun handleConnection(conn: Connection)

    companion object {
        fun createBroadcast(handlers: List<ConnectionHandler>): ConnectionHandler {
            return object : ConnectionHandler {
                override fun handleConnection(conn: Connection) {
                    handlers.forEach { it.handleConnection(conn) }
                }
            }
        }
    }
}