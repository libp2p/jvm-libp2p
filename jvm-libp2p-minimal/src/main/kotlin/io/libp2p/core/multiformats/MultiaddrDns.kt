package io.libp2p.core.multiformats

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

class MultiaddrDns {
    interface Resolver {
        fun resolveDns4(hostname: String): List<Multiaddr>
        fun resolveDns6(hostname: String): List<Multiaddr>
    }

    companion object {
        private val dnsProtocols = arrayOf(Protocol.DNS4, Protocol.DNS6, Protocol.DNSADDR)

        fun resolve(addr: Multiaddr, resolver: Resolver = DefaultResolver): List<Multiaddr> {
            if (!addr.hasAny(*dnsProtocols))
                return listOf(addr)

            val addressesToResolve = addr.split { isDnsProtocol(it) }

            val resolvedAddresses = mutableListOf<List<Multiaddr>>()
            for (address in addressesToResolve) {
                val toResolve = address.filterStringComponents(*dnsProtocols).firstOrNull()
                val resolved = if (toResolve != null)
                    resolve(toResolve.first, toResolve.second!!, address, resolver)
                else
                    listOf(address)
                resolvedAddresses.add(resolved)
            }

            return crossProduct(resolvedAddresses)
        }

        private fun resolve(proto: Protocol, hostname: String, address: Multiaddr, resolver: Resolver): List<Multiaddr> {
            return resolve(proto, hostname, resolver)
                .map {
                    val components = address.components.toMutableList()
                    components[0] = it.components[0] // replace DNS portion with resolved address
                    Multiaddr(components)
                }
        }

        private fun resolve(proto: Protocol, hostname: String, resolver: Resolver): List<Multiaddr> {
            try {
                return when (proto) {
                    Protocol.DNS4 -> resolver.resolveDns4(hostname)
                    Protocol.DNS6 -> resolver.resolveDns6(hostname)
                    else -> {
                        TODO(proto.toString() + " not done yet")
                    }
                }
            } catch (e: UnknownHostException) {
                return emptyList()
                // squash, as this might not be fatal,
                // and if it is we'll handle this higher up the call chain
            }
        }

        // We generate the cross product here as we don't have any
        // better way to represent "ORs" in multiaddrs. For
        // example, `/dns/pig.com/p2p-circuit/dns/pog.com` might
        // resolve to:
        // * /ip4/1.1.1.1/p2p-circuit/ip4/2.1.1.1
        // * /ip4/1.1.1.1/p2p-circuit/ip4/2.1.1.2
        // * /ip4/1.1.1.2/p2p-circuit/ip4/2.1.1.1
        // * /ip4/1.1.1.2/p2p-circuit/ip4/2.1.1.2
        private fun crossProduct(addressMatrix: List<List<Multiaddr>>): List<Multiaddr> {
            return if (addressMatrix.size == 1)
                addressMatrix[0]
            else
                addressMatrix[0].flatMap { parent ->
                    crossProduct(addressMatrix.subList(1, addressMatrix.size))
                        .map { child -> Multiaddr(parent, child) }
                }
        }

        private fun isDnsProtocol(proto: Protocol): Boolean {
            return dnsProtocols.contains(proto)
        }

        val DefaultResolver = object : Resolver {
            override fun resolveDns4(hostname: String): List<Multiaddr> {
                return resolveDns(
                    hostname,
                    Protocol.IP4,
                    Inet4Address::class.java
                )
            }

            override fun resolveDns6(hostname: String): List<Multiaddr> {
                return resolveDns(
                    hostname,
                    Protocol.IP6,
                    Inet6Address::class.java
                )
            }

            private fun <T : InetAddress> resolveDns(
                hostname: String,
                resultantProto: Protocol,
                desiredAddressType: Class<T>
            ): List<Multiaddr> {
                val ipAddresses = InetAddress.getAllByName(hostname)
                return ipAddresses
                    .filter { desiredAddressType.isInstance(it) }
                    .map {
                        Multiaddr(
                            listOf(
                                Pair(resultantProto, it.address)
                            )
                        )
                    }
            }
        }
    }
}
