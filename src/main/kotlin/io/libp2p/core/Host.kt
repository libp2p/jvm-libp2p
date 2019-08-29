package io.libp2p.core

import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multistream.ProtocolMatcher
import java.util.concurrent.CompletableFuture

/**
 * The Host is the libp2p entrypoint. It is tightly coupled with all its inner components right now; in the near future
 * it should use some kind of dependency injection to wire itself.
 */
interface Host {
    val privKey: PrivKey
    val peerId: PeerId
    val network: NetworkImpl
    val addressBook: AddressBook

    val streams: Map<Connection, Stream>

    fun start(): CompletableFuture<Unit>
    fun stop(): CompletableFuture<Unit>

    fun addStreamHandler(protocol: ProtocolMatcher, handler: StreamHandler): Unit = TODO()
    fun removeStreamHandler(protocol: ProtocolMatcher): Unit = TODO()

    fun addConnectionHandler(handler: ConnectionHandler): Unit = TODO()
    fun removeConnectionHandler(handler: ConnectionHandler): Unit = TODO()

    fun newStream(conn: Connection, protocol: String): CompletableFuture<Stream> = TODO()
    fun newStream(peer: PeerId, addr: Multiaddr, protocol: String): CompletableFuture<Stream> = TODO()
}


class HostImpl(
    val privKey: PrivKey,
    val network: NetworkImpl,
    val addressBook: AddressBook
) {

    val peerId = PeerId.fromPubKey(privKey.publicKey())

    fun start(): CompletableFuture<Unit> {
        return network.start()
    }

    fun stop(): CompletableFuture<Unit> {
        return network.close()
    }
}
