package io.libp2p.pubsub

import io.libp2p.core.Connection
import io.libp2p.core.Stream
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

    private fun newChannel(
        loggerCaption: String?,
        wireLogs: LogLevel? = null,
        pubsubLogs: LogLevel? = null
    ) =
        TestChannel(
            nettyInitializer {ch ->
                wireLogs?.also { ch.pipeline().addFirst(LoggingHandler(loggerCaption,it)) }
                val conn1 = Connection(TestChannel())
                val stream1 = Stream(ch, conn1)
                router.addPeerWithDebugHandler(stream1, pubsubLogs?.let { LoggingHandler(loggerCaption,it) })
            }
        ).also {
            it.executor = testExecutor
        }

    fun connect(
        another: TestRouter,
        wireLogs: LogLevel? = null,
        pubsubLogs: LogLevel? = null
    ): TestChannel.TestConnection {

        val thisChannel = newChannel("$name=>${another.name}", wireLogs, pubsubLogs)
        val anotherChannel = another.newChannel("${another.name}=>$name", wireLogs, pubsubLogs)
        return TestChannel.interConnect(thisChannel, anotherChannel)
    }
}
