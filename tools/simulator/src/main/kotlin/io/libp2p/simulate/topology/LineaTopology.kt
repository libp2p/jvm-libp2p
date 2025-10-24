package io.libp2p.simulate.topology

import io.libp2p.simulate.Topology
import io.libp2p.simulate.TopologyGraph
import java.util.Random

data class LineaTopology(
    val sequencerCount: Int = 1,
    val internalNodeCount: Int = 5,
    val edgeNodeCount: Int = 5,
    val partnerNodeCount: Int = 300
): Topology {
    override var random: Random
        get() = TODO("Not yet implemented")
        set(_) {}

    override fun generateGraph(verticesCount: Int): TopologyGraph {
        val sequencerNodeEdges = (0 until sequencerCount).flatMap { src ->
            (src + 1 until sequencerCount).map { dest ->
                TopologyGraph.Edge(src, dest)
            }
        }
        val internalNodeStartIndex = sequencerCount
        val internalNodeEdges = (0 until internalNodeCount).flatMap { src ->
            (src + 1 until internalNodeCount).map { dest ->
                TopologyGraph.Edge(internalNodeStartIndex + src, internalNodeStartIndex + dest)
            }
        }
        val sequencerInternalNodeEdges = (0 until sequencerCount).flatMap { sequencerNode ->
            (0 until internalNodeCount).map { internalNode ->
                TopologyGraph.Edge(sequencerNode, internalNodeStartIndex + internalNode)
            }
        }
        val edgeNodeStartIndex = sequencerCount + internalNodeCount
        val internalEdgeNodeEdges = (0 until internalNodeCount).flatMap { internalNode ->
            (0 until edgeNodeCount).map { edgeNode ->
                TopologyGraph.Edge(internalNodeStartIndex + internalNode, edgeNodeStartIndex + edgeNode)
            }
        }
        val partnerNodeStartIndex = sequencerCount + internalNodeCount + edgeNodeCount
        val edgePartnerNodeEdges = (0 until edgeNodeCount).flatMap { edgeNode ->
            (0 until partnerNodeCount).map { partnerNode ->
                TopologyGraph.Edge(edgeNodeStartIndex + edgeNode, partnerNodeStartIndex + partnerNode)
            }
        }

        val allEdges = mutableListOf<TopologyGraph.Edge>()
        allEdges.addAll(sequencerNodeEdges)
        allEdges.addAll(internalNodeEdges)
        allEdges.addAll(sequencerInternalNodeEdges)
        allEdges.addAll(internalEdgeNodeEdges)
        allEdges.addAll(edgePartnerNodeEdges)

        return CustomTopologyGraph(allEdges)
    }
}