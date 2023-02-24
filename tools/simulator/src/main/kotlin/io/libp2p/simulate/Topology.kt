package io.libp2p.simulate

import io.libp2p.simulate.topology.CustomTopologyGraph
import java.util.Random

interface Topology {

    var random: Random

    fun generateGraph(verticesCount: Int): TopologyGraph
}

interface TopologyGraph {
    data class Edge(val srcV: Int, val destV: Int)

    val edges: Collection<Edge>

    val vertices get() =
        edges.flatMap { listOf(it.srcV, it.destV) }.distinct().sorted()

    fun calcDiameter(): Int

    fun connect(peers: List<SimPeer>): Network {
        require(peers.size == vertices.size)
        return edges
            .map { peers[it.srcV].connect(peers[it.destV]).join() }
            .let { ImmutableNetworkImpl(it, this) }
    }

    companion object {
        fun customTopology(vararg vertices: Pair<Int, Int>) =
            CustomTopologyGraph(
                vertices.map { Edge(it.first, it.second) }
            )
    }
}

fun Topology.generateAndConnect(peers: List<SimPeer>) = generateGraph(peers.size).connect(peers)
