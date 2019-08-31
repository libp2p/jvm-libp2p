package io.libp2p.core

import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.network.NetworkImpl
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

    val streams: List<Stream>

    fun start(): CompletableFuture<Unit>
    fun stop(): CompletableFuture<Unit>

    fun addStreamHandler(handler: StreamHandler<*>)
    fun removeStreamHandler(handler: StreamHandler<*>)

    fun addProtocolHandler(protocolBinding: ProtocolBinding<Any>)
    fun removeProtocolHandler(protocolBinding: ProtocolBinding<Any>)

    fun addConnectionHandler(handler: ConnectionHandler)
    fun removeConnectionHandler(handler: ConnectionHandler)

    fun <TController> newStream(conn: Connection, protocol: String): StreamPromise<TController>
    fun <TController> newStream(protocol: String, peer: PeerId, vararg addr: Multiaddr): StreamPromise<TController>
}
