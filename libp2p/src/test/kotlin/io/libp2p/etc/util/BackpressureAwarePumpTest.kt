package io.libp2p.etc.util

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BackpressureAwarePumpTest {
    @Test
    fun `drains messages until the supplier is empty`() {
        val suppliedMessages = ConcurrentLinkedQueue<CompletableFuture<MessageAndPromise?>>()
        val writes = ConcurrentLinkedQueue<Pair<Any, CompletableFuture<Boolean>>>()
        val writer =
            BackpressureAwarePump(
                { message ->
                    CompletableFuture<Boolean>().also { writes.add(message.message to it) }
                },
                {
                    CompletableFuture<MessageAndPromise?>().also(suppliedMessages::add)
                }
            )

        writer.onNewOutboundData()
        assertThat(suppliedMessages).hasSize(1)

        suppliedMessages.remove().complete(message("first"))
        assertThat(writes.map { it.first }).containsExactly("first")

        writes.remove().second.complete(true)
        assertThat(suppliedMessages).hasSize(1)

        suppliedMessages.remove().complete(message("second"))
        assertThat(writes.map { it.first }).containsExactly("second")

        writes.remove().second.complete(true)
        suppliedMessages.remove().complete(null)
        assertThat(suppliedMessages).isEmpty()

        writer.onNewOutboundData()
        assertThat(suppliedMessages).hasSize(1)
    }

    @Test
    fun `waits for channel availability after backpressure`() {
        val suppliedMessages = ConcurrentLinkedQueue<CompletableFuture<MessageAndPromise?>>()
        val writes = ConcurrentLinkedQueue<CompletableFuture<Boolean>>()
        val writer =
            BackpressureAwarePump(
                {
                    CompletableFuture<Boolean>().also(writes::add)
                },
                {
                    CompletableFuture<MessageAndPromise?>().also(suppliedMessages::add)
                }
            )

        writer.onNewOutboundData()
        suppliedMessages.remove().complete(message("message"))
        writes.remove().complete(false)

        writer.onNewOutboundData()
        assertThat(suppliedMessages).isEmpty()

        writer.onChannelWritabilityChanged(true)
        assertThat(suppliedMessages).hasSize(1)
    }

    @Test
    fun `does not poll while the channel is not writable`() {
        val suppliedMessages = ConcurrentLinkedQueue<CompletableFuture<MessageAndPromise?>>()
        val writer =
            BackpressureAwarePump(
                { CompletableFuture.completedFuture(true) },
                {
                    CompletableFuture<MessageAndPromise?>().also(suppliedMessages::add)
                }
            )

        writer.onChannelWritabilityChanged(false)
        writer.onNewOutboundData()
        assertThat(suppliedMessages).isEmpty()

        writer.onChannelWritabilityChanged(true)
        assertThat(suppliedMessages).hasSize(1)
    }

    @Test
    fun `writes a message taken while the channel becomes not writable`() {
        val suppliedMessages = ConcurrentLinkedQueue<CompletableFuture<MessageAndPromise?>>()
        val writes = ConcurrentLinkedQueue<Any>()
        val writer =
            BackpressureAwarePump(
                { message ->
                    writes.add(message.message)
                    CompletableFuture<Boolean>()
                },
                {
                    CompletableFuture<MessageAndPromise?>().also(suppliedMessages::add)
                }
            )

        writer.onNewOutboundData()
        writer.onChannelWritabilityChanged(false)
        suppliedMessages.remove().complete(message("message"))
        assertThat(writes).containsExactly("message")
    }

    @Test
    fun `stops draining when writability changes during a write`() {
        val suppliedMessages = ConcurrentLinkedQueue<CompletableFuture<MessageAndPromise?>>()
        val writes = ConcurrentLinkedQueue<CompletableFuture<Boolean>>()
        val writer =
            BackpressureAwarePump(
                {
                    CompletableFuture<Boolean>().also(writes::add)
                },
                {
                    CompletableFuture<MessageAndPromise?>().also(suppliedMessages::add)
                }
            )

        writer.onNewOutboundData()
        suppliedMessages.remove().complete(message("message"))
        writer.onChannelWritabilityChanged(false)
        writes.remove().complete(true)
        assertThat(suppliedMessages).isEmpty()

        writer.onChannelWritabilityChanged(true)
        assertThat(suppliedMessages).hasSize(1)
    }

    @Test
    fun `does not lose data notification while a poll is in flight`() {
        val suppliedMessages = ConcurrentLinkedQueue<CompletableFuture<MessageAndPromise?>>()
        val writer =
            BackpressureAwarePump(
                { CompletableFuture.completedFuture(true) },
                {
                    CompletableFuture<MessageAndPromise?>().also(suppliedMessages::add)
                }
            )

        writer.onNewOutboundData()
        val firstPoll = suppliedMessages.remove()
        writer.onNewOutboundData()

        firstPoll.complete(null)
        assertThat(suppliedMessages).hasSize(1)

        suppliedMessages.remove().complete(null)
        assertThat(suppliedMessages).isEmpty()
    }

    @Test
    fun `serializes notifications from different threads`() {
        val suppliedMessages = ConcurrentLinkedQueue<CompletableFuture<MessageAndPromise?>>()
        val writer =
            BackpressureAwarePump(
                { CompletableFuture.completedFuture(true) },
                {
                    CompletableFuture<MessageAndPromise?>().also(suppliedMessages::add)
                }
            )
        writer.onNewOutboundData()
        val firstPoll = suppliedMessages.remove()

        val executor = Executors.newFixedThreadPool(8)
        try {
            repeat(1_000) {
                executor.execute(writer::onNewOutboundData)
            }
            executor.shutdown()
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue()
        } finally {
            executor.shutdownNow()
        }

        assertThat(suppliedMessages).isEmpty()
        firstPoll.complete(null)
        assertThat(suppliedMessages).hasSize(1)
    }

    private fun message(value: String) = MessageAndPromise(value, CompletableFuture())
}
