package io.libp2p.pubsub

import io.libp2p.core.PeerId
import io.libp2p.core.crypto.KEY_TYPE
import io.libp2p.core.crypto.generateKeyPair
import io.libp2p.core.pubsub.RESULT_VALID
import io.libp2p.core.pubsub.ValidationResult
import io.libp2p.core.security.SecureChannel
import io.libp2p.etc.PROTOCOL
import io.libp2p.etc.types.lazyVar
import io.libp2p.etc.util.netty.nettyInitializer
import io.libp2p.tools.NullTransport
import io.libp2p.tools.TestChannel
import io.libp2p.tools.TestChannel.TestConnection
import io.libp2p.transport.implementation.ConnectionOverNetty
import io.libp2p.transport.implementation.StreamOverNetty
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicInteger

val cnt = AtomicInteger()
val idCnt = AtomicInteger()

class SemiduplexConnection(val conn1: TestConnection, val conn2: TestConnection) {
    val connections = listOf(conn1, conn2)
    fun disconnect() {
        conn1.disconnect()
        // conn2 should be dropped by the router
    }
}

class TestRouter(
    val name: String = "" + cnt.getAndIncrement(),
    val router: PubsubRouterDebug
) {

    val inboundMessages = LinkedBlockingQueue<PubsubMessage>()
    var handlerValidationResult = RESULT_VALID
    val routerHandler: (PubsubMessage) -> CompletableFuture<ValidationResult> = {
        inboundMessages += it
        handlerValidationResult
    }

    var testExecutor: ScheduledExecutorService by lazyVar { Executors.newSingleThreadScheduledExecutor() }

    var keyPair = generateKeyPair(KEY_TYPE.ECDSA)
    val peerId by lazy { PeerId.fromPubKey(keyPair.second) }
    val protocol = router.protocol.announceStr

    init {
        router.initHandler(routerHandler)
    }

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
        connection.setSecureSession(
            SecureChannel.Session(
                peerId, remoteRouter.peerId, remoteRouter.keyPair.second, ""
            )
        )

        return TestChannel(
            channelName,
            initiator,
            nettyInitializer { ch ->
                wireLogs?.also { ch.channel.pipeline().addFirst(LoggingHandler(channelName, it)) }
                val stream1 = StreamOverNetty(ch.channel, connection, initiator)
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
    ): TestConnection {

        val thisChannel = newChannel("[${idCnt.incrementAndGet()}]$name=>${another.name}", another, wireLogs, pubsubLogs, true)
        val anotherChannel = another.newChannel("[${idCnt.incrementAndGet()}]${another.name}=>$name", this, wireLogs, pubsubLogs, false)
        listOf(thisChannel, anotherChannel).forEach {
            it.attr(PROTOCOL).get().complete(this.protocol)
        }
        return TestChannel.interConnect(thisChannel, anotherChannel)
    }

    fun connectSemiDuplex(
        another: TestRouter,
        wireLogs: LogLevel? = null,
        pubsubLogs: LogLevel? = null
    ): SemiduplexConnection {
        return SemiduplexConnection(
            connect(another, wireLogs, pubsubLogs),
            another.connect(this, wireLogs, pubsubLogs)
        )
    }
}
