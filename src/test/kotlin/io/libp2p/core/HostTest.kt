package io.libp2p.core

import io.libp2p.core.multistream.ProtocolMatcher
import io.libp2p.etc.PROTOCOL
import io.libp2p.etc.types.seconds
import io.libp2p.etc.types.toByteArray
import io.libp2p.mux.MuxFrame
import io.libp2p.protocol.Ping
import io.libp2p.protocol.PingBinding
import io.libp2p.protocol.PingProtocol
import io.libp2p.tools.Echo
import io.libp2p.tools.HostFactory
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPromise
import io.netty.handler.logging.LogLevel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.Random
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

class HostTest {

    val hostFactory = HostFactory().also {
        it.muxLogLevel = LogLevel.DEBUG
    }

    @AfterEach
    fun cleanup() {
        hostFactory.shutdown()
    }

    @Test
    fun `test host stream visitor`() {
        val host1 = hostFactory.createHost()
        val host2 = hostFactory.createHost()

        val streamVisitor1 = TestByteBufChannelHandler<Stream>("1")
        host1.host.addStreamVisitor(streamVisitor1)
        val streamVisitor2 = TestByteBufChannelHandler<Stream>("2")
        host2.host.addStreamVisitor(streamVisitor2)

        val ping = Ping().dial(host1.host, host2.peerId, host2.listenAddress)
        val ctrl = ping.controller.get(5, TimeUnit.SECONDS)

        val ret = ctrl.ping().get(5, TimeUnit.SECONDS)
        assertThat(ret).isGreaterThanOrEqualTo(0)

        val data1 = mergeBufs(streamVisitor1.inboundData)
        val data2 = mergeBufs(streamVisitor2.inboundData)

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
    fun `test protocol stream interceptor`() {
        val host1 = hostFactory.createHost()

        class TestInterceptor : ProtocolInterceptor(ProtocolMatcher.prefix("/test/echo")) {
            var interceptRead = false
            var interceptWrite = false
            override fun interceptRead(buf: ByteBuf) =
                if (interceptRead)
                    Unpooled.wrappedBuffer("RRR".toByteArray(Charsets.UTF_8))
                else buf
            override fun interceptWrite(buf: ByteBuf) =
                if (interceptWrite)
                    Unpooled.wrappedBuffer("WWW".toByteArray(Charsets.UTF_8))
                else buf
        }
        val interceptor = TestInterceptor()

        hostFactory.hostBuilderModifier = {
            debug {
                streamHandler.addHandler(interceptor)
            }
        }
        val host2 = hostFactory.createHost()

        val ping = Ping().dial(host1.host, host2.peerId, host2.listenAddress)
        val pingCtrl = ping.controller.get(5, TimeUnit.SECONDS)
        val echo = Echo().dial(host1.host, host2.peerId, host2.listenAddress)
        val echoCtrl = echo.controller.get(5, TimeUnit.SECONDS)

        assertThat(pingCtrl.ping()).succeedsWithin(5.seconds)
        assertThat(echoCtrl.echo("AAA")).succeedsWithin(5.seconds).isEqualTo("AAA")

        interceptor.interceptRead = true

        // interceptor doesn't affect /ping
        assertThat(pingCtrl.ping()).succeedsWithin(5.seconds)
        // ... but modifies /echo
        assertThat(echoCtrl.echo("AAA")).succeedsWithin(5.seconds).isEqualTo("RRR")

        interceptor.interceptRead = false
        interceptor.interceptWrite = true

        assertThat(echoCtrl.echo("AAA")).succeedsWithin(5.seconds).isEqualTo("WWW")
    }

    @Test
    fun `test pre post multistream handlers`() {
        hostFactory.muxLogLevel = LogLevel.DEBUG

        val pingSize = 256
        val pingProto = PingBinding(
            PingProtocol().also {
                it.pingSize = pingSize
                it.random = Random(1)
            }
        )
        val expectedPingData = ByteArray(pingSize).also { Random(1).nextBytes(it) }
        hostFactory.protocols = listOf(pingProto)

        val beforeSecureTestHandler1 = TestByteBufChannelHandler<Connection>("1-beforeSecure")
        val afterSecureTestHandler1 = TestByteBufChannelHandler<Connection>("1-afterSecure")
        val preStreamTestHandler1 = TestByteBufChannelHandler<Stream>("1-preStream")
        val streamTestHandler1 = TestByteBufChannelHandler<Stream>("1-stream")
        val muxFrameTestHandler1 = TestChannelHandler<Connection, MuxFrame>("1-mux")

        hostFactory.hostBuilderModifier = {
            debug {
                beforeSecureHandler.addHandler(beforeSecureTestHandler1)
                afterSecureHandler.addHandler(afterSecureTestHandler1)
                streamPreHandler.addHandler(preStreamTestHandler1)
                streamHandler.addHandler(streamTestHandler1)
                muxFramesHandler.addHandler(muxFrameTestHandler1)
            }
        }
        val host1 = hostFactory.createHost()

        val beforeSecureTestHandler2 = TestByteBufChannelHandler<Connection>("2-beforeSecure")
        val afterSecureTestHandler2 = TestByteBufChannelHandler<Connection>("2-afterSecure")
        val preStreamTestHandler2 = TestByteBufChannelHandler<Stream>("2-preStream")
        val streamTestHandler2 = TestByteBufChannelHandler<Stream>("2-stream")
        val muxFrameTestHandler2 = TestChannelHandler<Connection, MuxFrame>("2-mux")

        hostFactory.hostBuilderModifier = {
            debug {
                beforeSecureHandler.addHandler(beforeSecureTestHandler2)
                afterSecureHandler.addHandler(afterSecureTestHandler2)
                streamPreHandler.addHandler(preStreamTestHandler2)
                streamHandler.addHandler(streamTestHandler2)
                muxFramesHandler.addHandler(muxFrameTestHandler2)
            }
        }
        val host2 = hostFactory.createHost()

        val ping = pingProto.dial(host1.host, host2.peerId, host2.listenAddress)
        val ctrl = ping.controller.get(5, TimeUnit.SECONDS)

        val ret = ctrl.ping().get(5, TimeUnit.SECONDS)
        assertThat(ret).isGreaterThanOrEqualTo(0)

        listOf(
            mergeBufs(beforeSecureTestHandler1.inboundData),
            mergeBufs(beforeSecureTestHandler1.outboundData),
            mergeBufs(beforeSecureTestHandler2.inboundData),
            mergeBufs(beforeSecureTestHandler2.outboundData)
        ).forEach { data ->
            assertThat(data).containsSequence(*"/multistream/".toByteArray(Charsets.UTF_8))
            assertThat(data).containsSequence(*"/noise".toByteArray(Charsets.UTF_8))
            // mplex multistream negotiation should be ciphered
            assertDoesntContainSequence(data, "/mplex".toByteArray(Charsets.UTF_8))
        }

        listOf(
            mergeBufs(afterSecureTestHandler1.inboundData),
            mergeBufs(afterSecureTestHandler1.outboundData),
            mergeBufs(afterSecureTestHandler2.inboundData),
            mergeBufs(afterSecureTestHandler2.outboundData)
        ).forEach { data ->
            assertThat(data).containsSequence(*"/multistream/".toByteArray(Charsets.UTF_8))
            assertThat(data).containsSequence(*"/mplex/".toByteArray(Charsets.UTF_8))
            // secure negotiation shouldn't leak to this handler
            assertDoesntContainSequence(data, "/noise".toByteArray(Charsets.UTF_8))
            // /ping packets should be visible to this handler
            assertThat(data).containsSequence(*"/ping/".toByteArray(Charsets.UTF_8))
            assertThat(data).containsSequence(*expectedPingData)
        }

        listOf(
            mergeBufs(preStreamTestHandler1.inboundData),
            mergeBufs(preStreamTestHandler1.outboundData),
            mergeBufs(preStreamTestHandler2.inboundData),
            mergeBufs(preStreamTestHandler2.outboundData)
        ).forEach { data ->
            // stream _pre_ handlers 'see' the protocol negotiation
            assertThat(data).containsSequence(*"/multistream/".toByteArray(Charsets.UTF_8))
            assertThat(data).containsSequence(*"/ping/".toByteArray(Charsets.UTF_8))
            assertThat(data).containsSequence(*expectedPingData)
            // mplex negotiation shouldn't leak to this handler
            assertDoesntContainSequence(data, "/mplex".toByteArray(Charsets.UTF_8))
        }

        listOf(
            mergeBufs(streamTestHandler1.inboundData),
            mergeBufs(streamTestHandler1.outboundData),
            mergeBufs(streamTestHandler2.inboundData),
            mergeBufs(streamTestHandler2.outboundData)
        ).forEach { data ->
            assertThat(data).containsOnly(*expectedPingData)
            // stream handlers see only protocol data without multistream negotiation
            assertDoesntContainSequence(data, "/multistream/".toByteArray(Charsets.UTF_8))
            assertDoesntContainSequence(data, "/ping/".toByteArray(Charsets.UTF_8))
        }
    }

    fun assertDoesntContainSequence(data: ByteArray, seq: ByteArray) {
        assertThat(listOf(data)).noneSatisfy(Consumer { candidate -> assertThat(candidate).containsSequence(*seq) })
    }

    fun mergeBufs(bufs: Collection<ByteBuf>): ByteArray =
        bufs.fold(Unpooled.buffer()) { acc, byteBuf ->
            acc.writeBytes(byteBuf.slice())
        }.toByteArray()

    open class TestChannelHandler<TChannel : P2PChannel, TMessage>(val id: String) : ChannelVisitor<TChannel> {
        val inboundData = mutableListOf<TMessage>()
        val outboundData = mutableListOf<TMessage>()
        override fun visit(channel: TChannel) {
            channel.pushHandler(object : ChannelDuplexHandler() {
                override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                    @Suppress("UNCHECKED_CAST")
                    msg as TMessage
                    inboundData += retainMessage(msg)
                    println("####   --> [$id]: $msg")
                    ctx.fireChannelRead(msg)
                }

                override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
                    @Suppress("UNCHECKED_CAST")
                    msg as TMessage
                    outboundData += retainMessage(msg)
                    println("#### <--   [$id]: $msg")
                    ctx.write(msg, promise)
                }
            })
        }

        open fun retainMessage(msg: TMessage): TMessage = msg
    }

    class TestByteBufChannelHandler<TChannel : P2PChannel>(id: String) : TestChannelHandler<TChannel, ByteBuf>(id) {
        override fun retainMessage(msg: ByteBuf) = msg.retainedSlice()
    }

    abstract class ProtocolInterceptor(val protocol: ProtocolMatcher) : ChannelVisitor<Stream> {
        abstract fun interceptRead(buf: ByteBuf): ByteBuf
        abstract fun interceptWrite(buf: ByteBuf): ByteBuf

        override fun visit(channel: Stream) {
            var matched = false
            channel.pushHandler(object : ChannelDuplexHandler() {

                override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                    if (match(ctx)) {
                        msg as ByteBuf
                        println("####   --> : $msg")
                        val modifiedRead = interceptRead(msg)
                        ctx.fireChannelRead(modifiedRead)
                    } else {
                        ctx.fireChannelRead(msg)
                    }
                }

                override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
                    if (match(ctx)) {
                        msg as ByteBuf
                        println("#### <--   : $msg")
                        val modifiedWrite = interceptWrite(msg)
                        ctx.write(modifiedWrite, promise)
                    } else {
                        ctx.write(msg, promise)
                    }
                }

                fun match(ctx: ChannelHandlerContext): Boolean {
                    if (!matched) {
                        matched = true
                        val protoFut = ctx.channel().attr(PROTOCOL).get()
                        assert(protoFut.isDone)
                        val proto = protoFut.get()
                        if (!protocol.matches(proto)) {
                            ctx.pipeline().remove(this)
                            println("### protocol didn't match: $proto")
                            return false
                        } else {
                            println("### protocol matched: $proto")
                            return true
                        }
                    } else {
                        return true
                    }
                }

                override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                    cause.printStackTrace()
                    ctx.fireExceptionCaught(cause)
                }
            })
        }
    }
}
