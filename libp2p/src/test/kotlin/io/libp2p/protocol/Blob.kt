package io.libp2p.protocol

import io.libp2p.core.BadPeerException
import io.libp2p.core.ConnectionClosedException
import io.libp2p.core.Libp2pException
import io.libp2p.core.Stream
import io.libp2p.core.multistream.StrictProtocolBinding
import io.libp2p.etc.types.completedExceptionally
import io.libp2p.etc.types.lazyVar
import io.libp2p.etc.types.toByteArray
import io.libp2p.etc.types.toHex
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.ByteToMessageCodec
import java.time.Duration
import java.util.Collections
import java.util.Random
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

interface BlobController {
    fun blob(): CompletableFuture<Long>
}

class Blob(blobSize: Int) : BlobBinding(BlobProtocol(blobSize))

open class BlobBinding(blob: BlobProtocol) :
    StrictProtocolBinding<BlobController>("/ipfs/blob-echo/1.0.0", blob)

class BlobTimeoutException : Libp2pException()

open class BlobProtocol(var blobSize: Int) : ProtocolHandler<BlobController>(Long.MAX_VALUE, Long.MAX_VALUE) {
    var timeoutScheduler by lazyVar { Executors.newSingleThreadScheduledExecutor() }
    var curTime: () -> Long = { System.currentTimeMillis() }
    var random = Random()
    var blobTimeout = Duration.ofSeconds(10)

    override fun onStartInitiator(stream: Stream): CompletableFuture<BlobController> {
        val handler = BlobInitiator()
        stream.pushHandler(BlobCodec())
        stream.pushHandler(handler)
        stream.pushHandler(BlobCodec())
        return handler.activeFuture
    }

    override fun onStartResponder(stream: Stream): CompletableFuture<BlobController> {
        val handler = BlobResponder()
        stream.pushHandler(BlobCodec())
        stream.pushHandler(BlobResponder())
        stream.pushHandler(BlobCodec())
        return CompletableFuture.completedFuture(handler)
    }

    open class BlobCodec : ByteToMessageCodec<ByteArray>() {
        override fun encode(ctx: ChannelHandlerContext?, msg: ByteArray, out: ByteBuf) {
            println("Codec::encode")
            out.writeInt(msg.size)
            out.writeBytes(msg)
        }

        override fun decode(ctx: ChannelHandlerContext?, msg: ByteBuf, out: MutableList<Any>) {
            println("Codec::decode " + msg.readableBytes())
            val readerIndex = msg.readerIndex()
            if (msg.readableBytes() < 4) {
                return
            }
            val len = msg.readInt()
            if (msg.readableBytes() < len) {
                // not enough data to read the full array
                // will wait for more ...
                msg.readerIndex(readerIndex)
                return
            }
            val data = msg.readSlice(len)
            out.add(data.toByteArray())
        }
    }

    open inner class BlobResponder : ProtocolMessageHandler<ByteArray>, BlobController {
        override fun onMessage(stream: Stream, msg: ByteArray) {
            println("Responder::onMessage")
            stream.writeAndFlush(msg)
        }

        override fun blob(): CompletableFuture<Long> {
            throw Libp2pException("This is blob responder only")
        }
    }

    open inner class BlobInitiator : ProtocolMessageHandler<ByteArray>, BlobController {
        val activeFuture = CompletableFuture<BlobController>()
        val requests = Collections.synchronizedMap(mutableMapOf<String, Pair<Long, CompletableFuture<Long>>>())
        lateinit var stream: Stream
        var closed = false

        override fun onActivated(stream: Stream) {
            this.stream = stream
            activeFuture.complete(this)
        }

        override fun onMessage(stream: Stream, msg: ByteArray) {
            println("Initiator::onMessage")
            val dataS = msg.toHex()
            val (sentT, future) = requests.remove(dataS)
                ?: throw BadPeerException("Unknown or expired blob data in response: $dataS")
            future.complete(curTime() - sentT)
        }

        override fun onClosed(stream: Stream) {
            synchronized(requests) {
                closed = true
                requests.values.forEach { it.second.completeExceptionally(ConnectionClosedException()) }
                requests.clear()
                timeoutScheduler.shutdownNow()
            }
            activeFuture.completeExceptionally(ConnectionClosedException())
        }

        override fun blob(): CompletableFuture<Long> {
            val ret = CompletableFuture<Long>()
            val arr = ByteArray(blobSize)
            random.nextBytes(arr)
            val dataS = arr.toHex()

            synchronized(requests) {
                if (closed) return completedExceptionally(ConnectionClosedException())
                requests[dataS] = curTime() to ret

                timeoutScheduler.schedule(
                    {
                        requests.remove(dataS)?.second?.completeExceptionally(BlobTimeoutException())
                    },
                    blobTimeout.toMillis(),
                    TimeUnit.MILLISECONDS
                )
            }

            println("Sender writing " + blobSize)
            stream.writeAndFlush(arr)
            return ret
        }
    }
}
