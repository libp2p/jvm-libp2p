package io.libp2p.simulate.topology

import io.libp2p.etc.types.copy
import org.jgrapht.Graph
import org.jgrapht.generate.RandomRegularGraphGenerator
import org.jgrapht.graph.DefaultUndirectedGraph

data class RandomNPeers(val peersCount: Int = 10) : AbstractGraphTopology() {

    override fun buildGraph(vertexCount: Int): Graph<Int, Any> {
        val peersVertex = (0 until vertexCount).iterator()
        val graph: Graph<Int, Any> = DefaultUndirectedGraph(peersVertex::next, { Any() }, false)
        RandomRegularGraphGenerator<Int, Any>(vertexCount, peersCount, random).generateGraph(graph)
        graph.edgeSet().copy().forEach {
            if (graph.getEdgeSource(it) == graph.getEdgeTarget(it)) {
                graph.removeEdge(it)
            }
        }
        return graph
    }
}
