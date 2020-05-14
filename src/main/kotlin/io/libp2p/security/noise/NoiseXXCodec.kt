package io.libp2p.security.noise

import com.southernstorm.noise.protocol.CipherState
import io.libp2p.etc.types.toByteArray
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.MessageToMessageCodec
import org.apache.logging.log4j.LogManager

private val logger = LogManager.getLogger(NoiseXXSecureChannel::class.java.name)

class NoiseXXCodec(val aliceCipher: CipherState, val bobCipher: CipherState) : MessageToMessageCodec<ByteBuf, ByteBuf>() {

    override fun encode(ctx: ChannelHandlerContext, msg: ByteBuf, out: MutableList<Any>) {
        val plainLength = msg.readableBytes()
        val buf = ByteArray(plainLength + aliceCipher.macLength)
        msg.readBytes(buf, 0, plainLength)
        val length = aliceCipher.encryptWithAd(null, buf, 0, buf, 0, plainLength)
        out += Unpooled.wrappedBuffer(buf, 0, length)
    }

    override fun decode(ctx: ChannelHandlerContext, msg: ByteBuf, out: MutableList<Any>) {
        val buf = msg.toByteArray()
        val decryptLen = bobCipher.decryptWithAd(null, buf, 0, buf, 0, buf.size)
        out += Unpooled.wrappedBuffer(buf, 0, decryptLen)
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        logger.error(cause)
    }
}