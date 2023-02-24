package io.libp2p.simulate.topology

import io.libp2p.simulate.TopologyGraph
import org.jgrapht.Graph
import org.jgrapht.GraphMetrics

class JGraphtTopologyGraph(
    val graph: Graph<Int, Any>
) : TopologyGraph {

    override val edges: Collection<TopologyGraph.Edge> =
        graph.edgeSet()
            .map { TopologyGraph.Edge(graph.getEdgeSource(it), graph.getEdgeTarget(it)) }

    init {
        assert(edges.all { it.srcV != it.destV })

        // check no duplicate edges between any of to vertices
        assert(
            edges
                .map { listOf(it.srcV, it.destV).sorted() }
                .distinct()
                .size == edges.size
        )
    }

    override fun calcDiameter(): Int = GraphMetrics.getDiameter(graph).toInt()
}
