package io.libp2p.tools

import io.libp2p.core.AddressBook
import io.libp2p.core.ChannelVisitor
import io.libp2p.core.Connection
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.Host
import io.libp2p.core.Network
import io.libp2p.core.PeerId
import io.libp2p.core.Stream
import io.libp2p.core.StreamPromise
import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multistream.ProtocolBinding
import java.util.concurrent.CompletableFuture

open class NullHost : Host {
    override val privKey: PrivKey
        get() = TODO("not implemented")
    override val peerId: PeerId
        get() = TODO("not implemented")
    override val network: Network
        get() = TODO("not implemented")
    override val addressBook: AddressBook
        get() = TODO("not implemented")

    override fun listenAddresses(): List<Multiaddr> {
        TODO("not implemented")
    }

    override val streams: List<Stream>
        get() = TODO("not implemented")

    override fun start(): CompletableFuture<Void> {
        TODO("not implemented")
    }

    override fun stop(): CompletableFuture<Void> {
        TODO("not implemented")
    }

    override fun addStreamVisitor(streamVisitor: ChannelVisitor<Stream>) {
        TODO("Not yet implemented")
    }

    override fun removeStreamVisitor(streamVisitor: ChannelVisitor<Stream>) {
        TODO("Not yet implemented")
    }

    override fun addProtocolHandler(protocolBinding: ProtocolBinding<Any>) {
        TODO("not implemented")
    }

    override fun removeProtocolHandler(protocolBinding: ProtocolBinding<Any>) {
        TODO("not implemented")
    }

    override fun getProtocols(): List<ProtocolBinding<Any>> {
        TODO("not implemented")
    }

    override fun addConnectionHandler(handler: ConnectionHandler) {
        TODO("not implemented")
    }

    override fun removeConnectionHandler(handler: ConnectionHandler) {
        TODO("not implemented")
    }

    override fun <TController> newStream(protocols: List<String>, conn: Connection): StreamPromise<TController> {
        TODO("not implemented")
    }

    override fun <TController> newStream(
        protocols: List<String>,
        peer: PeerId,
        vararg addr: Multiaddr
    ): StreamPromise<TController> {
        TODO("not implemented")
    }
}
