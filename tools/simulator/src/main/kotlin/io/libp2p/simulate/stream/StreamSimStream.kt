package io.libp2p.simulate.stream

import io.libp2p.core.multistream.ProtocolId
import io.libp2p.etc.PROTOCOL
import io.libp2p.etc.types.toVoidCompletableFuture
import io.libp2p.etc.util.netty.nettyInitializer
import io.libp2p.simulate.MessageDelayer
import io.libp2p.simulate.SimStream
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import java.util.concurrent.CompletableFuture

class StreamSimStream(
    override val connection: StreamSimConnection,
    override val streamInitiator: SimStream.StreamInitiator,
    override val streamProtocol: ProtocolId,
    wireLogs: LogLevel? = null
) : SimStream {

    override val initiatorChannel: StreamNettyChannel
    override val acceptorChannel: StreamNettyChannel

    init {
        val from =
            if (streamInitiator == SimStream.StreamInitiator.CONNECTION_DIALER) connection.dialer
            else connection.listener
        val to =
            if (streamInitiator == SimStream.StreamInitiator.CONNECTION_LISTENER) connection.dialer
            else connection.listener

        val fromIsInitiator = from === connection.dialer
        val toIsInitiator = !fromIsInitiator
        val fromInitiatorSign = if (fromIsInitiator) "*" else ""
        val toInitiatorSign = if (toIsInitiator) "*" else ""
        val fromChannelName = "$fromInitiatorSign${from.name}=>$toInitiatorSign${to.name}"
        val toChannelName = "$toInitiatorSign${to.name}=>$fromInitiatorSign${from.name}"

        initiatorChannel =
            newChannel(fromChannelName, from, to, streamProtocol, wireLogs, fromIsInitiator, true)
        acceptorChannel =
            newChannel(toChannelName, to, from, streamProtocol, wireLogs, toIsInitiator, false)

        initiatorChannel.connect(acceptorChannel)
        acceptorChannel.connect(initiatorChannel)
    }

    private fun newChannel(
        channelName: String,
        local: StreamSimPeer<*>,
        remote: StreamSimPeer<*>,
        streamProtocol: ProtocolId,
        wireLogs: LogLevel? = null,
        connectionInitiator: Boolean,
        streamInitiator: Boolean
    ): StreamNettyChannel {

        return StreamNettyChannel(
            channelName,
            this,
            streamInitiator,
            remote.inboundBandwidth,
            local.outboundBandwidth,
            nettyInitializer {
                val channel = it.channel
                wireLogs?.also { channel.pipeline().addFirst(LoggingHandler(channelName, it)) }
                val connection =
                    Libp2pConnectionImpl(
                        remote.address,
                        connectionInitiator,
                        local.keyPair.second,
                        remote.keyPair.second
                    )
                val stream = Libp2pStreamImpl(connection, channel, streamInitiator)
                channel.attr(PROTOCOL).get().complete(streamProtocol)
                local.simHandleStream(stream)
            }
        ).also {
            it.executor = local.simExecutor
            it.currentTime = local.currentTime
            it.msgSizeEstimator = local.msgSizeEstimator
        }
    }

    fun setLatency(latency: MessageDelayer) {
        initiatorChannel.setLatency(latency)
        acceptorChannel.setLatency(latency)
    }
    fun disconnect(): CompletableFuture<Unit> {
        return CompletableFuture.allOf(
            initiatorChannel.close().toVoidCompletableFuture(),
            acceptorChannel.close().toVoidCompletableFuture()
        ).thenApply { }
    }
}
