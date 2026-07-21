package io.libp2p.etc.util

import io.libp2p.core.PeerId
import io.libp2p.core.Stream
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class P2PServiceWritabilityTest {
    private val executor = Executors.newSingleThreadScheduledExecutor()

    @AfterEach
    fun tearDown() {
        executor.shutdownNow()
    }

    @Test
    fun `peer writability follows outbound channel`() {
        val service = TestService()
        val channel = mockk<Channel>()
        val ctx = mockk<ChannelHandlerContext>()
        val handler = service.newHandler(ctx)
        val peer = service.newPeer(handler)

        every { ctx.channel() } returns channel
        every { channel.isWritable } returnsMany listOf(false, true)

        assertThat(peer.isWritable()).isFalse()
        assertThat(peer.isWritable()).isTrue()
    }

    @Test
    fun `writability event is forwarded and scheduled on service thread`() {
        val service = TestService()
        val ctx = mockk<ChannelHandlerContext>(relaxed = true)
        val handler = service.newHandler(ctx)
        service.newPeer(handler)

        handler.channelWritabilityChanged(ctx)

        assertThat(service.writabilityCallback.await(1, TimeUnit.SECONDS)).isTrue()
        verify(exactly = 1) { ctx.fireChannelWritabilityChanged() }
        assertThat(service.callbackThread).startsWith("pool-")
    }

    @Test
    fun `writability before peer association is only forwarded`() {
        val service = TestService()
        val ctx = mockk<ChannelHandlerContext>(relaxed = true)
        val handler = service.newHandler(ctx)

        handler.channelWritabilityChanged(ctx)

        verify(exactly = 1) { ctx.fireChannelWritabilityChanged() }
        assertThat(service.writabilityCallback.count).isEqualTo(1)
    }

    private inner class TestService : P2PService(executor) {
        val writabilityCallback = CountDownLatch(1)
        var callbackThread = ""

        fun newHandler(ctx: ChannelHandlerContext): StreamHandler =
            StreamHandler(
                mockk<Stream> {
                    every { remotePeerId() } returns PeerId(ByteArray(32))
                }
            ).also { it.ctx = ctx }

        fun newPeer(handler: StreamHandler): PeerHandler =
            PeerHandler(handler).also(handler::initPeerHandler)

        override fun streamWritabilityChanged(stream: StreamHandler) {
            callbackThread = Thread.currentThread().name
            writabilityCallback.countDown()
        }

        override fun initChannel(streamHandler: StreamHandler) {}
        override fun onPeerActive(peer: PeerHandler) {}
        override fun onPeerDisconnected(peer: PeerHandler) {}
        override fun onInbound(peer: PeerHandler, msg: Any) {}
    }
}
