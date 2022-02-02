package io.libp2p.pubsub

import io.libp2p.etc.types.toProtobuf
import io.libp2p.pubsub.TopicSubscriptionFilter.AllowAllTopicSubscriptionFilter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pubsub.pb.Rpc
import java.util.concurrent.CompletableFuture

class AbstractRouterTest {

    private class TestRouter(val msgValidator: (Rpc.RPCOrBuilder) -> Boolean) :
        AbstractRouter(AllowAllTopicSubscriptionFilter()) {
        override val protocol = PubsubProtocol.Floodsub
        override fun broadcastOutbound(msg: PubsubMessage): CompletableFuture<Unit> =
            CompletableFuture.completedFuture(null)

        override fun broadcastInbound(msgs: List<PubsubMessage>, receivedFrom: PeerHandler) {}
        override fun processControl(ctrl: Rpc.ControlMessage, receivedFrom: PeerHandler) {}

        override fun validateMessageListLimits(msg: Rpc.RPCOrBuilder) = msgValidator(msg)
        fun testMerge(parts: List<Rpc.RPC>): List<Rpc.RPC> = mergeMessageParts(parts)
    }

    private fun Collection<Rpc.RPC>.merge(): Rpc.RPC =
        this.fold(Rpc.RPC.newBuilder()) { bld, part -> bld.mergeFrom(part) }.build()

    @Test
    fun `test many subscriptions split to several messages`() {
        val router = TestRouter { it.subscriptionsCount <= 5 }
        val parts = (0 until 14).map {
            Rpc.RPC.newBuilder().addSubscriptions(
                Rpc.RPC.SubOpts.newBuilder()
                    .setTopicid("topic-$it")
                    .setSubscribe(true)
                    .build()
            ).build()
        }
        val msgs = router.testMerge(parts)

        assertThat(msgs)
            .hasSize(3)
            .allMatch { it.subscriptionsCount <= 5 }

        assertThat(msgs.merge()).isEqualTo(parts.merge())
    }

    @Test
    fun `test few subscriptions don't split to several messages`() {
        val router = TestRouter { it.subscriptionsCount <= 5 }
        val parts = (0 until 5).map {
            Rpc.RPC.newBuilder().addSubscriptions(
                Rpc.RPC.SubOpts.newBuilder()
                    .setTopicid("topic-$it")
                    .setSubscribe(true)
                    .build()
            ).build()
        }
        val msgs = router.testMerge(parts)

        assertThat(msgs).hasSize(1)
        assertThat(msgs.merge()).isEqualTo(parts.merge())
    }

    @Test
    fun `test that split doesn't result in topic publish before subscribe`() {
        val router = TestRouter { it.subscriptionsCount <= 5 }
        val parts = (0 until 6).map {
            Rpc.RPC.newBuilder().addSubscriptions(
                Rpc.RPC.SubOpts.newBuilder()
                    .setTopicid("topic-$it")
                    .setSubscribe(true)
                    .build()
            ).build()
        } + Rpc.RPC.newBuilder().addPublish(
            Rpc.Message.newBuilder()
                .addTopicIDs("topic-5")
                .setData(byteArrayOf(11).toProtobuf())
        ).build()
        val msgs = router.testMerge(parts)

        assertThat(msgs).hasSize(2)
        assertThat(msgs[0].publishCount).isZero()
        assertThat(msgs[1].publishCount).isEqualTo(1)
        assertThat(msgs.merge()).isEqualTo(parts.merge())
    }
}
