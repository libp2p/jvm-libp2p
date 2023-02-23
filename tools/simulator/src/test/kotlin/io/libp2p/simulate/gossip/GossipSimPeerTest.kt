package io.libp2p.simulate.gossip

import io.libp2p.core.pubsub.Subscriber
import io.libp2p.core.pubsub.Topic
import io.libp2p.etc.types.toByteBuf
import io.libp2p.simulate.gossip.router.SimGossipRouterBuilder
import io.libp2p.tools.schedulers.ControlledExecutorServiceImpl
import io.libp2p.tools.schedulers.TimeControllerImpl
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.util.*
import java.util.function.Consumer

class GossipSimPeerTest {
    @Test
    fun simplest1() {
        val timeController = TimeControllerImpl()

        val createPeer = {
            val peer = GossipSimPeer(1, Random())
            peer.routerBuilder = SimGossipRouterBuilder().also {
                it.serializeMessagesToBytes = true
            }

            peer.pubsubLogs = { true }
            peer.simExecutor = ControlledExecutorServiceImpl(timeController)
            peer.currentTime = { timeController.time }
            peer.subscribe(Topic("aaa"))
            peer
        }

        val p1 = createPeer()
        val p2 = createPeer()

        p1.connect(p2).get()
        var gotIt = false
        p2.api.subscribe(Consumer { gotIt = true }, Topic("a"))
        val p1Pub = p1.api.createPublisher(p1.keyPair.first, 0)
        p1Pub.publish("Hello".toByteArray().toByteBuf(), Topic("a"))

        Assertions.assertTrue(gotIt)
    }

    companion object {
        fun GossipSimPeer.subscribe(topic: Topic) =
            this.api.subscribe( Subscriber {}, topic)

    }
}
