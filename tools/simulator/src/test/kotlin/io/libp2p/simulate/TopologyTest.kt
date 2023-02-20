package io.libp2p.simulate

import io.libp2p.simulate.topology.AllToAllTopology
import io.libp2p.simulate.topology.RandomNPeers
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Test
import java.util.*

class TopologyTest {

    fun TopologyGraph.getVertexInboundEdges(vertex: Int): Collection<TopologyGraph.Edge> = edges.filter { it.destV == vertex }
    fun TopologyGraph.getVertexOutboundEdges(vertex: Int): Collection<TopologyGraph.Edge> = edges.filter { it.srcV == vertex }
    fun TopologyGraph.getVertexEdges(vertex: Int) = getVertexInboundEdges(vertex) + getVertexOutboundEdges(vertex)
    fun TopologyGraph.Edge.getOther(vertex: Int) =
        when (vertex) {
            srcV -> destV
            destV -> srcV
            else -> throw IllegalArgumentException()
        }

    @Test
    fun allToAllTopologyTest() {
        val graph = AllToAllTopology().generateGraph(100)

        assertThat(graph.vertices).isEqualTo((0 until 100).toList())
        assertThat(graph.edges).hasSize(4950)
    }

    @Test
    fun randomNTopologyTest() {
        val rnd = Random(1)
        val topology = RandomNPeers(30)
            .also { it.random = rnd }

        repeat(100) {
            val graph = topology.generateGraph(300)

            assertThat(graph.vertices).isEqualTo((0 until 300).toList())
            graph.vertices.forEach { vertex ->
                val vertexEdges = graph.getVertexEdges(vertex)
                assertThat(vertexEdges.map { it.getOther(vertex) }.distinct()).hasSize(vertexEdges.size)
                assertThat(vertexEdges.size).isCloseTo(30, Offset.offset(8))
            }
        }
    }
}
