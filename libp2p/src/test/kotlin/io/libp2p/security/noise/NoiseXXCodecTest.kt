package io.libp2p.security.noise

import com.southernstorm.noise.protocol.CipherState
import io.mockk.mockk
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.embedded.EmbeddedChannel
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class NoiseXXCodecTest {

    private fun codec() =
        NoiseXXCodec(mockk<CipherState>(relaxed = true), mockk<CipherState>(relaxed = true))

    private fun channelWith(codec: NoiseXXCodec): Pair<EmbeddedChannel, ChannelHandlerContext> {
        val channel = EmbeddedChannel(codec)
        val ctx = channel.pipeline().context(codec)!!
        return channel to ctx
    }

    @Test
    fun `fatal Error is rethrown and not swallowed`() {
        val codec = codec()
        val (_, ctx) = channelWith(codec)
        val oom = OutOfMemoryError("Java heap space")

        assertThatThrownBy { codec.exceptionCaught(ctx, oom) }
            .isSameAs(oom)
    }

    @Test
    fun `fatal Error closes the channel`() {
        val codec = codec()
        val (channel, ctx) = channelWith(codec)

        runCatching { codec.exceptionCaught(ctx, OutOfMemoryError("Java heap space")) }

        channel.runPendingTasks()
        assertThat(channel.isOpen).isFalse()
    }

    @Test
    fun `unexpected runtime exception closes the channel`() {
        val codec = codec()
        val (channel, ctx) = channelWith(codec)

        codec.exceptionCaught(ctx, RuntimeException("boom"))

        channel.runPendingTasks()
        assertThat(channel.isOpen).isFalse()
    }
}
