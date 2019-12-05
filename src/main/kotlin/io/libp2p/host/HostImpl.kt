package io.libp2p.host

import io.libp2p.core.AddressBook
import io.libp2p.core.Connection
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.Host
import io.libp2p.core.Network
import io.libp2p.core.NoSuchLocalProtocolException
import io.libp2p.core.PeerId
import io.libp2p.core.Stream
import io.libp2p.core.StreamHandler
import io.libp2p.core.StreamPromise
import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multistream.Multistream
import io.libp2p.core.multistream.ProtocolBinding
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

class HostImpl(
    override val privKey: PrivKey,
    override val network: Network,
    override val addressBook: AddressBook,
    private val listenAddrs: List<Multiaddr>,
    private val protocolHandlers: Multistream<Any>,
    private val connectionHandlers: ConnectionHandler.Broadcast,
    private val streamHandlers: StreamHandler.Broadcast
) : Host {

    override val peerId = PeerId.fromPubKey(privKey.publicKey())
    override val streams = CopyOnWriteArrayList<Stream>()

    private val internalStreamHandler = StreamHandler.create { stream ->
        streams += stream
        stream.closeFuture().thenAccept { streams -= stream }
    }

    init {
        streamHandlers += internalStreamHandler
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

    override fun addStreamHandler(handler: StreamHandler<*>) {
        streamHandlers += handler
    }

    override fun removeStreamHandler(handler: StreamHandler<*>) {
        streamHandlers -= handler
    }

    override fun addProtocolHandler(protocolBinding: ProtocolBinding<Any>) {
        protocolHandlers.bindings += protocolBinding
    }

    override fun removeProtocolHandler(protocolBinding: ProtocolBinding<Any>) {
        protocolHandlers.bindings -= protocolBinding
    }

    override fun addConnectionHandler(handler: ConnectionHandler) {
        connectionHandlers += handler
    }

    override fun removeConnectionHandler(handler: ConnectionHandler) {
        connectionHandlers -= handler
    }

    override fun <TController> newStream(protocol: String, peer: PeerId, vararg addr: Multiaddr): StreamPromise<TController> {
        val retF = network.connect(peer, *addr)
            .thenApply { newStream<TController>(protocol, it) }
        return StreamPromise(retF.thenCompose { it.stream }, retF.thenCompose { it.controller })
    }

    override fun <TController> newStream(protocol: String, conn: Connection): StreamPromise<TController> {
        val binding =
            protocolHandlers.bindings.find { it.matcher.matches(protocol) } as? ProtocolBinding<TController>
            ?: throw NoSuchLocalProtocolException("Protocol handler not found: $protocol")

        val multistream: Multistream<TController> =
            Multistream.create(binding.toInitiator(protocol))
        return conn.muxerSession().createStream(object : StreamHandler<TController> {
            override fun handleStream(stream: Stream): CompletableFuture<out TController> {
                val ret = multistream.toStreamHandler().handleStream(stream)
                streamHandlers.handleStream(stream)
                return ret
            }
        })
    }
}
