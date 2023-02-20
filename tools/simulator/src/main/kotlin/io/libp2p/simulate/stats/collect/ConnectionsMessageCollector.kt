package io.libp2p.simulate.stats.collect

import io.libp2p.pubsub.gossip.CurrentTimeSupplier
import io.libp2p.simulate.Network
import io.libp2p.simulate.SimConnection

open class ConnectionsMessageCollector<MessageT>(
    network: Network,
    private val timeSupplier: CurrentTimeSupplier
) {

    private val connectionCollectors: Map<SimConnection, ConnectionMessageCollector<MessageT>> =
        network.activeConnections.associateWith { conn ->
            ConnectionMessageCollector(conn, timeSupplier)
        }

    val connectionMessages: Map<SimConnection, List<CollectedMessage<MessageT>>> =
        connectionCollectors.mapValues { it.value.deliveredMessages }

    val pendingMessages get() = connectionCollectors
        .flatMap { it.value.pendingMessages }
        .sortedBy { it.sendTime }
}
