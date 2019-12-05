package io.libp2p.core.multiformats

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

class MultiaddrDns {
    companion object {
        private val dnsProtocols = arrayOf(Protocol.DNS4, Protocol.DNS6, Protocol.DNSADDR)

        fun resolve(addr: Multiaddr): List<Multiaddr> {
            if (!addr.hasAny(*dnsProtocols))
                return listOf(addr)

            val addressesToResolve = addr.split { isDnsProtocol(it) }

            val resolvedAddresses = mutableListOf<List<Multiaddr>>()
            for (address in addressesToResolve) {
                val toResolve = address.filterStringComponents(*dnsProtocols).firstOrNull()
                val resolved = if (toResolve != null)
                    resolve(toResolve.first, toResolve.second!!, address)
                else
                    listOf(address)
                resolvedAddresses.add(resolved)
            }

            return crossProduct(resolvedAddresses)
        }

        private fun resolve(proto: Protocol, hostname: String, address: Multiaddr): List<Multiaddr> {
            return when (proto) {
                Protocol.DNS4 -> resolveDns4(hostname, address)
                Protocol.DNS6 -> resolveDns6(hostname, address)
                else -> {
                    TODO(proto.toString() + " not done yet")
                }
            }
        }

        private fun resolveDns4(hostname: String, address: Multiaddr): List<Multiaddr> {
            return resolveDns(
                hostname,
                address,
                Protocol.IP4,
                Inet4Address::class.java
            )
        }

        private fun resolveDns6(hostname: String, address: Multiaddr): List<Multiaddr> {
            return resolveDns(
                hostname,
                address,
                Protocol.IP6,
                Inet6Address::class.java
            )
        }

        private fun <T : InetAddress> resolveDns(
            hostname: String,
            address: Multiaddr,
            resultantProto: Protocol,
            desiredAddressType: Class<T>
        ): List<Multiaddr> {
            val ipAddresses = InetAddress.getAllByName(hostname)
            return ipAddresses
                .filter { desiredAddressType.isInstance(it) }
                .map {
                    val components = address.components.toMutableList()
                    components[0] = Pair(resultantProto, it.address)
                    Multiaddr(components)
                }
        }

        private fun crossProduct(addressMatrix: List<List<Multiaddr>>): List<Multiaddr> {
            return addressMatrix[0]
        }

        private fun isDnsProtocol(proto: Protocol): Boolean {
            return dnsProtocols.contains(proto)
        }
    }
}