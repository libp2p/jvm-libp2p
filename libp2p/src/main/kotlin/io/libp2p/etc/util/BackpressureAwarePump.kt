package io.libp2p.etc.util

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/**
 * Outbound message together with the promise that should be completed when the write finishes.
 */
data class MessageAndPromise(
    val messagePayload: Any,
    val writePromise: CompletableFuture<Unit>,
)

/**
 * Drains outbound messages while respecting channel backpressure.
 *
 * The pump is driven by two external signals: [onNewOutboundData] tells it that the supplier may
 * now have another message, and [onChannelWritabilityChanged] tells it whether polling new messages
 * is currently allowed. Both methods are synchronized and may be called from different threads.
 *
 * [messageSupplier] returns the next already-consumed outbound message, or `null` when no message is
 * ready. Once a non-null message is returned, the pump writes it even if channel writability changes
 * to false before the supplier future completes. Backpressure only prevents polling the next message.
 *
 * [messageWriter] writes one supplied message and completes with the channel writability observed
 * after that write was scheduled.
 */
class BackpressureAwarePump(
    private val messageWriter: BackpressureAwareAsyncWriter,
    private val messageSupplier: () -> CompletionStage<MessageAndPromise?>
) {
    private enum class State {
        IDLE,
        RUNNING,
        BLOCKED
    }

    private var state = State.IDLE
    private var channelWritable = true
    private var outboundDataGeneration = 0L

    /**
     * Notifies the pump that channel writability changed.
     */
    @Synchronized
    fun onChannelWritabilityChanged(isWritable: Boolean) {
        channelWritable = isWritable
        if (isWritable && state == State.BLOCKED) {
            takeNextMessage()
        }
    }

    /**
     * Notifies the pump that outbound data may now be available from [messageSupplier].
     */
    @Synchronized
    fun onNewOutboundData() {
        outboundDataGeneration++
        if (state == State.IDLE) {
            if (channelWritable) {
                takeNextMessage()
            } else {
                state = State.BLOCKED
            }
        }
    }

    @Synchronized
    private fun takeNextMessage() {
        state = State.RUNNING
        val generationAtStart = outboundDataGeneration
        messageSupplier().whenComplete { message: MessageAndPromise?, error: Throwable? ->
            onMessageTaken(message, error, generationAtStart)
        }
    }

    @Synchronized
    private fun onMessageTaken(
        message: MessageAndPromise?,
        error: Throwable?,
        generationAtStart: Long
    ) {
        when {
            error != null -> state = State.IDLE
            message != null -> writeMessage(message)
            outboundDataGeneration != generationAtStart && channelWritable -> takeNextMessage()
            outboundDataGeneration != generationAtStart -> state = State.BLOCKED
            else -> state = State.IDLE
        }
    }

    @Synchronized
    private fun writeMessage(message: MessageAndPromise) {
        messageWriter.write(message.messagePayload, message.writePromise)
            .whenComplete { isWritable: Boolean?, error: Throwable? ->
                onMessageWritten(isWritable, error)
            }
    }

    @Synchronized
    private fun onMessageWritten(
        isWritable: Boolean?,
        error: Throwable?
    ) {
        when {
            error != null -> state = State.IDLE
            isWritable == false -> {
                channelWritable = false
                state = State.BLOCKED
            }

            channelWritable -> takeNextMessage()
            else -> state = State.BLOCKED
        }
    }
}
