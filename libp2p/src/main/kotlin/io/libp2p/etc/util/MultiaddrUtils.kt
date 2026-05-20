package io.libp2p.etc.util

import io.libp2p.core.InternalErrorException
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multiformats.Protocol
import java.net.*

class MultiaddrUtils {

    companion object {

        fun inetAddressToIpMultiaddr(addr: InetAddress): Multiaddr {
            val proto = when (addr) {
                is Inet4Address -> Protocol.IP4
                is Inet6Address -> Protocol.IP6
                else -> throw InternalErrorException("Unknown address type $addr")
            }
            return Multiaddr.empty()
                .withComponent(proto, addr.hostAddress)
        }

        fun inetSocketAddressToTcpMultiaddr(addr: InetSocketAddress): Multiaddr =
            inetAddressToIpMultiaddr(addr.address)
                .withComponent(Protocol.TCP, addr.port.toString())

        fun inetSocketAddressToUdpMultiaddr(addr: InetSocketAddress): Multiaddr =
            inetAddressToIpMultiaddr(addr.address)
                .withComponent(Protocol.UDP, addr.port.toString())
    }
}
