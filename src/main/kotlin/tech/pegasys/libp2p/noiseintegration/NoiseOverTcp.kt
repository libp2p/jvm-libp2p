package tech.pegasys.libp2p.noiseintegration

import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.transport.ConnectionUpgrader
import io.libp2p.core.transport.tcp.TcpTransport

class NoiseOverTcp {
    companion object {
        @JvmStatic
        fun validMultiaddrs() = listOf(
            "/ip4/1.2.3.4/tcp/1234",
            "/ip6/fe80::6f77:b303:aa6e:a16/tcp/42"
        ).map { Multiaddr(it) }

        @JvmStatic
        fun invalidMultiaddrs() = listOf(
            "/ip4/1.2.3.4/udp/42",
            "/unix/a/file/named/tcp"
        ).map { Multiaddr(it) }
    }

    private val upgrader = ConnectionUpgrader(emptyList(), emptyList())

    fun setupTcpTransport(addr: Multiaddr): Boolean {
        val tcp = TcpTransport(upgrader)
        assert(tcp.handles(addr))
        return true
    }
}

fun main(args: Array<String>) {
    val noisytcp = NoiseOverTcp()
    noisytcp.setupTcpTransport(NoiseOverTcp.validMultiaddrs().get(0))
}