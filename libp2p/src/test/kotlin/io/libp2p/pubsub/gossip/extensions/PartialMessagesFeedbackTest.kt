package io.libp2p.pubsub.gossip.extensions

import com.google.protobuf.ByteString
import io.libp2p.core.PeerId
import io.libp2p.pubsub.PubsubProtocol
import io.libp2p.pubsub.Topic
import io.libp2p.pubsub.gossip.GossipExtension
import io.libp2p.pubsub.gossip.GossipRouterEventListener
import io.libp2p.pubsub.gossip.GossipTestsBase
import io.libp2p.pubsub.gossip.partialmessages.FeedbackKind
import io.libp2p.pubsub.gossip.partialmessages.PartialMessagesHandler
import io.libp2p.pubsub.gossip.partialmessages.PartialMessagesPeerFeedback
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.pubsub.ValidationResult
import io.libp2p.pubsub.PubsubMessage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pubsub.pb.Rpc
import java.util.Optional
import java.util.concurrent.CopyOnWriteArrayList

class PartialMessagesFeedbackTest : GossipTestsBase() {

    private val topicId = "test-topic"
    private val groupIdBytes = "group-fb-1".toByteArray()

    /** Records (peerId, count) for every notifyRouterMisbehavior call. */
    data class MisbehaviorEvent(val peerId: PeerId, val count: Int)

    private val misbehaviorEvents = CopyOnWriteArrayList<MisbehaviorEvent>()

    private val misbehaviorListener = object : GossipRouterEventListener {
        override fun notifyDisconnected(peerId: PeerId) {}
        override fun notifyConnected(peerId: PeerId, peerAddress: Multiaddr) {}
        override fun notifyUnseenMessage(peerId: PeerId, msg: PubsubMessage) {}
        override fun notifySeenMessage(peerId: PeerId, msg: PubsubMessage, validationResult: Optional<ValidationResult>) {}
        override fun notifyUnseenInvalidMessage(peerId: PeerId, msg: PubsubMessage) {}
        override fun notifyUnseenValidMessage(peerId: PeerId, msg: PubsubMessage) {}
        override fun notifyMeshed(peerId: PeerId, topic: Topic) {}
        override fun notifyPruned(peerId: PeerId, topic: Topic) {}
        override fun notifyRouterMisbehavior(peerId: PeerId, count: Int) {
            misbehaviorEvents += MisbehaviorEvent(peerId, count)
        }
    }

    /** Handler that always reports INVALID for every inbound RPC. */
    private val invalidatingHandler: PartialMessagesHandler<Unit> =
        object : PartialMessagesHandler<Unit> {
            override fun onIncomingRpc(
                from: PeerId,
                peerStates: Map<PeerId, Unit>,
                rpc: Rpc.PartialMessagesExtension,
                feedback: PartialMessagesPeerFeedback
            ): Unit? {
                feedback.reportFeedback(rpc.topicID, from, FeedbackKind.INVALID)
                return null
            }

            override fun onEmitGossip(
                topic: Topic,
                groupId: ByteArray,
                gossipPeers: Collection<PeerId>,
                peerStates: Map<PeerId, Unit>,
                feedback: PartialMessagesPeerFeedback
            ) {}
        }

    private fun newTest(): TwoRoutersTest {
        val test = TwoRoutersTest(
            protocol = PubsubProtocol.Gossip_V_1_3,
            enabledGossipExtensions = listOf(GossipExtension.PARTIAL_MESSAGES),
            partialMessagesHandler = invalidatingHandler,
        )
        test.gossipRouter.eventBroadcaster.listeners += misbehaviorListener
        return test
    }

    private fun controlExtensionsWithPartial(): Rpc.RPC =
        Rpc.RPC.newBuilder().setControl(
            Rpc.ControlMessage.newBuilder().setExtensions(
                Rpc.ControlExtensions.newBuilder().setPartialMessages(true)
            )
        ).build()

    private fun partialRpc(): Rpc.RPC {
        val ext = Rpc.PartialMessagesExtension.newBuilder()
            .setTopicID(topicId)
            .setGroupID(ByteString.copyFrom(groupIdBytes))
            .build()
        return Rpc.RPC.newBuilder().setPartial(ext).build()
    }

    private fun TwoRoutersTest.flush() = gossipRouter.submitOnEventThread {}.join()

    @Test
    fun `INVALID feedback from handler triggers notifyRouterMisbehavior for sending peer`() {
        val test = newTest()
        test.flush()

        val sendingPeerId = test.router2.peerId

        test.mockRouter.sendToSingle(controlExtensionsWithPartial())
        test.mockRouter.sendToSingle(partialRpc())
        test.flush()

        assertThat(misbehaviorEvents).isNotEmpty
        assertThat(misbehaviorEvents).allMatch { it.peerId == sendingPeerId && it.count == 1 }
    }

    @Test
    fun `USEFUL feedback from handler does not trigger notifyRouterMisbehavior`() {
        val usefulHandler: PartialMessagesHandler<Unit> =
            object : PartialMessagesHandler<Unit> {
                override fun onIncomingRpc(
                    from: PeerId,
                    peerStates: Map<PeerId, Unit>,
                    rpc: Rpc.PartialMessagesExtension,
                    feedback: PartialMessagesPeerFeedback
                ): Unit? {
                    feedback.reportFeedback(rpc.topicID, from, FeedbackKind.USEFUL)
                    return null
                }

                override fun onEmitGossip(
                    topic: Topic,
                    groupId: ByteArray,
                    gossipPeers: Collection<PeerId>,
                    peerStates: Map<PeerId, Unit>,
                    feedback: PartialMessagesPeerFeedback
                ) {}
            }

        val test = TwoRoutersTest(
            protocol = PubsubProtocol.Gossip_V_1_3,
            enabledGossipExtensions = listOf(GossipExtension.PARTIAL_MESSAGES),
            partialMessagesHandler = usefulHandler,
        )
        test.gossipRouter.eventBroadcaster.listeners += misbehaviorListener
        test.flush()

        test.mockRouter.sendToSingle(controlExtensionsWithPartial())
        test.mockRouter.sendToSingle(partialRpc())
        test.flush()

        assertThat(misbehaviorEvents).isEmpty()
    }
}
