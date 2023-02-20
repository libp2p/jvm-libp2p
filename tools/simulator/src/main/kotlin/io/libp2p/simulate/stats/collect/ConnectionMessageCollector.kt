package io.libp2p.simulate.stats.collect

import io.libp2p.pubsub.gossip.CurrentTimeSupplier
import io.libp2p.simulate.SimChannelMessageVisitor
import io.libp2p.simulate.SimConnection
import io.libp2p.simulate.SimPeer
import java.util.IdentityHashMap

@Suppress("UNCHECKED_CAST")
open class ConnectionMessageCollector<MessageT>(
    val connection: SimConnection,
    private val timeSupplier: CurrentTimeSupplier
) {

    private val deliveredMessagesWrite = mutableListOf<CollectedMessage<MessageT>>()
    val deliveredMessages: List<CollectedMessage<MessageT>> = deliveredMessagesWrite

    private val sentMessages = IdentityHashMap<MessageT, CollectedMessage<MessageT>>()
    val pendingMessages: Collection<CollectedMessage<MessageT>> get() = sentMessages.values

    init {
        connection.streams.forEach { stream ->
            stream.initiatorChannel.msgVisitors += object : SimChannelMessageVisitor {
                override fun onOutbound(message: Any) {
                    message as MessageT
                    sentMessages[message] = CollectedMessage(
                        connection,
                        stream.streamInitiatorPeer,
                        timeSupplier(),
                        Long.MAX_VALUE,
                        message
                    )
                }
                override fun onInbound(message: Any) {
                    val sentMessage = sentMessages.remove(message as MessageT)
                        ?: throw IllegalStateException("Pending message not found for message $message at ${timeSupplier()}")
                    deliveredMessagesWrite += sentMessage.copy(receiveTime = timeSupplier())
                }
            }

            stream.acceptorChannel.msgVisitors += object : SimChannelMessageVisitor {
                override fun onOutbound(message: Any) {
                    message as MessageT
                    sentMessages[message] = CollectedMessage(
                        connection,
                        stream.streamAcceptorPeer,
                        timeSupplier(),
                        Long.MAX_VALUE,
                        message
                    )
                }
                override fun onInbound(message: Any) {
                    val sentMessage = sentMessages.remove(message as MessageT)
                        ?: throw IllegalStateException("Pending message not found for message $message at ${timeSupplier()}")
                    deliveredMessagesWrite += sentMessage.copy(receiveTime = timeSupplier())
                }
            }
        }
    }
}

data class CollectedMessage<T>(
    val connection: SimConnection,
    val sendingPeer: SimPeer,
    val sendTime: Long,
    val receiveTime: Long,
    val message: T
) {
    val delay get() = receiveTime - sendTime
    val receivingPeer get() = if (connection.dialer === sendingPeer) connection.listener else connection.dialer

    fun <R> withMessage(msg: R): CollectedMessage<R> =
        CollectedMessage(connection, sendingPeer, sendTime, receiveTime, msg)

    override fun toString(): String {
        return "CollectedMessage[$sendingPeer => $receivingPeer, $sendTime --($delay)-> $receiveTime]"
    }
}
