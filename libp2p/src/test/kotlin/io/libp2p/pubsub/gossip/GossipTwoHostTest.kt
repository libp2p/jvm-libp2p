package io.libp2p.pubsub.gossip

import io.libp2p.core.pubsub.MessageApi
import io.libp2p.core.pubsub.Subscriber
import io.libp2p.core.pubsub.Topic
import io.libp2p.etc.types.toByteArray
import io.libp2p.etc.types.toByteBuf
import io.libp2p.mux.mplex.DEFAULT_MAX_MPLEX_FRAME_DATA_LENGTH
import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPromise
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pubsub.pb.Rpc
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class GossipTwoHostTest : TwoGossipHostTestBase() {

    override val params = GossipParams(maxGossipMessageSize = DEFAULT_MAX_MPLEX_FRAME_DATA_LENGTH * 2)
    private val outboundWrites = BatchingOutboundWriteHandler()
    override val gossip1 by lazy { Gossip(router1, debugGossipHandler = outboundWrites) }

    @Test
    fun `test message larger than mplex frame`() {
        connect()

        val topic = Topic("topic")

        val messages = mutableListOf<MessageApi>()
        gossip2.subscribe(Subscriber { messages += it }, topic)

        waitForSubscribed(router1, topic.topic)

        val msgSize = DEFAULT_MAX_MPLEX_FRAME_DATA_LENGTH + 10
        val msgBytes = ByteArray(msgSize) { 0xab.toByte() }

        val res = gossip1.createPublisher(null)
            .publish(msgBytes.toByteBuf(), topic)

        res.get(10, TimeUnit.SECONDS)

        waitFor { messages.isNotEmpty() }

        assertThat(messages)
            .hasSize(1)
            .allMatch { it.data.toByteArray().contentEquals(msgBytes) }
    }

    @Test
    fun `TCP Mplex preserves healthy gossipsub publication order and batching`() {
        connect()

        val topic = Topic("ordered-topic")
        val receivedByHost1 = CopyOnWriteArrayList<MessageApi>()
        val receivedByHost2 = CopyOnWriteArrayList<MessageApi>()
        gossip1.subscribe(Subscriber { receivedByHost1 += it }, topic)
        gossip2.subscribe(Subscriber { receivedByHost2 += it }, topic)
        waitForSubscribed(router1, topic.topic)
        waitForSubscribed(router2, topic.topic)

        val publisher = gossip1.createPublisher(null)
        val payloads = listOf("first", "second", "third")
        outboundWrites.holdNextPublish()
        val publications = payloads.map { payload ->
            publisher.publish(payload.toByteArray().toByteBuf(), topic)
        }
        outboundWrites.awaitHeldPublish()
        router1.getPeerTopics().join()
        outboundWrites.releaseHeldPublish()
        publications.forEach { it.get(10, TimeUnit.SECONDS) }

        waitFor { receivedByHost2.size == payloads.size }

        assertThat(receivedByHost2.map { it.data.toByteArray().decodeToString() }).containsExactlyElementsOf(payloads)
        assertThat(outboundWrites.publishBatchSizes).containsExactly(1, 2)

        gossip2.createPublisher(null)
            .publish("reply".toByteArray().toByteBuf(), topic)
            .get(10, TimeUnit.SECONDS)
        waitFor { receivedByHost1.size == 1 }
        assertThat(receivedByHost1.single().data.toByteArray().decodeToString()).isEqualTo("reply")
    }

    @ChannelHandler.Sharable
    private class BatchingOutboundWriteHandler : ChannelDuplexHandler() {
        data class HeldWrite(
            val ctx: ChannelHandlerContext,
            val msg: Any,
            val promise: ChannelPromise
        )

        val publishBatchSizes = CopyOnWriteArrayList<Int>()
        private val heldWrite = AtomicReference<HeldWrite>()

        @Volatile
        private var holdNextPublish = false

        @Volatile
        private var heldPublishLatch = CountDownLatch(0)

        fun holdNextPublish() {
            publishBatchSizes.clear()
            heldPublishLatch = CountDownLatch(1)
            holdNextPublish = true
        }

        fun awaitHeldPublish() {
            assertThat(heldPublishLatch.await(10, TimeUnit.SECONDS)).isTrue()
        }

        fun releaseHeldPublish() {
            val write = heldWrite.getAndSet(null) ?: throw AssertionError("No held publish write")
            write.ctx.executor().execute {
                write.ctx.writeAndFlush(write.msg, write.promise)
            }
        }

        override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
            if (msg is Rpc.RPC && msg.publishCount > 0) {
                publishBatchSizes += msg.publishCount
                if (holdNextPublish) {
                    holdNextPublish = false
                    heldWrite.set(HeldWrite(ctx, msg, promise))
                    heldPublishLatch.countDown()
                    return
                }
            }
            ctx.write(msg, promise)
        }
    }
}
