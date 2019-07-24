package io.libp2p.core.dsl

import io.libp2p.core.AddressBook
import io.libp2p.core.Host
import io.libp2p.core.Network
import io.libp2p.core.PeerId
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.core.mux.StreamMuxer
import io.libp2p.core.security.SecureChannel
import io.libp2p.core.transport.ConnectionUpgrader
import io.libp2p.core.transport.Transport

typealias TransportCtor = (ConnectionUpgrader) -> Transport
typealias StreamMuxerCtor = () -> StreamMuxer
typealias SecureChannelCtor = () -> SecureChannel
typealias ProtocolCtor = () -> ProtocolBinding<*>

class HostConfigurationException(message: String) : RuntimeException(message)

/**
 * Starts a fluent builder to construct a new Host.
 */
fun host(fn: Builder.() -> Unit) = Builder().apply(fn).build()

class Builder {
    private var identity = IdentityBuilder()
    private val secureChannels = SecureChannelsBuilder()
    private val muxers = MuxersBuilder()
    private val transports = TransportsBuilder()
    private val addressBook = AddressBookBuilder()
    private val protocols = ProtocolsBuilder()
    private val network = NetworkConfigBuilder()

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

    /**
     * Constructs the Host with the provided parameters.
     */
    fun build(): Host {
        if (secureChannels.values.isEmpty()) throw HostConfigurationException("at least one secure channel is required")
        if (muxers.values.isEmpty()) throw HostConfigurationException("at least one muxer is required")
        if (transports.values.isEmpty()) throw HostConfigurationException("at least one transport is required")

        val secureChannels = secureChannels.values.map { it() }
        val muxers = muxers.values.map { it() }

        val upgrader = ConnectionUpgrader(secureChannels, muxers)
        val transports = transports.values.map { it(upgrader) }
        val addressBook = addressBook.impl

        val id = identity.factory()
        val network = Network(transports, Network.Config(network.listen.map { Multiaddr(it) }))
        return Host(id, network, addressBook)
    }
}

class NetworkConfigBuilder {
    val listen = mutableListOf<String>()

    fun listen(vararg addrs: String): NetworkConfigBuilder = apply { addrs.forEach { listen += it } }
}

class IdentityBuilder {
    var factory: () -> PeerId = { PeerId.random() }

    fun random(): IdentityBuilder = apply { factory = { PeerId.random() } }
}

class AddressBookBuilder {
    var impl: AddressBook = TODO()

    fun memory(): AddressBookBuilder = apply { TODO() }
}

class ProtocolsBuilder : Enumeration<ProtocolCtor>()
class TransportsBuilder : Enumeration<TransportCtor>()
class SecureChannelsBuilder : Enumeration<SecureChannelCtor>()
class MuxersBuilder : Enumeration<StreamMuxerCtor>()

open class Enumeration<T>(val values: MutableList<T> = mutableListOf()) {
    operator fun (T).unaryPlus() {
        values += this
    }
}