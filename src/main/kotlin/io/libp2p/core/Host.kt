package io.libp2p.core

import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multistream.ProtocolBinding
import java.util.concurrent.CompletableFuture

/**
 * The Host is the libp2p entrypoint. It is tightly coupled with all its inner components right now; in the near future
 * it should use some kind of dependency injection to wire itself.
 */
interface Host {
    /**
     * Our private key which can be used by different protocols to sign messages
     */
    val privKey: PrivKey
    /**
     * Our [PeerId] which is normally derived from [privKey]
     */
    val peerId: PeerId
    /**
     * [Network] implementation
     */
    val network: Network
    /**
     * [AddressBook] implementation
     */
    val addressBook: AddressBook

    /**
     * List of all of the active listen address, across all of the active transports.
     * Note these address will be the actual address in use, not necessarily what was requested.
     * For example, requests to listen on a random TCP port - /ip4/addr/tcp/0 - will be returned
     * with the actual port used.
     */
    fun listenAddresses(): List<Multiaddr>

    /**
     * List of all streams opened at the moment across all the [Connection]s
     * Please note that this list is updated asynchronously so the streams upon receiving
     * of this list can be already closed or not yet completely initialized
     * To be synchronously notified on stream creation use [addStreamHandler] and
     * use [Stream.closeFuture] to be synchronously notified on stream close
     */
    val streams: List<Stream>

    /**
     * Starts all services of this host (like listening transports, etc)
     * The returned future is completed when all stuff up and working or
     * has completes with exception in case of any problems during start up
     */
    fun start(): CompletableFuture<Void>

    /**
     * Stops all the services of this host
     */
    fun stop(): CompletableFuture<Void>

    /**
     * Adds a handler which is notified when a new [Stream] is created
     * Note that this is just a hook to be informed on a stream creation
     * and no actual [Stream.nettyChannel] initialization should happen here.
     * Refer to [addProtocolHandler] to setup a specific protocol handler
     */
    fun addStreamHandler(handler: StreamHandler<*>)

    /**
     * Removes the handler added with [addStreamHandler]
     */
    fun removeStreamHandler(handler: StreamHandler<*>)

    /**
     * Adds a new supported protocol 'on the fly'
     * After the protocol is added it would handle inbound requests
     * and be actively started up with [newStream] method
     */
    fun addProtocolHandler(protocolBinding: ProtocolBinding<Any>)

    /**
     * Removes the handler added with [addProtocolHandler]
     */
    fun removeProtocolHandler(protocolBinding: ProtocolBinding<Any>)

    fun addConnectionHandler(handler: ConnectionHandler)
    fun removeConnectionHandler(handler: ConnectionHandler)

    fun <TController> newStream(protocol: String, conn: Connection): StreamPromise<TController>
    fun <TController> newStream(protocol: String, peer: PeerId, vararg addr: Multiaddr): StreamPromise<TController>
}
