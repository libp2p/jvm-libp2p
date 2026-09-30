package io.libp2p.pubsub.gossip

import com.google.common.util.concurrent.AtomicDouble
import io.libp2p.core.PeerId
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.pubsub.ValidationResult
import io.libp2p.etc.types.seconds
import io.libp2p.pubsub.DeterministicFuzz
import io.libp2p.pubsub.DeterministicFuzz.Companion.createGossipFuzzRouterFactory
import io.libp2p.pubsub.DeterministicFuzz.Companion.createMockFuzzRouterFactory
import io.libp2p.pubsub.MessageRejectReason
import io.libp2p.pubsub.MockRouter
import io.libp2p.pubsub.PubsubMessage
import io.libp2p.pubsub.PubsubProtocol
import io.libp2p.pubsub.PubsubRouterMessageValidator
import io.libp2p.pubsub.Topic
import io.libp2p.pubsub.gossip.builders.GossipRouterBuilder
import io.netty.handler.logging.LogLevel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pubsub.pb.Rpc
import java.util.Optional
import java.util.concurrent.CompletableFuture

/**
 * Covers the notification hooks that exist so an embedder can observe the router:
 * the reject reason on [GossipRouterEventListener.notifyUnseenInvalidMessage], and the
 * RPC-level hooks that make control messages (IHAVE/IWANT/GRAFT/PRUNE) observable.
 */
class GossipRouterNotificationHooksTest : GossipTestsBase() {

    private class RecordingListener : GossipRouterEventListener {
        val rejectReasons = mutableListOf<MessageRejectReason>()
        val rpcReceived = mutableListOf<Rpc.RPC>()
        val rpcSent = mutableListOf<Rpc.RPC>()
        val rpcDropped = mutableListOf<Rpc.RPC>()
        val ignoredMessages = mutableListOf<PubsubMessage>()
        val nonSubscribedMessages = mutableListOf<Rpc.Message>()
        val subscribed = mutableListOf<Topic>()
        val unsubscribed = mutableListOf<Topic>()

        override fun notifyUnseenIgnoredMessage(peerId: PeerId, msg: PubsubMessage) {
            ignoredMessages += msg
        }

        override fun notifyNonSubscribedMessage(peerId: PeerId, msg: Rpc.Message) {
            nonSubscribedMessages += msg
        }

        override fun notifySubscribed(topic: Topic) {
            subscribed += topic
        }

        override fun notifyUnsubscribed(topic: Topic) {
            unsubscribed += topic
        }

        override fun notifyUnseenInvalidMessage(
            peerId: PeerId,
            msg: PubsubMessage,
            reason: MessageRejectReason
        ) {
            rejectReasons += reason
        }

        override fun notifyRpcReceived(peerId: PeerId, rpc: Rpc.RPC) {
            rpcReceived += rpc
        }

        override fun notifyRpcSent(peerId: PeerId, rpc: Rpc.RPC) {
            rpcSent += rpc
        }

        override fun notifyRpcDropped(peerId: PeerId, rpc: Rpc.RPC) {
            rpcDropped += rpc
        }

        override fun notifyDisconnected(peerId: PeerId) {}
        override fun notifyConnected(peerId: PeerId, peerAddress: Multiaddr) {}
        override fun notifyUnseenMessage(peerId: PeerId, msg: PubsubMessage) {}
        override fun notifySeenMessage(
            peerId: PeerId,
            msg: PubsubMessage,
            validationResult: Optional<ValidationResult>
        ) {}
        override fun notifyUnseenValidMessage(peerId: PeerId, msg: PubsubMessage) {}
        override fun notifyMeshed(peerId: PeerId, topic: Topic) {}
        override fun notifyPruned(peerId: PeerId, topic: Topic) {}
        override fun notifyRouterMisbehavior(peerId: PeerId, count: Int) {}
        override fun notifySlowPeer(peerId: PeerId) {}
    }

    private class Harness(
        messageValidator: PubsubRouterMessageValidator,
        scoreParams: GossipScoreParams = GossipScoreParams()
    ) {
        val listener = RecordingListener()
        val fuzz = DeterministicFuzz()
        private val builderFactory = {
            GossipRouterBuilder(protocol = PubsubProtocol.Gossip_V_1_2, scoreParams = scoreParams).also {
                it.messageValidator = messageValidator
                it.gossipRouterEventListeners += listener
            }
        }
        val router1 = fuzz.createTestRouter(createGossipFuzzRouterFactory(builderFactory))
        val router2 = fuzz.createTestRouter(createMockFuzzRouterFactory())
        val gossipRouter = router1.router as GossipRouter
        val mockRouter = router2.router as MockRouter

        init {
            router1.connectSemiDuplex(router2, null, LogLevel.ERROR)
        }

        fun subscribeBoth(topic: Topic) {
            mockRouter.subscribe(topic)
            gossipRouter.subscribe(topic)
            fuzz.timeController.addTime(2.seconds)
        }
    }

    private val acceptEverything = PubsubRouterMessageValidator { }
    private val rejectEverything = PubsubRouterMessageValidator { throw IllegalArgumentException("nope") }

    @Test
    fun `a message the wire validator rejects is reported as ValidationFailed`() {
        val test = Harness(rejectEverything)
        test.gossipRouter.initHandler { CompletableFuture.completedFuture(ValidationResult.Valid) }
        test.subscribeBoth("topic1")

        test.mockRouter.sendToSingle(publishRpc("topic1", 0L))
        test.fuzz.timeController.addTime(1.seconds)

        assertThat(test.listener.rejectReasons).containsExactly(MessageRejectReason.ValidationFailed)
    }

    @Test
    fun `a message the application handler rejects is reported as RejectedByHandler`() {
        val test = Harness(acceptEverything)
        test.gossipRouter.initHandler { CompletableFuture.completedFuture(ValidationResult.Invalid) }
        test.subscribeBoth("topic1")

        test.mockRouter.sendToSingle(publishRpc("topic1", 0L))
        test.fuzz.timeController.addTime(1.seconds)

        assertThat(test.listener.rejectReasons).containsExactly(MessageRejectReason.RejectedByHandler)
    }

    @Test
    fun `a valid message is not reported as rejected`() {
        val test = Harness(acceptEverything)
        test.gossipRouter.initHandler { CompletableFuture.completedFuture(ValidationResult.Valid) }
        test.subscribeBoth("topic1")

        test.mockRouter.sendToSingle(publishRpc("topic1", 0L))
        test.fuzz.timeController.addTime(1.seconds)

        assertThat(test.listener.rejectReasons).isEmpty()
    }

    @Test
    fun `inbound control messages are observable through notifyRpcReceived`() {
        val test = Harness(acceptEverything)
        test.gossipRouter.initHandler { CompletableFuture.completedFuture(ValidationResult.Valid) }
        test.subscribeBoth("topic1")
        test.listener.rpcReceived.clear()

        val graft = Rpc.RPC.newBuilder().setControl(
            Rpc.ControlMessage.newBuilder()
                .addGraft(Rpc.ControlGraft.newBuilder().setTopicID("topic1"))
        ).build()
        test.mockRouter.sendToSingle(graft)
        test.fuzz.timeController.addTime(1.seconds)

        // The GRAFT count is what Teku cannot see without this hook.
        assertThat(test.listener.rpcReceived.sumOf { it.control.graftCount }).isEqualTo(1)
    }

    @Test
    fun `outbound RPCs are observable through notifyRpcSent`() {
        val test = Harness(acceptEverything)
        test.gossipRouter.initHandler { CompletableFuture.completedFuture(ValidationResult.Valid) }
        test.subscribeBoth("topic1")

        // Subscribing makes the router announce the subscription to its peer.
        assertThat(test.listener.rpcSent.sumOf { it.subscriptionsCount }).isGreaterThan(0)
    }

    @Test
    fun `an RPC from a graylisted peer is reported dropped, not received`() {
        val appScore = AtomicDouble()
        val test = Harness(
            acceptEverything,
            GossipScoreParams(
                peerScoreParams = GossipPeerScoreParams(
                    appSpecificScore = { appScore.get() },
                    appSpecificWeight = 1.0
                ),
                graylistThreshold = -100.0
            )
        )
        test.gossipRouter.initHandler { CompletableFuture.completedFuture(ValidationResult.Valid) }
        test.subscribeBoth("topic1")

        // Below the graylist threshold the router stops accepting anything from this peer.
        appScore.set(-100500.0)
        test.fuzz.timeController.addTime(2.seconds)
        test.listener.rpcReceived.clear()
        test.listener.rpcDropped.clear()

        test.mockRouter.sendToSingle(publishRpc("topic1", 0L))
        test.fuzz.timeController.addTime(1.seconds)

        assertThat(test.listener.rpcDropped).hasSize(1)
        assertThat(test.listener.rpcReceived).isEmpty()
    }

    @Test
    fun `a message the handler ignores is reported ignored, not rejected`() {
        val test = Harness(acceptEverything)
        test.gossipRouter.initHandler { CompletableFuture.completedFuture(ValidationResult.Ignore) }
        test.subscribeBoth("topic1")

        test.mockRouter.sendToSingle(publishRpc("topic1", 0L))
        test.fuzz.timeController.addTime(1.seconds)

        assertThat(test.listener.ignoredMessages).hasSize(1)
        assertThat(test.listener.rejectReasons).isEmpty()
    }

    @Test
    fun `a message for an unsubscribed topic is reported non-subscribed`() {
        val test = Harness(acceptEverything)
        test.gossipRouter.initHandler { CompletableFuture.completedFuture(ValidationResult.Valid) }
        test.subscribeBoth("topic1")

        test.mockRouter.sendToSingle(publishRpc("some-other-topic", 0L))
        test.fuzz.timeController.addTime(1.seconds)

        assertThat(test.listener.nonSubscribedMessages.flatMap { it.topicIDsList })
            .containsExactly("some-other-topic")
    }

    @Test
    fun `joining and leaving a topic is reported`() {
        val test = Harness(acceptEverything)
        test.gossipRouter.initHandler { CompletableFuture.completedFuture(ValidationResult.Valid) }

        test.gossipRouter.subscribe("topic1")
        test.fuzz.timeController.addTime(1.seconds)
        assertThat(test.listener.subscribed).containsExactly("topic1")
        assertThat(test.listener.unsubscribed).isEmpty()

        test.gossipRouter.unsubscribe("topic1")
        test.fuzz.timeController.addTime(1.seconds)
        assertThat(test.listener.unsubscribed).containsExactly("topic1")
    }

    private fun publishRpc(topic: Topic, seqNo: Long) =
        Rpc.RPC.newBuilder()
            .addPublish(newProtoMessage(topic, seqNo, "Hello-$seqNo".toByteArray()))
            .build()
}
