package io.libp2p.etc.util.netty

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.DefaultChannelPromise
import io.netty.util.concurrent.DefaultEventExecutorGroup
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.TimeUnit

class TotalTimeoutHandlerTest {
    private val group = DefaultEventExecutorGroup(1)

    @AfterEach
    fun tearDown() {
        group.shutdownGracefully(0, 100, TimeUnit.MILLISECONDS).sync()
    }

    /**
     * Regression test for the closed-substream pipeline-retention leak.
     *
     * The timeout must be cancelled when the channel closes even if the handler is
     * NEVER removed from the pipeline — which is exactly what happens to a substream
     * that closes mid-negotiation while the event loop is too backlogged to run the
     * deferred pipeline-destroy (and thus handlerRemoved). Here we close the channel
     * (complete its close future) WITHOUT firing handlerRemoved and assert the
     * scheduled onTimeout never runs.
     *
     * Before the fix (cancel only in handlerRemoved) this fails: onTimeout fires and
     * the scheduled task — capturing the whole closed pipeline — outlives the channel.
     */
    @Test
    fun `timeout is cancelled on channel close even when handler is not removed`() {
        val executor = group.next()
        val channel = mockk<Channel>(relaxed = true)
        val closeFuture = DefaultChannelPromise(channel, executor)
        every { channel.closeFuture() } returns closeFuture

        val ctx = mockk<ChannelHandlerContext>(relaxed = true)
        every { ctx.executor() } returns executor
        every { ctx.channel() } returns channel

        val handler = TotalTimeoutHandler(Duration.ofMillis(150))

        // Install the handler (schedules the timeout + registers the close listener).
        executor.submit { handler.handlerAdded(ctx) }.get(5, TimeUnit.SECONDS)

        // Close the channel WITHOUT removing the handler from the pipeline.
        executor.submit { closeFuture.setSuccess() }.get(5, TimeUnit.SECONDS)

        // Wait well past the timeout: if it were still scheduled, onTimeout would run.
        Thread.sleep(500)

        // onTimeout (which closes the channel and fires the exception) must NOT have run.
        verify(exactly = 0) { ctx.close() }
        verify(exactly = 0) { ctx.fireExceptionCaught(any()) }
    }

    /**
     * Sanity: a channel that stays open past the negotiation deadline IS timed out
     * (the handler still does its job when not cancelled).
     */
    @Test
    fun `timeout fires when the channel neither closes nor removes the handler`() {
        val executor = group.next()
        val channel = mockk<Channel>(relaxed = true)
        val closeFuture = DefaultChannelPromise(channel, executor)
        every { channel.closeFuture() } returns closeFuture

        val ctx = mockk<ChannelHandlerContext>(relaxed = true)
        every { ctx.executor() } returns executor
        every { ctx.channel() } returns channel

        val handler = TotalTimeoutHandler(Duration.ofMillis(100))
        executor.submit { handler.handlerAdded(ctx) }.get(5, TimeUnit.SECONDS)

        Thread.sleep(500)

        verify(exactly = 1) { ctx.close() }
        verify(exactly = 1) { ctx.fireExceptionCaught(any()) }
    }
}
