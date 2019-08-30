package io.libp2p.core.dsl

import io.libp2p.core.AddressBook
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.HostImpl
import io.libp2p.core.MemoryAddressBook
import io.libp2p.core.NetworkImpl
import io.libp2p.core.StreamHandler
import io.libp2p.core.crypto.KEY_TYPE
import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.crypto.generateKeyPair
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multistream.Multistream
import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.core.mux.StreamMuxer
import io.libp2p.core.mux.StreamMuxerDebug
import io.libp2p.core.security.SecureChannel
import io.libp2p.core.transport.ConnectionUpgrader
import io.libp2p.core.transport.Transport
import io.libp2p.core.types.lazyVar
import io.netty.channel.ChannelHandler
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler

typealias TransportCtor = (ConnectionUpgrader) -> Transport
typealias StreamMuxerCtor = () -> StreamMuxer
typealias SecureChannelCtor = (PrivKey) -> SecureChannel
typealias ProtocolCtor = () -> ProtocolBinding<*>

class HostConfigurationException(message: String) : RuntimeException(message)

/**
 * Starts a fluent builder to construct a new Host.
 */
fun host(fn: Builder.() -> Unit) = Builder().apply(fn).build()

open class Builder {
    protected open val identity = IdentityBuilder()
    protected open val secureChannels = SecureChannelsBuilder()
    protected open val muxers = MuxersBuilder()
    protected open val transports = TransportsBuilder()
    protected open val addressBook = AddressBookBuilder()
    protected open val protocols = ProtocolsBuilder()
    protected open val network = NetworkConfigBuilder()
    protected open val debug = DebugBuilder()

    /**
     * Sets an identity for this host. If unset, libp2p will default to a random identity.
     */
    fun identity(fn: IdentityBuilder.() -> Unit): Builder = apply { fn(identity) }

    /**
     * Manipulates the security channels for this host.
     */
    fun secureChannels(fn: SecureChannelsBuilder.() -> Unit): Builder = apply { fn(secureChannels) }

    /**
     * Manipulates the stream muxers for this host.
     */
    fun muxers(fn: MuxersBuilder.() -> Unit): Builder = apply { fn(muxers) }

    /**
     * Manipulates the transports for this host.
     */
    fun transports(fn: TransportsBuilder.() -> Unit): Builder = apply { fn(transports) }

    fun addressBook(fn: AddressBookBuilder.() -> Unit): Builder = apply { fn(addressBook) }

    fun protocols(fn: ProtocolsBuilder.() -> Unit): Builder = apply { fn(protocols) }

    fun network(fn: NetworkConfigBuilder.() -> Unit): Builder = apply { fn(network) }

    fun debug(fn: DebugBuilder.() -> Unit): Builder = apply { fn(debug) }

    /**
     * Constructs the Host with the provided parameters.
     */
    fun build(): HostImpl {
        if (secureChannels.values.isEmpty()) throw HostConfigurationException("at least one secure channel is required")
        if (muxers.values.isEmpty()) throw HostConfigurationException("at least one muxer is required")
        if (transports.values.isEmpty()) throw HostConfigurationException("at least one transport is required")

        val privKey = identity.factory()

        val secureChannels = secureChannels.values.map { it(privKey) }
        val muxers = muxers.values.map { it() }

        muxers.mapNotNull { it as? StreamMuxerDebug }.forEach { it.muxFramesDebugHandler = debug.muxFramesHandler.handler }

        val upgrader = ConnectionUpgrader(secureChannels, muxers).apply {
            beforeSecureHandler = debug.beforeSecureHandler.handler
            afterSecureHandler = debug.afterSecureHandler.handler
        }

        val transports = transports.values.map { it(upgrader) }
        val addressBook = addressBook.impl

        val protocolsMultistream: Multistream<Any> = Multistream.create(protocols.values)
        val broadcastStreamHandler = StreamHandler.createBroadcast()
        val allStreamHandlers = StreamHandler.createBroadcast(
            protocolsMultistream.toStreamHandler(), broadcastStreamHandler)

        val connHandlerProtocols = protocols.values.mapNotNull { it as? ConnectionHandler }
        var broadcastConnHandler = ConnectionHandler.createBroadcast(
            listOf(ConnectionHandler.createStreamHandlerInitializer(allStreamHandlers)) + connHandlerProtocols
        )
        val networkImpl = NetworkImpl(transports, broadcastConnHandler)

        return HostImpl(privKey, networkImpl, addressBook, network.listen.map { Multiaddr(it) }, protocolsMultistream, broadcastConnHandler, broadcastStreamHandler)
    }
}

class NetworkConfigBuilder {
    val listen = mutableListOf<String>()

    fun listen(vararg addrs: String): NetworkConfigBuilder = apply { listen += addrs }
}

class IdentityBuilder {
    var factory: () -> PrivKey = { throw IllegalStateException("No identity builder") }

    fun random(): IdentityBuilder = apply { factory = { generateKeyPair(KEY_TYPE.ECDSA).first } }
}

class AddressBookBuilder {
    var impl: AddressBook by lazyVar { MemoryAddressBook() }

    fun memory(): AddressBookBuilder = apply { impl = MemoryAddressBook() }
}

class TransportsBuilder : Enumeration<TransportCtor>()
class SecureChannelsBuilder : Enumeration<SecureChannelCtor>()
class MuxersBuilder : Enumeration<StreamMuxerCtor>()
class ProtocolsBuilder : Enumeration<ProtocolBinding<Any>>()

class DebugBuilder {
    val beforeSecureHandler = DebugHandlerBuilder("wire.sec.before")
    val afterSecureHandler = DebugHandlerBuilder("wire.sec.after")
    val muxFramesHandler = DebugHandlerBuilder("wire.mux.frames")
}

class DebugHandlerBuilder(var name: String) {
    var handler: ChannelHandler? = null

    fun setLogger(level: LogLevel, loggerName: String = name) {
        handler = LoggingHandler(loggerName, level)
    }
}

open class Enumeration<T>(val values: MutableList<T> = mutableListOf()) {
    operator fun (T).unaryPlus() {
        values += this
    }

    fun add(t: T) { values += t }
}
