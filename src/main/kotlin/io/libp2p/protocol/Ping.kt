package io.libp2p.protocol

import io.libp2p.core.BadPeerException
import io.libp2p.core.ConnectionClosedException
import io.libp2p.core.Libp2pException
import io.libp2p.core.Stream
import io.libp2p.core.multistream.StrictProtocolBinding
import io.libp2p.etc.types.completedExceptionally
import io.libp2p.etc.types.lazyVar
import io.libp2p.etc.types.toByteArray
import io.libp2p.etc.types.toByteBuf
import io.libp2p.etc.types.toHex
import io.netty.buffer.ByteBuf
import java.time.Duration
import java.util.Collections
import java.util.Random
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

interface PingController {
    fun ping(): CompletableFuture<Long>
}

class Ping : PingBinding(PingProtocol())

open class PingBinding(ping: PingProtocol) : StrictProtocolBinding<PingController>(ping) {
    override val announce = "/ipfs/ping/1.0.0"
}

class PingTimeoutException : Libp2pException()

open class PingProtocol : ProtocolHandler<PingController>() {
    var scheduler by lazyVar { Executors.newSingleThreadScheduledExecutor() }
    var curTime: () -> Long = { System.currentTimeMillis() }
    var random = Random()
    var pingSize = 32
    var pingTimeout = Duration.ofSeconds(10)

    override fun onStartInitiator(stream: Stream): CompletableFuture<PingController> {
        val handler = PingInitiator()
        stream.pushHandler(handler)
        return handler.activeFuture
    }

    override fun onStartResponder(stream: Stream): CompletableFuture<PingController> {
        val handler = PingResponder()
        stream.pushHandler(handler)
        return CompletableFuture.completedFuture(handler)
    }

    open inner class PingResponder : ProtocolMessageHandler<ByteBuf>, PingController {
        override fun onMessage(stream: Stream, msg: ByteBuf) {
            stream.writeAndFlush(msg)
        }

        override fun ping(): CompletableFuture<Long> {
            throw Libp2pException("This is ping responder only")
        }
    }

    open inner class PingInitiator : ProtocolMessageHandler<ByteBuf>, PingController {
        val activeFuture = CompletableFuture<PingController>()
        val requests = Collections.synchronizedMap(mutableMapOf<String, Pair<Long, CompletableFuture<Long>>>())
        lateinit var stream: Stream
        var closed = false

        override fun onActivated(stream: Stream) {
            this.stream = stream
            activeFuture.complete(this)
        }

        override fun onMessage(stream: Stream, msg: ByteBuf) {
            val dataS = msg.toByteArray().toHex()
            val (sentT, future) = requests.remove(dataS)
                ?: throw BadPeerException("Unknown or expired ping data in response: $dataS")
            future.complete(curTime() - sentT)
        }

        override fun onClosed(stream: Stream) {
            closed = true

            scheduler.shutdownNow()

            activeFuture.completeExceptionally(ConnectionClosedException())
            synchronized(requests) {
                requests.values.forEach { it.second.completeExceptionally(ConnectionClosedException()) }
                requests.clear()
            }
        }

        override fun ping(): CompletableFuture<Long> {
            if (closed) return completedExceptionally(ConnectionClosedException())
            val ret = CompletableFuture<Long>()
            val data = ByteArray(pingSize)
            random.nextBytes(data)
            val dataS = data.toHex()
            requests[dataS] = curTime() to ret
            scheduler.schedule({
                requests.remove(dataS)?.second?.completeExceptionally(PingTimeoutException())
            }, pingTimeout.toMillis(), TimeUnit.MILLISECONDS)
            stream.writeAndFlush(data.toByteBuf())
            return ret
        }
    }
}