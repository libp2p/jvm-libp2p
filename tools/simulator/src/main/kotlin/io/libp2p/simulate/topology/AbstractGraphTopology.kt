package io.libp2p.simulate.topology

import io.libp2p.simulate.*
import org.jgrapht.Graph
import java.util.*

abstract class AbstractGraphTopology : Topology {

    override var random = Random()

    abstract fun buildGraph(vertexCount: Int): Graph<Int, Any>

    override fun generateGraph(verticesCount: Int): TopologyGraph =
        JGraphtTopologyGraph(buildGraph(verticesCount))
}
