package io.libp2p.transport.ws

import io.libp2p.core.Connection
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.Libp2pException
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multiformats.Protocol
import io.libp2p.core.transport.Transport
import io.libp2p.transport.ConnectionUpgrader
import java.util.concurrent.CompletableFuture

class WsTransport(
    val upgrader: ConnectionUpgrader
) : Transport {
    private var closed = false

    override fun initialize() {
    }

    override fun handles(addr: Multiaddr): Boolean {
        return (addr.has(Protocol.IP4) || addr.has(Protocol.IP6) || addr.has(Protocol.DNSADDR)) &&
                addr.has(Protocol.TCP) &&
                addr.has(Protocol.WS)
    }

    override fun close(): CompletableFuture<Unit> {
        closed = true

        return CompletableFuture.completedFuture(null)
    }

    override fun listen(addr: Multiaddr, connHandler: ConnectionHandler): CompletableFuture<Unit> {
        if (closed) throw Libp2pException("Transport is closed")

        TODO("not implemented")
    }

    override fun unlisten(addr: Multiaddr): CompletableFuture<Unit> {
        TODO("not implemented")
    }

    override fun dial(addr: Multiaddr, connHandler: ConnectionHandler): CompletableFuture<Connection> {
        if (closed) throw Libp2pException("Transport is closed")

        TODO("not implemented")
    }
}