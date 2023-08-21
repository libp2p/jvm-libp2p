package io.libp2p.simulate.topology

import io.libp2p.simulate.Topology
import io.libp2p.simulate.TopologyGraph
import java.util.*

class FixedTopology(
    val graph: TopologyGraph
) : Topology {

    override fun generateGraph(verticesCount: Int): TopologyGraph {
        require(verticesCount == graph.vertices.size)
        return graph
    }

    override var random: Random
        get() = TODO("Not yet implemented")
        set(_) {}
}

fun TopologyGraph.asFixedTopology() = FixedTopology(this)
