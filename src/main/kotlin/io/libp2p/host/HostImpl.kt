package io.libp2p.host

import io.libp2p.core.AddressBook
import io.libp2p.core.ChannelVisitor
import io.libp2p.core.Connection
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.Host
import io.libp2p.core.Network
import io.libp2p.core.NoSuchLocalProtocolException
import io.libp2p.core.PeerId
import io.libp2p.core.Stream
import io.libp2p.core.StreamPromise
import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multistream.ProtocolBinding
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

class HostImpl(
    override val privKey: PrivKey,
    override val network: Network,
    override val addressBook: AddressBook,
    private val listenAddrs: List<Multiaddr>,
    private val protocolHandlers: MutableList<ProtocolBinding<Any>>,
    private val connectionHandlers: ConnectionHandler.Broadcast,
    private val streamVisitors: ChannelVisitor.Broadcast<Stream>
) : Host {

    override val peerId = PeerId.fromPubKey(privKey.publicKey())
    override val streams = CopyOnWriteArrayList<Stream>()

    private val internalStreamVisitor = ChannelVisitor<Stream> { stream ->
        streams += stream
        stream.closeFuture().thenAccept { streams -= stream }
    }

    init {
        streamVisitors += internalStreamVisitor
    }

    override fun listenAddresses(): List<Multiaddr> {
        val listening = mutableListOf<Multiaddr>()

        network.transports.forEach {
            listening.addAll(
                it.listenAddresses().map { Multiaddr(it, peerId) }
            )
        }

        return listening
    }

    override fun start(): CompletableFuture<Void> {
        return CompletableFuture.allOf(
            *listenAddrs.map { network.listen(it) }.toTypedArray()
        )
    }

    override fun stop(): CompletableFuture<Void> {
        return CompletableFuture.allOf(
            network.close()
        )
    }

    override fun addStreamVisitor(streamVisitor: ChannelVisitor<Stream>) {
        streamVisitors += streamVisitor
    }

    override fun removeStreamVisitor(streamVisitor: ChannelVisitor<Stream>) {
        streamVisitors -= streamVisitor
    }

    override fun addProtocolHandler(protocolBinding: ProtocolBinding<Any>) {
        protocolHandlers += protocolBinding
    }

    override fun removeProtocolHandler(protocolBinding: ProtocolBinding<Any>) {
        protocolHandlers -= protocolBinding
    }

    override fun addConnectionHandler(handler: ConnectionHandler) {
        connectionHandlers += handler
    }

    override fun removeConnectionHandler(handler: ConnectionHandler) {
        connectionHandlers -= handler
    }

    override fun <TController> newStream(protocols: List<String>, peer: PeerId, vararg addr: Multiaddr): StreamPromise<TController> {
        val retF = network.connect(peer, *addr)
            .thenApply { newStream<TController>(protocols, it) }
        return StreamPromise(retF.thenCompose { it.stream }, retF.thenCompose { it.controller })
    }

    override fun <TController> newStream(protocols: List<String>, conn: Connection): StreamPromise<TController> {
        val binding =
            @Suppress("UNCHECKED_CAST")
            protocolHandlers.find { it.protocolDescriptor.matchesAny(protocols) } as? ProtocolBinding<TController>
                ?: throw NoSuchLocalProtocolException("Protocol handler not found: $protocols")
        return conn.muxerSession().createStream(listOf(binding.toInitiator(protocols)))
    }
}
