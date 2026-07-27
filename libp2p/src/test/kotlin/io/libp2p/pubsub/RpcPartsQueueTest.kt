package io.libp2p.pubsub

import io.libp2p.core.ConnectionClosedException
import io.libp2p.etc.types.getX
import io.libp2p.etc.types.toProtobuf
import io.libp2p.pubsub.gossip.DefaultGossipRpcPartsQueue
import io.libp2p.pubsub.gossip.builders.GossipParamsBuilder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import pubsub.pb.Rpc
import java.util.concurrent.CompletableFuture

class RpcPartsQueueTest {

    @Test
    fun `merged promise completes publish promises from the same batch`() {
        val queue = DefaultRpcPartsQueue()
        val publishPromise1 = CompletableFuture<Unit>()
        val publishPromise2 = CompletableFuture<Unit>()

        queue.addPublish(createRpcMessage("topic", "data-1"), publishPromise1)
        queue.addPublish(createRpcMessage("topic", "silent-data"))
        queue.addPublish(createRpcMessage("topic", "data-2"), publishPromise2)

        val batch = queue.takeBatch()
        val writePromise = batch.mergePromises()
        val rpc = batch.mergeRpc()

        assertThat(queue.isEmpty()).isTrue()
        assertThat(rpc.publishList.map { it.data.toStringUtf8() })
            .containsExactly("data-1", "silent-data", "data-2")
        assertThat(publishPromise1).isNotDone()
        assertThat(publishPromise2).isNotDone()

        writePromise.complete(Unit)

        assertThat(publishPromise1).isCompleted
        assertThat(publishPromise2).isCompleted
    }

    @Test
    fun `merged promise fails publish promises from the same batch`() {
        val queue = DefaultRpcPartsQueue()
        val publishPromise = CompletableFuture<Unit>()
        val writeFailure = RuntimeException("write failed")

        queue.addPublish(createRpcMessage("topic", "data"), publishPromise)

        val writePromise = queue.takeBatch().mergePromises()
        writePromise.completeExceptionally(writeFailure)

        assertThat(publishPromise).isCompletedExceptionally
        assertThat(assertThrows(RuntimeException::class.java) { publishPromise.getX() })
            .isSameAs(writeFailure)
    }

    @Test
    fun `batched queue keeps later publish promises pending until their batch is written`() {
        val queue = DefaultGossipRpcPartsQueue(
            GossipParamsBuilder()
                .maxPublishedMessages(1)
                .maxIHaveLength(Int.MAX_VALUE)
                .build()
        )
        val publishPromise1 = CompletableFuture<Unit>()
        val publishPromise2 = CompletableFuture<Unit>()

        queue.addPublish(createRpcMessage("topic", "data-1"), publishPromise1)
        queue.addPublish(createRpcMessage("topic", "data-2"), publishPromise2)

        val batch1 = queue.takeBatch()
        val writePromise1 = batch1.mergePromises()
        val rpc1 = batch1.mergeRpc()

        assertThat(rpc1.publishList.map { it.data.toStringUtf8() }).containsExactly("data-1")
        assertThat(queue.isEmpty()).isFalse()

        writePromise1.complete(Unit)

        assertThat(publishPromise1).isCompleted
        assertThat(publishPromise2).isNotDone()

        val batch2 = queue.takeBatch()
        val writePromise2 = batch2.mergePromises()
        val rpc2 = batch2.mergeRpc()

        assertThat(rpc2.publishList.map { it.data.toStringUtf8() }).containsExactly("data-2")

        writePromise2.complete(Unit)

        assertThat(publishPromise2).isCompleted
        assertThat(queue.isEmpty()).isTrue()
    }

    @Test
    fun `abort fails pending publish promises`() {
        val queue = DefaultRpcPartsQueue()
        val publishPromise = CompletableFuture<Unit>()

        queue.addPublish(createRpcMessage("topic", "data"), publishPromise)
        queue.addPublish(createRpcMessage("topic", "silent-data"))

        queue.abort(ConnectionClosedException())

        assertThat(queue.isEmpty()).isTrue()
        assertThat(publishPromise).isCompletedExceptionally
        assertThrows(ConnectionClosedException::class.java) { publishPromise.getX() }
    }

    private fun createRpcMessage(topic: String, data: String): Rpc.Message =
        Rpc.Message.newBuilder()
            .addTopicIDs(topic)
            .setData(data.toByteArray().toProtobuf())
            .build()
}
