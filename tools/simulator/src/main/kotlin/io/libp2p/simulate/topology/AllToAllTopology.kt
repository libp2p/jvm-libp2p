package io.libp2p.simulate.topology

import io.libp2p.simulate.*
import java.util.*

class AllToAllTopology : Topology {

    override var random: Random
        get() = TODO("Not yet implemented")
        set(_) {}

    override fun generateGraph(verticesCount: Int): TopologyGraph =
        (0 until verticesCount)
            .flatMap { src ->
                (src + 1 until verticesCount)
                    .map { dest ->
                        TopologyGraph.Edge(src, dest)
                    }
            }
            .let { CustomTopologyGraph(it) }
}
