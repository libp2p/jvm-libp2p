package io.libp2p.simulate.stats.collect

import io.libp2p.simulate.Network
import io.libp2p.simulate.SimChannelMessageVisitor
import io.libp2p.simulate.stats.StatsFactory
import io.libp2p.simulate.util.MsgSizeEstimator

class GlobalNetworkStatsCollector(
    network: Network,
    private val msgEstimator: MsgSizeEstimator
) : SimChannelMessageVisitor {

    val msgSizeStats = StatsFactory.DEFAULT.createStats()

    val msgCount get() = msgSizeStats.getCount()
    val msgsSize get() = msgSizeStats.getSum().toLong()

    init {
        network.activeConnections.forEach { conn ->
            conn.streams.forEach { stream ->
                listOf(stream.initiatorChannel, stream.acceptorChannel).forEach { channel ->
                    channel.msgVisitors += this
                }
            }
        }
    }

    override fun onInbound(message: Any) {
        msgSizeStats += msgEstimator(message)
    }

    override fun onOutbound(message: Any) {
        // count inbound only
    }
}
