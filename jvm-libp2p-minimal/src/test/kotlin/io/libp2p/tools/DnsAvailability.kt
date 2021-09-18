package io.libp2p.tools

import org.junit.jupiter.api.Assumptions
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

class DnsAvailability {
    companion object {
        val ip4DnsAvailable =
            InetAddress.getAllByName("localhost")
                .filter { it is Inet4Address }
                .isNotEmpty()
        val ip6DnsAvailable =
            InetAddress.getAllByName("localhost")
                .filter { it is Inet6Address }
                .isNotEmpty()

        fun assumeIp4Dns(message: String = "IP4 DNS resolution not available") =
            Assumptions.assumeTrue(ip4DnsAvailable, message)

        fun assumeIp6Dns(message: String = "IP6 DNS resolution not available") =
            Assumptions.assumeTrue(ip6DnsAvailable, message)
    }
}
