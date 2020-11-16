package io.libp2p.security.noise

import com.southernstorm.noise.protocol.CipherState
import io.libp2p.etc.types.hasCauseOfType
import io.libp2p.etc.types.toByteArray
import io.libp2p.security.CantDecryptInboundException
import io.libp2p.security.SecureChannelError
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.MessageToMessageCodec
import org.apache.logging.log4j.LogManager
import java.io.IOException
import java.security.GeneralSecurityException

private val logger = LogManager.getLogger(NoiseXXSecureChannel::class.java.name)

class NoiseXXCodec(val aliceCipher: CipherState, val bobCipher: CipherState) :
    MessageToMessageCodec<ByteBuf, ByteBuf>() {

    private var abruptlyClosing = false

    override fun encode(ctx: ChannelHandlerContext, msg: ByteBuf, out: MutableList<Any>) {
        val plainLength = msg.readableBytes()
        val buf = ByteArray(plainLength + aliceCipher.macLength)
        msg.readBytes(buf, 0, plainLength)
        val length = aliceCipher.encryptWithAd(null, buf, 0, buf, 0, plainLength)
        out += Unpooled.wrappedBuffer(buf, 0, length)
    }

    override fun decode(ctx: ChannelHandlerContext, msg: ByteBuf, out: MutableList<Any>) {
        if (abruptlyClosing) {
            // if abrupt close was initiated by our node we shouldn't try decoding anything else
            return
        }
        val buf = msg.toByteArray()
        val decryptLen = try {
            bobCipher.decryptWithAd(null, buf, 0, buf, 0, buf.size)
        } catch (e: GeneralSecurityException) {
            throw CantDecryptInboundException("Unable to decrypt a message from remote", e)
        }
        out += Unpooled.wrappedBuffer(buf, 0, decryptLen)
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        if (cause.hasCauseOfType(IOException::class)) {
            // Trace level because having clients unexpectedly disconnect is extremely common
            logger.trace("IOException in Noise channel", cause)
        } else if (cause.hasCauseOfType(SecureChannelError::class)) {
            logger.debug("Invalid Noise content", cause)
            closeAbruptly(ctx)
        } else {
            logger.error("Unexpected error in Noise channel", cause)
        }
    }

    private fun closeAbruptly(ctx: ChannelHandlerContext) {
        abruptlyClosing = true
        ctx.close()
    }
}
