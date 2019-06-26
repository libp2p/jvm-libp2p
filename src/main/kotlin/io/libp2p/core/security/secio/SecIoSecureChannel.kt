package io.libp2p.core.security.secio

import io.libp2p.core.PeerId
import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.protocol.Mode
import io.libp2p.core.protocol.ProtocolMatcher
import io.libp2p.core.protocol.SecureChannel
import io.libp2p.core.protocol.SecureChannelInitializerName
import io.libp2p.core.types.toByteArray
import io.libp2p.core.types.toByteBuf
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelInitializer
import io.netty.handler.codec.LengthFieldBasedFrameDecoder
import io.netty.handler.codec.LengthFieldPrepender
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.sendBlocking
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import io.netty.channel.Channel as NettyChannel


class SecIoSecureChannel(val localKey: PrivKey, val remotePeerId: PeerId? = null) :
    SecureChannel {

    private val HandshakeHandlerName = "SecIoHandshake"
    private val HadshakeTimeout = 30 * 1000L

    override val matcher = ProtocolMatcher(Mode.STRICT, name = "/secio/1.0.0")

    override fun initializer(): ChannelInitializer<NettyChannel> =
        object : ChannelInitializer<NettyChannel>() {
            override fun initChannel(ch: NettyChannel) {
                ch.pipeline().addBefore(SecureChannelInitializerName, "PacketLenEncoder", LengthFieldPrepender(4))
                ch.pipeline().addBefore(SecureChannelInitializerName, "PacketLenDecoder",
                    LengthFieldBasedFrameDecoder(Integer.MAX_VALUE, 0, 4, 0, 4))
                ch.pipeline().addBefore(SecureChannelInitializerName, HandshakeHandlerName, SecIoHandshake())
            }
        }

    inner class SecIoHandshake : ChannelInboundHandlerAdapter() {
        private val kInChannel = Channel<ByteBuf>(1)
        private var deferred: Deferred<Pair<SecioParams, SecioParams>>? = null
        private val messageReadCount = AtomicInteger()
        private var nonce: ByteArray? = null
        private val executor = Executors.newSingleThreadExecutor()

        override fun channelActive(ctx: ChannelHandlerContext) {
            val negotiator = SecioHandshake(kInChannel, { buf -> writeAndFlush(ctx, buf) }, localKey, remotePeerId)

            deferred = GlobalScope.async {
                try {
                    withTimeout(HadshakeTimeout) {
                        negotiator.doHandshake()
                    }
                } catch (e: Exception) {
                    e.printStackTrace() // TODO logging
                    ctx.fireExceptionCaught(e)
                    throw e
                }
            }
        }

        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            kInChannel.sendBlocking(msg as ByteBuf)
            val cnt = messageReadCount.incrementAndGet()
            if (cnt == 2) {
                val (local, remote) = runBlocking { withTimeout(5000) { deferred!!.await() }}
                val secIoCodec = SecIoCodec(local, remote)
                nonce = local.nonce
                ctx.channel().pipeline().addBefore(HandshakeHandlerName, "SecIoCodec", secIoCodec)
                writeAndFlush(ctx, remote.nonce.toByteBuf())
            } else if (cnt == 3) {
                if (!nonce!!.contentEquals(msg.toByteArray())) throw InvalidInitialPacket()
                ctx.channel().pipeline().remove(HandshakeHandlerName)
                ctx.fireChannelActive()
            } else if (cnt > 3) {
                throw InvalidNegotiationState()
            }
        }

        private fun writeAndFlush(ctx: ChannelHandlerContext, bb: ByteBuf) {
            // this is a workaround for Netty limitation when messages could be reordered
            // when sending both from event loop and other thread
            // https://github.com/netty/netty/issues/3887
            // TODO the reorder may still happen when higher level handler is activated and immediately
            // sends a messages from the handler callback
            executor.execute {
                ctx.writeAndFlush(bb)
            }
        }

        override fun channelUnregistered(ctx: ChannelHandlerContext?) {
            executor.shutdown()
        }

        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            cause.printStackTrace() // TODO logging
            kInChannel.close(cause)
            ctx.channel().close()
        }
    }
}

