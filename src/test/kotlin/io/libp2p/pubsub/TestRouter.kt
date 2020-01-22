package io.libp2p.pubsub

import io.libp2p.core.PeerId
import io.libp2p.core.crypto.KEY_TYPE
import io.libp2p.core.crypto.generateKeyPair
import io.libp2p.core.pubsub.RESULT_VALID
import io.libp2p.core.pubsub.createPubsubApi
import io.libp2p.core.security.SecureChannel
import io.libp2p.etc.types.lazyVar
import io.libp2p.etc.util.netty.nettyInitializer
import io.libp2p.pubsub.flood.FloodRouter
import io.libp2p.tools.NullTransport
import io.libp2p.tools.TestChannel
import io.libp2p.transport.implementation.ConnectionOverNetty
import io.libp2p.transport.implementation.StreamOverNetty
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import pubsub.pb.Rpc
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicInteger

val cnt = AtomicInteger()
val idCnt = AtomicInteger()

class TestRouter(val name: String = "" + cnt.getAndIncrement()) {

    val inboundMessages = LinkedBlockingQueue<Rpc.Message>()
    var routerHandler: (Rpc.Message) -> CompletableFuture<Boolean> = {
        inboundMessages += it
        RESULT_VALID
    }

    var testExecutor: ScheduledExecutorService by lazyVar { Executors.newSingleThreadScheduledExecutor() }

    var routerInstance: PubsubRouterDebug by lazyVar { FloodRouter() }
    var router by lazyVar {
        routerInstance.also {
            it.initHandler(routerHandler)
            it.executor = testExecutor
        }
    }
    var api by lazyVar { createPubsubApi(router) }

    var keyPair = generateKeyPair(KEY_TYPE.ECDSA)

    private fun newChannel(
        channelName: String,
        remoteRouter: TestRouter,
        wireLogs: LogLevel? = null,
        pubsubLogs: LogLevel? = null,
        initiator: Boolean
    ): TestChannel {

        val parentChannel = TestChannel("dummy-parent-channel", false)
        val connection =
            ConnectionOverNetty(parentChannel, NullTransport(), initiator)
        connection.setSecureSession(SecureChannel.Session(
            PeerId.fromPubKey(keyPair.second),
            PeerId.fromPubKey(remoteRouter.keyPair.second),
            remoteRouter.keyPair.second
        ))

        return TestChannel(
            channelName,
            initiator,
            nettyInitializer { ch ->
                wireLogs?.also { ch.pipeline().addFirst(LoggingHandler(channelName, it)) }
                val stream1 = StreamOverNetty(ch, connection, initiator)
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
