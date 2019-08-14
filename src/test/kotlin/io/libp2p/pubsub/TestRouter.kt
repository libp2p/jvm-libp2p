package io.libp2p.pubsub

import io.libp2p.core.Connection
import io.libp2p.core.PeerId
import io.libp2p.core.SECURE_SESSION
import io.libp2p.core.Stream
import io.libp2p.core.crypto.KEY_TYPE
import io.libp2p.core.crypto.generateKeyPair
import io.libp2p.core.security.SecureChannel
import io.libp2p.core.types.lazyVar
import io.libp2p.core.util.netty.nettyInitializer
import io.libp2p.pubsub.flood.FloodRouter
import io.libp2p.tools.TestChannel
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import pubsub.pb.Rpc
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicInteger

val cnt = AtomicInteger()
val idCnt = AtomicInteger()

class TestRouter(val name: String = "" + cnt.getAndIncrement()) {

    val inboundMessages = LinkedBlockingQueue<Rpc.Message>()
    var routerHandler: (Rpc.Message) -> Unit = {
        inboundMessages += it
    }

    var testExecutor: ScheduledExecutorService by lazyVar { Executors.newSingleThreadScheduledExecutor() }

    var routerInstance: PubsubRouterDebug by lazyVar { FloodRouter() }
    var router by lazyVar { routerInstance.also {
        it.setHandler(routerHandler)
        it.executor = testExecutor
    } }
    var keyPair = generateKeyPair(KEY_TYPE.ECDSA)

    private fun newChannel(
        channelName: String,
        remoteRouter: TestRouter,
        wireLogs: LogLevel? = null,
        pubsubLogs: LogLevel? = null,
        initiator: Boolean
    ): TestChannel {

        val parentChannel = TestChannel("dummy-parent-channel", false, nettyInitializer {
            it.attr(SECURE_SESSION).set(
                SecureChannel.Session(
                    PeerId.fromPubKey(keyPair.second),
                    PeerId.fromPubKey(remoteRouter.keyPair.second),
                    remoteRouter.keyPair.second
                )
            )
        })
        val connection = Connection(parentChannel)

        return TestChannel(
            channelName,
            initiator,
            nettyInitializer { ch ->
                wireLogs?.also { ch.pipeline().addFirst(LoggingHandler(channelName, it)) }
                val stream1 = Stream(ch, connection)
                router.addPeerWithDebugHandler(stream1, pubsubLogs?.let { LoggingHandler(channelName, it) })
            }
        ).also {
            it.executor = testExecutor
        }
    }

    fun connect(
        another: TestRouter,
        wireLogs: LogLevel? = null,
        pubsubLogs: LogLevel? = null
    ): TestChannel.TestConnection {

        val thisChannel = newChannel("[${idCnt.incrementAndGet()}]$name=>${another.name}", another, wireLogs, pubsubLogs, true)
        val anotherChannel = another.newChannel("[${idCnt.incrementAndGet()}]${another.name}=>$name", this, wireLogs, pubsubLogs, false)
        return TestChannel.interConnect(thisChannel, anotherChannel)
    }

    fun connectSemiDuplex(
        another: TestRouter,
        wireLogs: LogLevel? = null,
        pubsubLogs: LogLevel? = null
    ): TestChannel.TestConnection {
        connect(another, wireLogs, pubsubLogs)
        return another.connect(this, wireLogs, pubsubLogs)
    }
}
