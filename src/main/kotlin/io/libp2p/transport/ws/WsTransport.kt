package io.libp2p.transport.ws

import io.libp2p.core.Connection
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multiformats.Protocol
import io.libp2p.core.transport.Transport
import io.libp2p.transport.ConnectionUpgrader
import java.util.concurrent.CompletableFuture

class WsTransport(
    val upgrader: ConnectionUpgrader
) : Transport {
    override fun initialize() {
    }

    override fun handles(addr: Multiaddr): Boolean {
        return (addr.has(Protocol.IP4) || addr.has(Protocol.IP6) || addr.has(Protocol.DNSADDR)) &&
                addr.has(Protocol.TCP) &&
                addr.has(Protocol.WS)
    }

    override fun close(): CompletableFuture<Unit> {
        TODO("not implemented")
    }

    override fun listen(addr: Multiaddr, connHandler: ConnectionHandler): CompletableFuture<Unit> {
        TODO("not implemented")
    }

    override fun unlisten(addr: Multiaddr): CompletableFuture<Unit> {
        TODO("not implemented")
    }

    override fun dial(addr: Multiaddr, connHandler: ConnectionHandler): CompletableFuture<Connection> {
        TODO("not implemented")
    }
}