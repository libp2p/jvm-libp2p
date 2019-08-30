package io.libp2p.core

import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multistream.Multistream
import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.core.types.forward
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

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


class HostImpl(
    override val privKey: PrivKey,
    override val network: NetworkImpl,
    override val addressBook: AddressBook,
    private val listenAddrs: List<Multiaddr>,
    private val protocolHandlers: Multistream<Any>,
    private val connectionHandlers: BroadcastConnectionHandler,
    private val streamHandlers: BroadcastStreamHandler
) : Host {

    override val peerId = PeerId.fromPubKey(privKey.publicKey())
    override val streams = CopyOnWriteArrayList<Stream>()

    private val internalStreamHandler = StreamHandler.create { stream ->
        streams += stream
        stream.nettyChannel.closeFuture().addListener { streams -= stream }
    }

    init {
        streamHandlers += internalStreamHandler
    }

    override fun start(): CompletableFuture<Unit> {
        return CompletableFuture.allOf(
            *listenAddrs.map { network.listen(it) }.toTypedArray()
        ).thenApply { }
    }

    override fun stop(): CompletableFuture<Unit> {
        return network.close()
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
        connectionHandlers += handler
    }

    override fun <TController> newStream(protocol: String, peer: PeerId, vararg addr: Multiaddr): StreamPromise<TController> {
        val ret = StreamPromise<TController>()
        network.connect(peer, *addr)
            .handle { r, t ->
                if (t != null) {
                    ret.stream.completeExceptionally(t)
                    ret.controler.completeExceptionally(t)
                } else {
                    val (stream, controler) = newStream<TController>(r, protocol)
                    stream.forward(ret.stream)
                    controler.forward(ret.controler)
                }
            }
        return ret
    }

    override fun <TController> newStream(conn: Connection, protocol: String): StreamPromise<TController> {
        val binding =
            protocolHandlers.bindings.find { it.matcher.matches(protocol) } as? ProtocolBinding<TController>
            ?: throw Libp2pException("Protocol handler not found: $protocol")

        val multistream: Multistream<TController>  = Multistream.create(binding.toInitiator(protocol))
        return conn.muxerSession.createStream(object : StreamHandler<TController> {
            override fun handleStream(stream: Stream): CompletableFuture<out TController> {
                val ret = multistream.toStreamHandler().handleStream(stream)
                streamHandlers.handleStream(stream)
                return ret
            }
        })
    }
}
