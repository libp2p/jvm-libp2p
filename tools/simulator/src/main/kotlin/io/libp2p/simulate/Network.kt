package io.libp2p.simulate

interface Network {

    val peers: List<SimPeer>

    val activeConnections: List<SimConnection>
        get() = peers.flatMap { it.connections }.distinct()

    val topologyGraph: TopologyGraph
}

class ImmutableNetworkImpl(
    override val activeConnections: List<SimConnection>,
    override val topologyGraph: TopologyGraph
) : Network {
    override val peers = activeConnections.map { it.dialer }.distinct()
}
