package io.libp2p.core.security.secio

import io.libp2p.core.PeerId
import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.protocol.Mode
import io.libp2p.core.protocol.ProtocolMatcher
import io.libp2p.core.protocol.SecureChannel
import io.libp2p.core.util.replace
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelInitializer
import io.netty.handler.codec.LengthFieldBasedFrameDecoder
import io.netty.handler.codec.LengthFieldPrepender
import io.netty.channel.Channel as NettyChannel


class SecIoSecureChannel(val localKey: PrivKey, val remotePeerId: PeerId? = null) :
    SecureChannel {

    private val HandshakeHandlerName = "SecIoHandshake"
    private val HadshakeTimeout = 30 * 1000L

    override val matcher = ProtocolMatcher(Mode.STRICT, name = "/secio/1.0.0")

    override fun initializer(): ChannelInitializer<NettyChannel> =
        object : ChannelInitializer<NettyChannel>() {
            override fun initChannel(ch: NettyChannel) {
                ch.pipeline().replace(
                    this, listOf(
                        "PacketLenEncoder" to LengthFieldPrepender(4),
                        "PacketLenDecoder" to LengthFieldBasedFrameDecoder(Integer.MAX_VALUE, 0, 4, 0, 4),
                        HandshakeHandlerName to SecIoHandshake()
                    )
                )
            }
        }

    inner class SecIoHandshake : ChannelInboundHandlerAdapter() {
        private var negotiator: SecioHandshake? = null
        private var activated = false

        override fun channelActive(ctx: ChannelHandlerContext) {
            if (!activated) {
                activated = true
                negotiator = SecioHandshake({ buf -> writeAndFlush(ctx, buf) }, localKey, remotePeerId)
                negotiator!!.start()
            }
        }

        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            // it seems there is no guarantee from Netty that channelActive() must be called before channelRead()
            channelActive(ctx)

            val keys = negotiator!!.onNewMessage(msg as ByteBuf)

            if (keys != null) {
                val secIoCodec = SecIoCodec(keys.first, keys.second)
                ctx.channel().pipeline().addBefore(HandshakeHandlerName, "SecIoCodec", secIoCodec)
                negotiator!!.onSecureChannelSetup()
            }

            if (negotiator!!.isComplete()) {
                ctx.channel().pipeline().remove(HandshakeHandlerName)
                ctx.fireChannelActive()
            }
        }

        private fun writeAndFlush(ctx: ChannelHandlerContext, bb: ByteBuf) {
            ctx.writeAndFlush(bb)
        }

        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            cause.printStackTrace() // TODO logging
            ctx.channel().close()
        }
    }
}

