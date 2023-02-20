package io.libp2p.simulate

import io.libp2p.simulate.topology.ClusteredNPeers
import io.libp2p.simulate.topology.WEdge
import io.libp2p.simulate.topology.WVertex
import org.jgrapht.Graph
import org.jgrapht.generate.BarabasiAlbertGraphGenerator
import org.jgrapht.generate.RandomRegularGraphGenerator
import org.jgrapht.graph.DefaultUndirectedGraph
import org.junit.jupiter.api.Test
import java.lang.Integer.max
import java.lang.Integer.min
import java.util.Random
import java.util.concurrent.atomic.AtomicInteger

class GraphTest {

    @Test
    fun a() {
//        val random = Random(0)
//        val peersCount = 10
        val vertexes = List(1000) { Any() }
        val vertexIt = vertexes.iterator()
        val graph = DefaultUndirectedGraph(vertexIt::next, { Any() }, false)
//        RandomRegularGraphGenerator<Any, Any>(vertexes.size, peersCount, random).generateGraph(graph)
        BarabasiAlbertGraphGenerator<Any, Any>(100, 10, 1000).generateGraph(graph)

        for (v in vertexes) {
            println(graph.edgesOf(v).size)
        }

//        val graphMeasurer = GraphMeasurer(graph)
//        graphMeasurer

//        val diameter = GraphMetrics.getDiameter(graph)
//        val girth = GraphMetrics.getGirth(graph)
//        val triangles = 0//GraphMetrics.getNumberOfTriangles(graph)
//
//        println("Graph metrics: $diameter, $girth, $triangles")
    }

    @Test
    fun b() {
        val list = List(4) { it }
        val filter = list.indices
            .flatMap { i -> list.indices.map { min(i, it) to max(i, it) } }
            .filter { it.first != it.second }
            .distinct()
        println(filter)
    }

    @Test
    fun c() {
        val clusters = DefaultUndirectedGraph<WVertex, WEdge>(WEdge::class.java)
        val c1 = WVertex(0.5)
        val c2 = WVertex(0.5)
        clusters.addVertex(c1)
        clusters.addVertex(c2)
        clusters.addEdge(c1, c2, WEdge(1))
        val nPeers = ClusteredNPeers(2, clusters).also {
            it.random = Random(1)
        }
        val graph = nPeers.buildGraph(20)
        println(graph)
    }

    @Test
    fun d() {
        for (i in 0..100) {
            val random = Random(i.toLong())
            val vSupp = AtomicInteger()
            val eSupp = AtomicInteger()
            val graph = DefaultUndirectedGraph({ vSupp.getAndIncrement() }, { eSupp.getAndIncrement() }, false)
//            generateRandomGraph(graph, 4, 2, random)
            RandomRegularGraphGenerator<Int, Int>(5000, 10, random).generateGraph(graph)

            val min = graph.vertexSet().map { graph.edgesOf(it).size }.minOrNull()
            val max = graph.vertexSet().map { graph.edgesOf(it).size }.maxOrNull()
            val hasLoops = graph.edgeSet().any { graph.getEdgeSource(it) == graph.getEdgeTarget(it) }
            println("$min, $max, $hasLoops")
        }
    }

    fun <V, E> generateRandomGraph(graph: Graph<V, E>, vertexCount: Int, d: Int, random: Random = Random()) {
        val vertexes = (1..vertexCount).map { graph.addVertex() }
        val remaining = vertexes.toMutableList()
        for (v in vertexes) {
            remaining -= v
            remaining
                .filter { graph.edgesOf(it).size < d }
                .shuffled(random)
                .takeLast(d - graph.edgesOf(v).size)
                .forEach {
                    graph.addEdge(v, it)
                }
        }
    }
}
