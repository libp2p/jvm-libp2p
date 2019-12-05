package io.libp2p.core.multiformats

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
            return listOf(address)
        }

        private fun crossProduct(addressMatrix: List<List<Multiaddr>>): List<Multiaddr> {
            return addressMatrix[0]
        }

        private fun isDnsProtocol(proto: Protocol): Boolean {
            return dnsProtocols.contains(proto)
        }
    }
}