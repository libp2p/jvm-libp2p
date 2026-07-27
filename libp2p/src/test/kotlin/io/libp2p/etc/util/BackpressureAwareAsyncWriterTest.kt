package io.libp2p.etc.util

import io.mockk.every
import io.mockk.mockk
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.EventLoop
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException

class BackpressureAwareAsyncWriterTest {

    @Test
    fun `completes both futures when event loop rejects write task`() {
        val rejection = RejectedExecutionException("event loop is shutting down")
        val eventLoop = mockk<EventLoop>()
        val channel = mockk<Channel>()
        val ctx = mockk<ChannelHandlerContext>()
        val streamHandler = mockk<P2PService.StreamHandler>()
        val peerHandler = mockk<P2PService.PeerHandler>()
        val writePromise = CompletableFuture<Unit>()

        every { peerHandler.getOutboundHandler() } returns streamHandler
        every { streamHandler.ctx } returns ctx
        every { ctx.channel() } returns channel
        every { channel.eventLoop() } returns eventLoop
        every { eventLoop.execute(any()) } throws rejection

        val result = BackpressureAwareAsyncWriter
            .createFromStreamHandler(peerHandler)
            .write("message", writePromise)
            .toCompletableFuture()

        assertThat(result).isCompletedExceptionally
        assertThat(writePromise).isCompletedExceptionally
        assertThat(result.exception()).isSameAs(rejection)
        assertThat(writePromise.exception()).isSameAs(rejection)
    }

    private fun CompletableFuture<*>.exception(): Throwable {
        return try {
            get()
            throw AssertionError("Future completed successfully")
        } catch (e: ExecutionException) {
            e.cause!!
        }
    }
}
