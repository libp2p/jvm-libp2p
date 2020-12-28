package io.libp2p.core

import io.libp2p.etc.types.toByteArray
import io.libp2p.protocol.Ping
import io.libp2p.tools.HostFactory
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.logging.LogLevel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class HostTest {

    val hostFactory = HostFactory().also {
        it.muxLogLevel = LogLevel.ERROR
    }

    @AfterEach
    fun cleanup() {
        hostFactory.shutdown()
    }

    @Test
    fun `test stream visitor`() {
        val host1 = hostFactory.createHost()
        val host2 = hostFactory.createHost()

        class TestStreamVisitor(val id: String) : ChannelVisitor<Stream> {
            val inboundData = mutableListOf<ByteBuf>()
            override fun visit(stream: Stream) {
                stream.pushHandler(object : ChannelInboundHandlerAdapter() {
                    override fun channelRead(ctx: ChannelHandlerContext?, msg: Any?) {
                        msg as ByteBuf
                        inboundData += msg.retainedSlice()
                        super.channelRead(ctx, msg)
                    }
                })
            }
        }

        val streamVisitor1 = TestStreamVisitor("1")
        host1.host.addStreamVisitor(streamVisitor1)
        val streamVisitor2 = TestStreamVisitor("2")
        host2.host.addStreamVisitor(streamVisitor2)

        val ping = Ping().dial(host1.host, host2.peerId, host2.listenAddress)
        val ctrl = ping.controller.get(5, TimeUnit.SECONDS)

        val ret = ctrl.ping().get(5, TimeUnit.SECONDS)
        assertThat(ret).isGreaterThanOrEqualTo(0)

        val data1 = streamVisitor1.inboundData
            .fold(Unpooled.buffer()) { acc, byteBuf -> acc.writeBytes(byteBuf.slice()) }
            .toByteArray()
        val data2 = streamVisitor2.inboundData
            .fold(Unpooled.buffer()) { acc, byteBuf -> acc.writeBytes(byteBuf.slice()) }
            .toByteArray()

        listOf(data1, data2).forEach { data ->
            assertThat(data).containsSequence(*"/multistream/".toByteArray(Charsets.UTF_8))
            assertThat(data).containsSequence(*"/ping/".toByteArray(Charsets.UTF_8))
        }

        val packetsCount1 = streamVisitor1.inboundData.size
        val packetsCount2 = streamVisitor2.inboundData.size

        ctrl.ping().get(5, TimeUnit.SECONDS)

        assertThat(streamVisitor1.inboundData.size).isGreaterThan(packetsCount1)
        assertThat(streamVisitor2.inboundData.size).isGreaterThan(packetsCount2)
    }

    @Test
    fun `test pre post multistream handlers`() {
        class TestChannleHandler(val id: String) : ChannelVisitor<P2PChannel> {
            val inboundData = mutableListOf<ByteBuf>()
            val outboundData = mutableListOf<ByteBuf>()
            override fun visit(ch: P2PChannel) {
                ch.pushHandler(object : ChannelDuplexHandler() {
                    override fun channelRead(ctx: ChannelHandlerContext?, msg: Any?) {
                        msg as ByteBuf
                        inboundData += msg.retainedSlice()
                        println("#### [$id]: $msg")
                        super.channelRead(ctx, msg)
                    }
                })
            }
        }

        hostFactory.muxLogLevel = LogLevel.DEBUG
        val host1 = hostFactory.createHost()

        hostFactory.hostBuilderModifier = {
//            secureMultistreamProtocol =
//                MultistreamProtocolDebug_v_1_0_0(TestChannleHandler("Secure-pre"), TestChannleHandler("Secure-post"))
//            muxerMultistreamProtocol =
//                MultistreamProtocolDebug_v_1_0_0(TestChannleHandler("Mux-pre"), TestChannleHandler("Mux-post"))
//            streamMultistreamProtocol =
//                MultistreamProtocolDebug_v_1_0_0(TestChannleHandler("Stream-pre"), TestChannleHandler("Stream-post"))
            debug {
//                beforeSecureHandler.setLogger(LogLevel.ERROR)
//                afterSecureHandler.setLogger(LogLevel.ERROR)
                muxFramesHandler.addLogger(LogLevel.ERROR)
            }
        }
//        hostFactory.muxLogLevel = LogLevel.ERROR
        val host2 = hostFactory.createHost()

        val ping = Ping().dial(host1.host, host2.peerId, host2.listenAddress)
        val ctrl = ping.controller.get(5, TimeUnit.SECONDS)

        val ret = ctrl.ping().get(5, TimeUnit.SECONDS)
        assertThat(ret).isGreaterThanOrEqualTo(0)

    }
}
