package io.libp2p.etc.util

import io.libp2p.core.SemiDuplexNoOutboundStreamException
import io.libp2p.core.StreamNotActiveException
import io.libp2p.etc.types.forwardTo
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

fun interface BackpressureAwareAsyncWriter {

    fun write(message: Any, writePromise: CompletableFuture<Unit>): CompletionStage<Boolean>

    companion object {

        fun createFromStreamHandler(streamHandler: P2PService.PeerHandler): BackpressureAwareAsyncWriter {
            return object : BackpressureAwareAsyncWriter {
                override fun write(
                    message: Any,
                    writePromise: CompletableFuture<Unit>
                ): CompletionStage<Boolean> {
                    val outboundHandler = streamHandler.getOutboundHandler()
                    if (outboundHandler == null) {
                        writePromise.completeExceptionally(SemiDuplexNoOutboundStreamException())
                        return CompletableFuture.failedFuture(SemiDuplexNoOutboundStreamException())
                    }
                    val channel = outboundHandler.ctx?.channel()
                    if (channel == null) {
                        writePromise.completeExceptionally(StreamNotActiveException())
                        return CompletableFuture.failedFuture(StreamNotActiveException())
                    }
                    val ret = CompletableFuture<Boolean>()
                    channel.eventLoop().execute {
                        channel.writeAndFlush(message).forwardTo(writePromise)
                        ret.complete(channel.isWritable)
                    }
                    return ret
                }
            }
        }
    }
}
