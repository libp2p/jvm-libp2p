package io.libp2p.simulate.connection

import io.libp2p.core.Host
import io.libp2p.core.dsl.Builder
import io.libp2p.core.dsl.TransportsBuilder
import io.libp2p.etc.types.lazyVar
import io.libp2p.simulate.Network
import io.libp2p.simulate.TopologyGraph
import io.libp2p.transport.ConnectionUpgrader
import java.util.Random
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicInteger

class LoopbackNetwork : Network {
    var random = Random(0)
    var simExecutor: ScheduledExecutorService by lazyVar { TODO() }

    override val topologyGraph: TopologyGraph
        get() = TODO("Not yet implemented")

    private var ipCounter = AtomicInteger(1)
    internal val ipTransportMap = mutableMapOf<String, LoopbackTransport>()

    fun createLoopbackTransportCtor(): (ConnectionUpgrader) -> LoopbackTransport {
        val newIp = newIp()
        return { upgrader ->
            LoopbackTransport(upgrader, this, simExecutor, newIp).also {
                ipTransportMap[it.localIp] = it
            }
        }
    }

    fun newPeer(host: Host): HostSimPeer =
        HostSimPeer(host).also {
            it.transport // check the right transport
            peers += it
        }

    fun newPeer(fn: Builder.() -> Unit): HostSimPeer {
        val host = (
            object : Builder() {
                init {
                    transports += createLoopbackTransportCtor()
                }

                override fun transports(fn: TransportsBuilder.() -> Unit): Builder {
                    throw UnsupportedOperationException("Transports shouldn't be configured by client code")
                }
            }
            ).apply(fn).build(Builder.Defaults.None)
        return newPeer(host)
    }

    private fun newIp(): String {
        val ip = ipCounter.getAndIncrement()
        return "" + ip.shr(24) +
            "." + ip.shr(16).and(0xFF) +
            "." + ip.shr(8).and(0xFF) +
            "." + ip.and(0xFF)
    }

    override val peers: MutableList<HostSimPeer> = mutableListOf()
}
