package io.libp2p.etc.util

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

data class MessageAndPromise(
    val messagePayload: Any,
    val writePromise: CompletableFuture<Unit>,
)

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
     * Notified when channel writability changes
     */
    @Synchronized
    fun onChannelWritabilityChanged(isWritable: Boolean) {
        channelWritable = isWritable
        if (isWritable && state == State.BLOCKED) {
            takeNextMessage()
        }
    }

    /**
     * Notified when new outbound data is ready to be sent
     */
    @Synchronized
    fun onNewOutboundData() {
        outboundDataGeneration++
        if (state == State.IDLE && channelWritable) {
            takeNextMessage()
        } else if (state == State.IDLE) {
            state = State.BLOCKED
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
            .whenComplete { isWritable: Boolean, error: Throwable? ->
                onMessageWritten(isWritable, error)
            }
    }

    @Synchronized
    private fun onMessageWritten(
        isWritable: Boolean,
        error: Throwable?
    ) {
        when {
            error != null -> state = State.IDLE
            !isWritable -> {
                channelWritable = false
                state = State.BLOCKED
            }

            channelWritable -> takeNextMessage()
            else -> state = State.BLOCKED
        }
    }
}
