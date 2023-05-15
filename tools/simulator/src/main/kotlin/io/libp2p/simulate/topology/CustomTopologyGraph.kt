package io.libp2p.simulate.topology

import io.libp2p.simulate.TopologyGraph

class CustomTopologyGraph(
    override val edges: Collection<TopologyGraph.Edge>
) : TopologyGraph {

    override fun calcDiameter(): Int {
        TODO("Not yet implemented")
    }
}
