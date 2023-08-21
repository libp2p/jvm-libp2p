package io.libp2p.simulate

import io.libp2p.core.multistream.ProtocolId

interface SimStream {

    enum class StreamInitiator { CONNECTION_DIALER, CONNECTION_LISTENER }

    val connection: SimConnection
    val streamInitiator: StreamInitiator
    val streamProtocol: ProtocolId

    val streamInitiatorPeer get() =
        when (streamInitiator) {
            StreamInitiator.CONNECTION_DIALER -> connection.dialer
            StreamInitiator.CONNECTION_LISTENER -> connection.listener
        }
    val streamAcceptorPeer get() =
        when (streamInitiator) {
            StreamInitiator.CONNECTION_DIALER -> connection.listener
            StreamInitiator.CONNECTION_LISTENER -> connection.dialer
        }

    val initiatorChannel: SimChannel
    val acceptorChannel: SimChannel
}
